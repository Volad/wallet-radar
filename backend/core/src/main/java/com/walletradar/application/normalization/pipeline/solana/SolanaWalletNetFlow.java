package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkStablecoinContracts;
import com.walletradar.platform.networks.solana.SolanaChain;
import org.bson.Document;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Package-private utility: computes the wallet's net per-mint token delta and related stable/value
 * flow signals from a Helius-parsed Solana transaction.
 *
 * <p>All methods are stateless and side-effect-free. Callers in the same package
 * ({@link SolanaTransactionClassifier}, {@link SolanaFlowShape}) use these to derive flow shape
 * without duplicating the accountData / tokenTransfers parsing logic.</p>
 *
 * <p>W17 extraction: moved verbatim from {@link SolanaTransactionClassifier}. No decision logic
 * was changed; only the class boundary shifted.</p>
 */
final class SolanaWalletNetFlow {

    static final BigDecimal LAMPORTS_PER_SOL = BigDecimal.valueOf(1_000_000_000L);

    /**
     * Native SOL dust floor (in SOL) for flow-shape counting: net wallet SOL deltas below this are
     * rent/noise and are not counted as an economic side. Mirrors the builder's dust threshold.
     */
    static final BigDecimal NATIVE_SOL_DUST_THRESHOLD = new BigDecimal("0.000001");

    private SolanaWalletNetFlow() {
    }

    /**
     * Net per-mint wallet delta (native SOL keyed as wSOL) for flow-shape inference. Prefers the
     * authoritative {@code accountData[].tokenBalanceChanges} (owner == wallet) plus the wallet's
     * native SOL delta; falls back to {@code tokenTransfers} + {@code nativeTransfers} when no
     * account-level balance changes are present. Native SOL deltas below the dust floor are ignored;
     * strictly-zero SPL deltas are dropped, and zero-net mints (in==out) are pruned.
     */
    static Map<String, BigDecimal> walletNetByMint(SolanaRawTransactionView view) {
        String wallet = view.walletAddress();
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        if (wallet == null) {
            return net;
        }

        BigDecimal solDelta = BigDecimal.ZERO;
        boolean sawAccountData = false;
        for (Document account : view.accountData()) {
            if (account == null) {
                continue;
            }
            if (wallet.equals(account.getString("account"))) {
                long nativeChange = toLong(account.get("nativeBalanceChange"));
                if (nativeChange != 0) {
                    solDelta = solDelta.add(BigDecimal.valueOf(nativeChange).divide(LAMPORTS_PER_SOL));
                }
            }
            for (Document change : docList(account, "tokenBalanceChanges")) {
                if (change == null || !wallet.equals(change.getString("userAccount"))) {
                    continue;
                }
                String mint = change.getString("mint");
                if (mint == null || mint.isBlank()) {
                    continue;
                }
                BigDecimal delta = rawTokenAmountToDecimal(change.get("rawTokenAmount"), mint.trim());
                if (delta == null || delta.signum() == 0) {
                    continue;
                }
                sawAccountData = true;
                net.merge(mint.trim(), delta, BigDecimal::add);
            }
        }

        if (sawAccountData) {
            // The native SOL delta from accountData embeds the tx fee ONLY when the wallet paid it;
            // add back the wallet-scoped fee so a pure fee payment is not miscounted as an economic
            // outflow (and third-party-paid fees are not falsely added back).
            BigDecimal walletSolDelta = solDelta.add(
                    BigDecimal.valueOf(view.walletFeeInLamports()).divide(LAMPORTS_PER_SOL));
            if (walletSolDelta.abs().compareTo(NATIVE_SOL_DUST_THRESHOLD) >= 0) {
                net.merge(SolanaChain.WSOL_MINT, walletSolDelta, BigDecimal::add);
            }
        } else {
            accumulateTransferNet(view, wallet, net);
        }

        net.values().removeIf(v -> v.signum() == 0);
        return net;
    }

    /**
     * True when the tracked wallet's own token accounts show any (gross, non-net) movement of a
     * SOLANA USD-stable mint. Used to tell a genuine leveraged loop-open (wallet borrows a stablecoin
     * and swaps it to collateral within one tx) apart from a plain collateral deposit that merely
     * co-locates with an unrelated Jupiter swap whose stable legs are owned by maker/pool accounts.
     */
    static boolean walletHasStableFlow(SolanaRawTransactionView view) {
        String wallet = view.walletAddress();
        if (wallet == null) {
            return false;
        }
        boolean sawAccountData = false;
        for (Document account : view.accountData()) {
            if (account == null) {
                continue;
            }
            for (Document change : docList(account, "tokenBalanceChanges")) {
                if (change == null || !wallet.equals(change.getString("userAccount"))) {
                    continue;
                }
                String mint = change.getString("mint");
                if (mint == null || mint.isBlank()) {
                    continue;
                }
                sawAccountData = true;
                if (NetworkStablecoinContracts.forNetwork(NetworkId.SOLANA).contains(mint.trim())) {
                    BigDecimal delta = rawTokenAmountToDecimal(change.get("rawTokenAmount"), mint.trim());
                    if (delta != null && delta.signum() != 0) {
                        return true;
                    }
                }
            }
        }
        if (!sawAccountData) {
            for (Document transfer : view.tokenTransfers()) {
                String mint = transfer.getString("mint");
                if (mint == null || !NetworkStablecoinContracts.forNetwork(NetworkId.SOLANA).contains(mint.trim())) {
                    continue;
                }
                String from = transfer.getString("fromUserAccount");
                String to = transfer.getString("toUserAccount");
                if (!wallet.equals(from) && !wallet.equals(to)) {
                    continue;
                }
                BigDecimal amount = parseBigDecimal(transfer.get("tokenAmount"));
                if (amount != null && amount.signum() != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when the co-located swap is a THIRD-PARTY swap the tracked wallet does not participate in:
     * there is a token transfer of a value mint (wSOL or a SOLANA USD-stable) whose BOTH endpoints are
     * accounts other than the tracked wallet (maker/pool ↔ maker/pool). A genuine leveraged loop routes
     * the borrowed stablecoin and re-supplied collateral through the WALLET's own token accounts, so its
     * value legs always touch the wallet; a plain SOL deposit that merely shares a transaction/CPI with
     * an unrelated Jupiter swap shows the swap's stable/SOL legs owned by maker/pool accounts only.
     *
     * <p>Returns {@code false} when the wallet's balance evidence is authoritative {@code accountData}
     * with no {@code tokenTransfers} (the WS-1 B1 anchor, where a native-SOL wrap is co-located with a
     * Jupiter Swap and there are no visible foreign legs) — those are genuine wallet-participating loops.
     */
    static boolean hasForeignValueSwapLegs(SolanaRawTransactionView view) {
        String wallet = view.walletAddress();
        if (wallet == null) {
            return false;
        }
        for (Document transfer : view.tokenTransfers()) {
            String mint = transfer.getString("mint");
            if (mint == null) {
                continue;
            }
            String trimmed = mint.trim();
            boolean valueMint = SolanaChain.WSOL_MINT.equals(trimmed)
                    || NetworkStablecoinContracts.forNetwork(NetworkId.SOLANA).contains(trimmed);
            if (!valueMint) {
                continue;
            }
            BigDecimal amount = parseBigDecimal(transfer.get("tokenAmount"));
            if (amount == null || amount.signum() == 0) {
                continue;
            }
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            if (!wallet.equals(from) && !wallet.equals(to)) {
                return true;
            }
        }
        return false;
    }

    static void accumulateTransferNet(SolanaRawTransactionView view, String wallet,
                                      Map<String, BigDecimal> net) {
        for (Document transfer : view.tokenTransfers()) {
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            String mint = transfer.getString("mint");
            BigDecimal amount = parseBigDecimal(transfer.get("tokenAmount"));
            if (mint == null || mint.isBlank() || amount == null || amount.signum() == 0) {
                continue;
            }
            if (wallet.equals(to)) {
                net.merge(mint.trim(), amount, BigDecimal::add);
            }
            if (wallet.equals(from)) {
                net.merge(mint.trim(), amount.negate(), BigDecimal::add);
            }
        }
        BigDecimal solDelta = BigDecimal.ZERO;
        for (Document transfer : view.nativeTransfers()) {
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            long lamports = toLong(transfer.get("amount"));
            if (lamports <= 0) {
                continue;
            }
            BigDecimal amount = BigDecimal.valueOf(lamports).divide(LAMPORTS_PER_SOL);
            if (wallet.equals(to)) {
                solDelta = solDelta.add(amount);
            }
            if (wallet.equals(from)) {
                solDelta = solDelta.subtract(amount);
            }
        }
        if (solDelta.abs().compareTo(NATIVE_SOL_DUST_THRESHOLD) >= 0) {
            net.merge(SolanaChain.WSOL_MINT, solDelta, BigDecimal::add);
        }
    }

    // -----------------------------------------------------------------------
    // Raw-data utilities (package-accessible; shared with SolanaFlowShape)
    // -----------------------------------------------------------------------

    static long toLong(Object value) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    static BigDecimal parseBigDecimal(Object value) {
        if (value instanceof Number num) {
            return BigDecimal.valueOf(num.doubleValue());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static BigDecimal rawTokenAmountToDecimal(Object rawTokenAmount, String mint) {
        if (!(rawTokenAmount instanceof Document doc)) {
            return null;
        }
        BigDecimal raw = parseBigDecimal(doc.get("tokenAmount"));
        if (raw == null) {
            return null;
        }
        int decimals = (int) toLong(doc.get("decimals"));
        if (decimals <= 0) {
            Integer seeded = SolanaSplTokenMetadataRegistry.decimals(mint);
            if (seeded != null && seeded > 0) {
                return raw.movePointLeft(seeded);
            }
            return raw;
        }
        return raw.movePointLeft(decimals);
    }

    static List<Document> docList(Document parent, String key) {
        if (parent == null) {
            return List.of();
        }
        Object val = parent.get(key);
        if (val instanceof List<?> list) {
            List<Document> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Document doc) {
                    result.add(doc);
                }
            }
            return result;
        }
        return List.of();
    }
}
