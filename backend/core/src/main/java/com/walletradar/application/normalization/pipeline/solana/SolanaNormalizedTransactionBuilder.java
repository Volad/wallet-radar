package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkStablecoinContracts;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.NormalizedCapabilityFlagStamper;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.platform.networks.solana.SolanaChain;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds canonical {@link NormalizedTransaction} documents from Helius-parsed Solana raw evidence.
 *
 * <p>Flow construction rules:
 * <ul>
 *   <li>Fee flow: always emitted for {@code fee > 0} lamports → SOL quantity negative.</li>
 *   <li>SWAP: uses {@code events.swap} token inputs/outputs; falls back to {@code tokenTransfers} filtered by wallet.</li>
 *   <li>TRANSFER: uses {@code nativeTransfers} + {@code tokenTransfers} filtered by wallet direction.</li>
 *   <li>LP / LENDING / VAULT: uses {@code tokenTransfers} filtered by wallet address (principal in/out).</li>
 * </ul>
 * </p>
 */
@Component
public class SolanaNormalizedTransactionBuilder {

    private static final Logger log = LoggerFactory.getLogger(SolanaNormalizedTransactionBuilder.class);

    private static final BigDecimal LAMPORTS_PER_SOL = BigDecimal.valueOf(1_000_000_000L);
    private static final String SOL_SYMBOL = "SOL";

    /**
     * RC-S5 dust thresholds (per asset class). Inbound transfer legs below these are ignored as
     * dust/scam so they do not create spurious economic acquisitions — but the FEE leg is never
     * dropped. Native SOL: 0.000001 SOL (1,000 lamports). SPL: 0 (only strictly-zero amounts are
     * filtered, since SPL dust cannot be judged without decimals/USD value at normalization time).
     */
    private static final BigDecimal NATIVE_SOL_DUST_THRESHOLD = new BigDecimal("0.000001");
    private static final BigDecimal SPL_DUST_THRESHOLD = BigDecimal.ZERO;

    private final SolanaTransactionClassifier classifier;
    private final AccountingUniverseService accountingUniverseService;

    public SolanaNormalizedTransactionBuilder(
            SolanaTransactionClassifier classifier,
            AccountingUniverseService accountingUniverseService
    ) {
        this.classifier = classifier;
        this.accountingUniverseService = accountingUniverseService;
    }

    public NormalizedTransaction build(RawTransaction rawTransaction, Instant now) {
        SolanaRawTransactionView view = SolanaRawTransactionView.wrap(rawTransaction);

        SolanaClassificationResult classification = classifier.classify(view);

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(rawTransaction.getId());
        tx.setTxHash(view.signature());
        tx.setNetworkId(NetworkId.SOLANA);
        tx.setWalletAddress(view.walletAddress());
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setBlockTimestamp(view.blockTimestamp());
        tx.setType(classification.type());
        tx.setProtocolName(classification.protocolName());
        tx.setClassifiedBy(ClassificationSource.HEURISTIC);
        tx.setConfidence(ConfidenceLevel.MEDIUM);

        NormalizedTransactionStatus status = classification.needsReview()
                ? NormalizedTransactionStatus.NEEDS_REVIEW
                : NormalizedTransactionStatus.PENDING_PRICE;
        tx.setStatus(status);

        if (classification.needsReview()) {
            tx.setMissingDataReasons(List.of("SOLANA_UNCLASSIFIED"));
        }

        tx.setFlows(buildFlows(view, classification));

        // RC-S-LP: link an NFT-based Meteora DLMM LP_ENTRY and its later LP_EXIT into the same
        // position-scoped basis pool. The position PDA is deterministic and appears on both legs, so
        // basis is carried through the position without any read-path RPC. When no direct DLMM
        // position is present (Hawksight-wrapped shapes, non-DLMM protocols) the correlation stays
        // null and the transaction rides the generic family-continuity bucket instead.
        if (isPositionScopedLpType(classification.type())) {
            String lpCorrelationId = SolanaLpPositionResolver.resolveCorrelationId(view);
            if (lpCorrelationId != null) {
                tx.setCorrelationId(lpCorrelationId);
                // Capture the Meteora DLMM LbPair pool address (accounts[1]) at normalization time.
                // The LbPair pool account is shared and persists on-chain even after the user's
                // position PDA is closed/reclaimed, so it is the only durable source of the SOL/<SPL>
                // pair for a later-closed single-sided position. The correlation id stays keyed on
                // the position PDA (basis-pool continuity); this is auxiliary enrichment metadata.
                // null for Raydium CLMM and any non-DLMM shape.
                String lpPoolAddress = SolanaLpPositionResolver.resolveLpPoolAddress(view);
                if (lpPoolAddress != null) {
                    tx.setLpPoolAddress(lpPoolAddress);
                }
                // RC-S-LP-CLOSE: a concentrated-liquidity remove that also deallocates the position
                // account (rent reclaimed) is the TERMINAL exit. Promote it to LP_EXIT_FINAL so the
                // cost-basis replay drains every residual per-asset basis pool for this position and
                // books the leftover as realized LP PnL, rather than leaving phantom "still held"
                // quantities when a CLMM/DLMM position returns a different asset ratio than deposited
                // (impermanent loss / rebalancing). A partial remove leaves the position account open
                // and stays LP_EXIT, so residual basis is preserved until the real close.
                if (classification.type() == NormalizedTransactionType.LP_EXIT
                        && SolanaLpPositionResolver.isFullPositionClose(view)) {
                    tx.setType(NormalizedTransactionType.LP_EXIT_FINAL);
                }
                // ADR-081 (C1): flag the wallet's Meteora DAMM MLP receipt leg as an LP receipt so
                // replay stamps its ledger point FAMILY:LP_RECEIPT (the fungible MLP symbol is
                // confusable across pools, so the family MUST come from LP-correlation membership —
                // the durable identity/flag route — not from the symbol). DLMM/CLMM positions have no
                // fungible wallet receipt (NFT/PDA), so resolveLpReceiptMint returns null for them.
                markDammLpReceiptLeg(tx, SolanaLpPositionResolver.resolveLpReceiptMint(view));
            }
        }

        // ADR-081 (C1): a Meteora-farm stake/unstake only ever moves the fungible LP receipt (the
        // DAMM MLP / farm LP token) — reward harvests are classified REWARD_CLAIM, not STAKE/UNSTAKE.
        // The farm instruction does not expose the DAMM pool, so the lpMint cannot be resolved from
        // the DAMM IDL here; instead flag every SPL receipt leg of the farm move so replay stamps
        // FAMILY:LP_RECEIPT on the stake/unstake bucket point too (not only LP_ENTRY). Without this,
        // C7's identity exclusion keys on the latest bucket point (the UNSTAKE) and misses it.
        if (classification.type() == NormalizedTransactionType.LP_POSITION_STAKE
                || classification.type() == NormalizedTransactionType.LP_POSITION_UNSTAKE) {
            markFarmStakedReceiptLegs(view, tx);
        }

        // RULE 1 — stable loan correlation for the SOLANA borrow_liabilities book. The order id is
        // deterministic per wallet + debt mint + network, so repeated borrows aggregate into one
        // liability and a later REPAY nets against it (mirrors the EVM correlation-id order key).
        if (classification.type() == NormalizedTransactionType.BORROW
                || classification.type() == NormalizedTransactionType.REPAY) {
            String loanCorrelationId = resolveBorrowLoanCorrelationId(tx, view.walletAddress());
            if (loanCorrelationId != null) {
                tx.setCorrelationId(loanCorrelationId);
            }
        }

        tx.setPricingAttempts(0);
        tx.setStatAttempts(0);
        tx.setCreatedAt(now);
        tx.setUpdatedAt(now);

        // WS-8 (ADR-073/074): stamp capability flags at the shared ingestion seam after the
        // correlation id is finalized. receiptBearingCollateral=false (Solana lending is
        // receipt-less) and lpConcentrated=true for Meteora DLMM / Raydium CLMM position-scoped LP
        // are derived here uniformly with EVM/TON — and re-derived if this row is later reclassified.
        NormalizedCapabilityFlagStamper.stamp(tx);

        return tx;
    }

    private List<NormalizedTransaction.Flow> buildFlows(
            SolanaRawTransactionView view,
            SolanaClassificationResult classification
    ) {
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();

        // Emit the fee flow only when the TRACKED wallet actually paid the fee. Helius reports
        // meta.fee for every tx the wallet appears in (including bot/protocol-paid rebalances such as
        // Hawksight Meteora), so gating on the fee payer avoids overstating gas paid.
        long feeInLamports = view.walletFeeInLamports();
        if (feeInLamports > 0) {
            NormalizedTransaction.Flow feeFlow = new NormalizedTransaction.Flow();
            feeFlow.setRole(NormalizedLegRole.FEE);
            feeFlow.setAssetContract(SolanaChain.WSOL_MINT);
            feeFlow.setAssetSymbol(SOL_SYMBOL);
            feeFlow.setQuantityDelta(BigDecimal.valueOf(-feeInLamports).divide(LAMPORTS_PER_SOL));
            flows.add(feeFlow);
        }

        String walletAddress = view.walletAddress();
        if (walletAddress == null) {
            return flows;
        }

        NormalizedTransactionType type = classification.type();

        switch (type) {
            case SWAP -> buildSwapFlows(view, walletAddress, flows);
            case EXTERNAL_TRANSFER_IN, EXTERNAL_TRANSFER_OUT -> buildTransferFlows(view, walletAddress, flows);
            case INTERNAL_TRANSFER -> buildInternalTransferFlows(view, walletAddress, flows);
            case LP_ENTRY, LP_EXIT, LP_FEE_CLAIM -> buildLpFlows(view, walletAddress, type, flows);
            // ADR-081 (C1): Meteora farm LP-receipt stake/unstake are non-priced continuity moves of
            // the fungible MLP receipt (and any farm receipt), NOT market BUY/SELL. Routing them
            // through the LP TRANSFER path (not the generic swap-shape builder) prevents booking the
            // staked MLP as a disposal — the mis-tag that produced a spurious realized-P&L loss.
            case LP_POSITION_STAKE, LP_POSITION_UNSTAKE -> buildLpFlows(view, walletAddress, type, flows);
            case LENDING_DEPOSIT, LENDING_WITHDRAW, BORROW, REPAY,
                    LENDING_LOOP_OPEN, LENDING_LOOP_REBALANCE -> buildLendingFlows(view, walletAddress, type, flows);
            case VAULT_DEPOSIT, VAULT_WITHDRAW -> buildVaultFlows(view, walletAddress, type, flows);
            case STAKING_DEPOSIT, STAKING_WITHDRAW -> buildStakingFlows(view, walletAddress, type, flows);
            // RC-S6: compressed-NFT mint and SPL/system housekeeping are non-economic → fee-only.
            case NFT_MINT, ADMIN_CONFIG -> { /* no economic flows; the fee leg above is sufficient */ }
            default -> buildGenericFlows(view, walletAddress, flows);
        }

        return flows;
    }

    // --- SWAP ---

    private void buildSwapFlows(SolanaRawTransactionView view, String walletAddress, List<NormalizedTransaction.Flow> flows) {
        // RC-S4: prefer events.swap; if it does not yield both a SELL and a BUY leg, reconstruct net
        // per-mint wallet deltas from accountData[].tokenBalanceChanges (+ native SOL delta); finally
        // fall back to per-transfer classification from tokenTransfers.
        List<NormalizedTransaction.Flow> swapFlows = new ArrayList<>();
        Document swapEvent = view.swapEvent();
        if (swapEvent != null) {
            buildSwapFlowsFromEvent(swapEvent, walletAddress, swapFlows);
        }
        if (!hasBuyAndSell(swapFlows)) {
            List<NormalizedTransaction.Flow> reconstructed = reconstructSwapFromAccountData(view, walletAddress);
            if (hasBuyAndSell(reconstructed)) {
                swapFlows = reconstructed;
            } else if (swapFlows.isEmpty()) {
                buildSwapFlowsFromTokenTransfers(view.tokenTransfers(), walletAddress, swapFlows);
            }
        }
        flows.addAll(swapFlows);
    }

    private static boolean hasBuyAndSell(List<NormalizedTransaction.Flow> flows) {
        boolean hasBuy = false;
        boolean hasSell = false;
        for (NormalizedTransaction.Flow flow : flows) {
            if (flow == null) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.BUY) {
                hasBuy = true;
            } else if (flow.getRole() == NormalizedLegRole.SELL) {
                hasSell = true;
            }
        }
        return hasBuy && hasSell;
    }

    /**
     * RC-S4: reconstructs swap legs from net per-mint wallet balance deltas in
     * {@code accountData[].tokenBalanceChanges} (owner == wallet) plus the native SOL delta.
     * Each net-negative mint becomes a SELL, each net-positive mint a BUY. Multi-hop swaps net per
     * mint so intermediate hops cancel. Legs below the per-asset dust threshold are dropped.
     */
    private List<NormalizedTransaction.Flow> reconstructSwapFromAccountData(SolanaRawTransactionView view, String walletAddress) {
        List<NormalizedTransaction.Flow> reconstructed = new ArrayList<>();
        if (walletAddress == null) {
            return reconstructed;
        }
        Map<String, BigDecimal> netByMint = new LinkedHashMap<>();
        BigDecimal solDelta = BigDecimal.ZERO;
        boolean sawWalletAccount = false;

        for (Document account : view.accountData()) {
            if (account == null) {
                continue;
            }
            if (walletAddress.equals(account.getString("account"))) {
                sawWalletAccount = true;
                long nativeChange = toLong(account.get("nativeBalanceChange"));
                if (nativeChange != 0) {
                    solDelta = solDelta.add(BigDecimal.valueOf(nativeChange).divide(LAMPORTS_PER_SOL));
                }
            }
            for (Document change : docList(account, "tokenBalanceChanges")) {
                if (change == null || !walletAddress.equals(change.getString("userAccount"))) {
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
                netByMint.merge(mint.trim(), delta, BigDecimal::add);
            }
        }

        // Native SOL leg: add back the transaction fee (which is embedded in nativeBalanceChange but
        // is not part of the swap economics). Only when no wSOL token account already captured it.
        // Wallet-scoped fee: when a third party paid the fee it is NOT embedded in the wallet's
        // nativeBalanceChange, so nothing must be added back (walletFeeInLamports() returns 0).
        if (sawWalletAccount && !netByMint.containsKey(SolanaChain.WSOL_MINT)) {
            long feeInLamports = view.walletFeeInLamports();
            BigDecimal swapSolDelta = solDelta.add(BigDecimal.valueOf(feeInLamports).divide(LAMPORTS_PER_SOL));
            if (swapSolDelta.abs().compareTo(NATIVE_SOL_DUST_THRESHOLD) >= 0) {
                netByMint.merge(SolanaChain.WSOL_MINT, swapSolDelta, BigDecimal::add);
            }
        }

        for (Map.Entry<String, BigDecimal> entry : netByMint.entrySet()) {
            String mint = entry.getKey();
            BigDecimal net = entry.getValue();
            if (net.signum() == 0) {
                continue;
            }
            boolean isSol = SolanaChain.WSOL_MINT.equals(mint);
            BigDecimal threshold = isSol ? NATIVE_SOL_DUST_THRESHOLD : SPL_DUST_THRESHOLD;
            if (net.abs().compareTo(threshold) <= 0 && threshold.signum() > 0) {
                continue;
            }
            NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
            flow.setRole(net.signum() < 0 ? NormalizedLegRole.SELL : NormalizedLegRole.BUY);
            flow.setAssetContract(mint);
            flow.setAssetSymbol(resolveMintSymbol(view, mint));
            flow.setQuantityDelta(net);
            reconstructed.add(flow);
        }
        return reconstructed;
    }

    private String resolveMintSymbol(SolanaRawTransactionView view, String mint) {
        if (SolanaChain.WSOL_MINT.equals(mint)) {
            return SOL_SYMBOL;
        }
        for (Document transfer : view.tokenTransfers()) {
            if (transfer == null || !mint.equals(transfer.getString("mint"))) {
                continue;
            }
            String symbol = transfer.getString("symbol");
            if (symbol == null || symbol.isBlank()) {
                symbol = transfer.getString("tokenSymbol");
            }
            if (symbol != null && !symbol.isBlank()) {
                return symbol.trim().toUpperCase(Locale.ROOT);
            }
        }
        return SolanaSplTokenMetadataRegistry.symbol(mint);
    }

    /**
     * Resolves a flow's asset symbol from (1) the Helius payload symbol, then (2) the config-seeded
     * SPL registry (USDC/USDT/wSOL), then (3) the wSOL→SOL native alias. Returns {@code null} for an
     * unknown/unseeded mint — the flow is still well-formed (assetContract carries the mint) and
     * prices by contract; it must never force CLASSIFICATION_FAILED.
     */
    private static String resolveSymbol(String payloadSymbol, String mint) {
        if (payloadSymbol != null && !payloadSymbol.isBlank()) {
            return payloadSymbol.trim().toUpperCase(Locale.ROOT);
        }
        String seeded = SolanaSplTokenMetadataRegistry.symbol(mint);
        if (seeded != null && !seeded.isBlank()) {
            return seeded;
        }
        if (SolanaChain.WSOL_MINT.equals(mint)) {
            return SOL_SYMBOL;
        }
        return null;
    }

    private static BigDecimal rawTokenAmountToDecimal(Object rawTokenAmount, String mint) {
        if (!(rawTokenAmount instanceof Document doc)) {
            return null;
        }
        BigDecimal raw = parseBigDecimal(doc.get("tokenAmount"));
        if (raw == null) {
            return null;
        }
        int decimals = (int) toLong(doc.get("decimals"));
        if (decimals <= 0) {
            // Helius omitted decimals: fall back to the seeded SPL registry (USDC/USDT=6, wSOL=9)
            // so a known-mint raw amount is not booked at 10^decimals × the true quantity.
            Integer seeded = SolanaSplTokenMetadataRegistry.decimals(mint);
            if (seeded != null && seeded > 0) {
                return raw.movePointLeft(seeded);
            }
            return raw;
        }
        return raw.movePointLeft(decimals);
    }

    private void buildSwapFlowsFromEvent(Document swapEvent, String walletAddress, List<NormalizedTransaction.Flow> flows) {
        List<Document> tokenInputs = docList(swapEvent, "tokenInputs");
        List<Document> tokenOutputs = docList(swapEvent, "tokenOutputs");

        for (Document input : tokenInputs) {
            String userAccount = input.getString("userAccount");
            if (walletAddress.equals(userAccount)) {
                NormalizedTransaction.Flow flow = tokenTransferToFlow(input, NormalizedLegRole.SELL, true);
                if (flow != null) {
                    flows.add(flow);
                }
            }
        }
        for (Document output : tokenOutputs) {
            String userAccount = output.getString("userAccount");
            if (walletAddress.equals(userAccount)) {
                NormalizedTransaction.Flow flow = tokenTransferToFlow(output, NormalizedLegRole.BUY, false);
                if (flow != null) {
                    flows.add(flow);
                }
            }
        }

        // Handle native SOL inputs/outputs
        Document nativeInput = swapEvent.get("nativeInput", Document.class);
        Document nativeOutput = swapEvent.get("nativeOutput", Document.class);
        if (nativeInput != null) {
            String userAccount = nativeInput.getString("userAccount");
            if (walletAddress.equals(userAccount)) {
                NormalizedTransaction.Flow flow = nativeSolFlow(nativeInput, NormalizedLegRole.SELL, true);
                if (flow != null) {
                    flows.add(flow);
                }
            }
        }
        if (nativeOutput != null) {
            String userAccount = nativeOutput.getString("userAccount");
            if (walletAddress.equals(userAccount)) {
                NormalizedTransaction.Flow flow = nativeSolFlow(nativeOutput, NormalizedLegRole.BUY, false);
                if (flow != null) {
                    flows.add(flow);
                }
            }
        }
    }

    private void buildSwapFlowsFromTokenTransfers(List<Document> tokenTransfers, String walletAddress, List<NormalizedTransaction.Flow> flows) {
        for (Document transfer : tokenTransfers) {
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            if (walletAddress.equals(from)) {
                NormalizedTransaction.Flow flow = tokenTransferToFlow(transfer, NormalizedLegRole.SELL, true);
                if (flow != null) {
                    flows.add(flow);
                }
            } else if (walletAddress.equals(to)) {
                NormalizedTransaction.Flow flow = tokenTransferToFlow(transfer, NormalizedLegRole.BUY, false);
                if (flow != null) {
                    flows.add(flow);
                }
            }
        }
    }

    // --- TRANSFER ---

    private void buildTransferFlows(SolanaRawTransactionView view, String walletAddress, List<NormalizedTransaction.Flow> flows) {
        boolean hasInFlow = false;
        boolean hasOutFlow = false;

        for (Document transfer : view.nativeTransfers()) {
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            Object amtObj = transfer.get("amount");
            if (amtObj == null) {
                continue;
            }
            long lamports = toLong(amtObj);
            if (walletAddress.equals(to) && lamports > 0) {
                // RC-S5: drop inbound native-SOL dust (scam/rent noise); never touches the fee leg.
                BigDecimal inboundSol = BigDecimal.valueOf(lamports).divide(LAMPORTS_PER_SOL);
                if (inboundSol.compareTo(NATIVE_SOL_DUST_THRESHOLD) < 0) {
                    continue;
                }
                NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
                flow.setRole(NormalizedLegRole.TRANSFER);
                flow.setAssetContract(SolanaChain.WSOL_MINT);
                flow.setAssetSymbol(SOL_SYMBOL);
                flow.setQuantityDelta(inboundSol);
                flow.setCounterpartyAddress(from);
                flows.add(flow);
                hasInFlow = true;
            } else if (walletAddress.equals(from) && lamports > 0) {
                NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
                flow.setRole(NormalizedLegRole.TRANSFER);
                flow.setAssetContract(SolanaChain.WSOL_MINT);
                flow.setAssetSymbol(SOL_SYMBOL);
                flow.setQuantityDelta(BigDecimal.valueOf(-lamports).divide(LAMPORTS_PER_SOL));
                flow.setCounterpartyAddress(to);
                flows.add(flow);
                hasOutFlow = true;
            }
        }

        for (Document transfer : view.tokenTransfers()) {
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            if (walletAddress.equals(to)) {
                NormalizedTransaction.Flow flow = tokenTransferToFlow(transfer, NormalizedLegRole.TRANSFER, false);
                if (flow != null) {
                    flow.setCounterpartyAddress(from);
                    flows.add(flow);
                    hasInFlow = true;
                }
            } else if (walletAddress.equals(from)) {
                NormalizedTransaction.Flow flow = tokenTransferToFlow(transfer, NormalizedLegRole.TRANSFER, true);
                if (flow != null) {
                    flow.setCounterpartyAddress(to);
                    flows.add(flow);
                    hasOutFlow = true;
                }
            }
        }
    }

    private void buildInternalTransferFlows(SolanaRawTransactionView view, String walletAddress, List<NormalizedTransaction.Flow> flows) {
        buildTransferFlows(view, walletAddress, flows);
    }

    // --- LP ---

    private void buildLpFlows(SolanaRawTransactionView view, String walletAddress, NormalizedTransactionType type, List<NormalizedTransaction.Flow> flows) {
        for (Document transfer : view.tokenTransfers()) {
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            boolean outbound = walletAddress.equals(from);
            boolean inbound = walletAddress.equals(to);
            if (!outbound && !inbound) {
                continue;
            }

            if (type == NormalizedTransactionType.LP_FEE_CLAIM) {
                // Harvest income only — a zero-cost acquisition that must not carry LP principal basis.
                if (inbound) {
                    NormalizedTransaction.Flow flow = tokenTransferToFlow(transfer, NormalizedLegRole.LP_FEE_INCOME, false);
                    if (flow != null) {
                        flow.setCounterpartyAddress(from);
                        flows.add(flow);
                    }
                }
                continue;
            }

            // RC-S-LP: LP_ENTRY / LP_EXIT principal legs are basis-carrying continuity moves, NOT
            // market BUY/SELL. Booking a deposited underlying as a disposal (or a returned underlying
            // as a fresh acquisition) breaks AVCO and, for NFT-based Meteora DLMM positions where no
            // fungible LP token is minted, severs entry↔exit continuity. Emitting every wallet leg as
            // TRANSFER lets the position-scoped LP receipt pool (keyed by the lp-position correlation)
            // carry basis on entry and return it on exit; same-tx dust refunds net out safely.
            NormalizedTransaction.Flow flow = tokenTransferToFlow(transfer, NormalizedLegRole.TRANSFER, outbound);
            if (flow != null) {
                flow.setCounterpartyAddress(outbound ? to : from);
                flows.add(flow);
            }
        }
    }

    // --- LENDING ---

    /**
     * RULE 1 — builds Jupiter Lend flows from the wallet's NET per-mint delta (collateral SOL +
     * borrowable USD-stable mints only; the protocol position-receipt token and any other mint are
     * excluded as non-inventory position markers). Netting is essential for loop transactions: the
     * borrowed principal is acquired and swapped away within one tx, so its net is ~0 and it must not
     * surface as phantom SPOT SELL legs. Roles:
     * <ul>
     *   <li>DEPOSIT / WITHDRAW / LOOP: {@code TRANSFER} (basis-carrying move into/out of the lending
     *       position — collateral stays owned), sign preserved. Loop emits only the collateral leg.</li>
     *   <li>BORROW: the net-positive stablecoin is a {@code BUY} (the borrow inflow is acquired at
     *       market-at-borrow basis with a parallel {@code borrow_liabilities} row); a same-tx
     *       net-negative collateral leg is emitted as {@code TRANSFER} so the dispatcher parks it in
     *       the borrow-collateral continuity bucket.</li>
     *   <li>REPAY: the net-negative stablecoin is a {@code SELL} routed to the repay handler.</li>
     * </ul>
     */
    private void buildLendingFlows(SolanaRawTransactionView view, String walletAddress, NormalizedTransactionType type, List<NormalizedTransaction.Flow> flows) {
        Map<String, BigDecimal> net = lendingValueNetByMint(view, walletAddress);
        boolean loop = type == NormalizedTransactionType.LENDING_LOOP_OPEN
                || type == NormalizedTransactionType.LENDING_LOOP_REBALANCE;
        for (Map.Entry<String, BigDecimal> entry : net.entrySet()) {
            String mint = entry.getKey();
            BigDecimal delta = entry.getValue();
            if (delta.signum() == 0) {
                continue;
            }
            boolean stable = NetworkStablecoinContracts.forNetwork(NetworkId.SOLANA).contains(mint);
            // A loop's borrowed principal nets ~0 and is fully consumed by the swap; carry only the
            // net collateral change so no phantom stablecoin SELL leg is produced.
            if (loop && stable) {
                continue;
            }
            NormalizedLegRole role;
            switch (type) {
                case BORROW -> role = delta.signum() > 0 ? NormalizedLegRole.BUY : NormalizedLegRole.TRANSFER;
                case REPAY -> {
                    if (delta.signum() >= 0) {
                        continue;
                    }
                    role = NormalizedLegRole.SELL;
                }
                default -> role = NormalizedLegRole.TRANSFER;
            }
            NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
            flow.setRole(role);
            flow.setAssetContract(mint);
            String symbol = resolveMintSymbol(view, mint);
            if (symbol != null) {
                flow.setAssetSymbol(symbol);
            }
            flow.setQuantityDelta(delta);
            flows.add(flow);
        }
    }

    /**
     * Wallet net per-mint delta restricted to Jupiter Lend inventory assets — collateral SOL (wSOL
     * key, native + wrapped, fee added back) plus the SOLANA USD-stable mints. All other mints (the
     * protocol position-receipt token, memecoins) are excluded so they never enter inventory. Prefers
     * authoritative {@code accountData[].tokenBalanceChanges} (owner == wallet) and falls back to
     * {@code tokenTransfers} + {@code nativeTransfers} when account-level balances are absent.
     */
    private Map<String, BigDecimal> lendingValueNetByMint(SolanaRawTransactionView view, String walletAddress) {
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        BigDecimal solDelta = BigDecimal.ZERO;
        boolean sawWalletAccount = false;

        for (Document account : view.accountData()) {
            if (account == null) {
                continue;
            }
            if (walletAddress.equals(account.getString("account"))) {
                // The wallet's own account entry is the authoritative net-lamport ledger for the tx:
                // it already collapses a SOL→wSOL wrap into the wallet's temporary ATA (the wrap
                // funding leg and the wSOL leg net inside this single number). Its presence — not a
                // matching wSOL token-balance change — is what makes accountData trustworthy.
                sawWalletAccount = true;
                long nativeChange = toLong(account.get("nativeBalanceChange"));
                if (nativeChange != 0) {
                    solDelta = solDelta.add(BigDecimal.valueOf(nativeChange).divide(LAMPORTS_PER_SOL));
                }
            }
            for (Document change : docList(account, "tokenBalanceChanges")) {
                if (change == null || !walletAddress.equals(change.getString("userAccount"))) {
                    continue;
                }
                String mint = change.getString("mint");
                if (mint == null || mint.isBlank() || !isLendingValueMint(mint.trim())) {
                    continue;
                }
                BigDecimal delta = rawTokenAmountToDecimal(change.get("rawTokenAmount"), mint.trim());
                if (delta == null || delta.signum() == 0) {
                    continue;
                }
                net.merge(mint.trim(), delta, BigDecimal::add);
            }
        }

        if (sawWalletAccount) {
            // Authoritative path. The wallet's SOL economic delta is nativeBalanceChange (fee re-added)
            // PLUS any wSOL token-balance change already owned by the wallet — a wrap-and-keep nets to
            // zero, a wrap-and-supply nets to the true outflow. We deliberately do NOT also fold in raw
            // nativeTransfers/tokenTransfers here: a SOL→wSOL wrap emits BOTH a nativeTransfer into the
            // wallet's temp ATA and a wSOL tokenTransfer out to the protocol for the SAME lamports, so
            // summing them double-counted the collateral (e.g. a 0.5 SOL Jupiter Lend deposit booked as
            // -1.0 SOL when the wallet held no residual wSOL token account).
            BigDecimal walletSolDelta = solDelta.add(
                    BigDecimal.valueOf(view.walletFeeInLamports()).divide(LAMPORTS_PER_SOL));
            if (walletSolDelta.abs().compareTo(NATIVE_SOL_DUST_THRESHOLD) >= 0) {
                net.merge(SolanaChain.WSOL_MINT, walletSolDelta, BigDecimal::add);
            }
        } else {
            accumulateLendingTransferNet(view, walletAddress, net);
        }

        net.values().removeIf(v -> v.signum() == 0);
        return net;
    }

    private void accumulateLendingTransferNet(SolanaRawTransactionView view, String walletAddress, Map<String, BigDecimal> net) {
        for (Document transfer : view.tokenTransfers()) {
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            String mint = transfer.getString("mint");
            BigDecimal amount = parseBigDecimal(transfer.get("tokenAmount"));
            if (mint == null || mint.isBlank() || amount == null || amount.signum() == 0
                    || !isLendingValueMint(mint.trim())) {
                continue;
            }
            if (walletAddress.equals(to)) {
                net.merge(mint.trim(), amount, BigDecimal::add);
            }
            if (walletAddress.equals(from)) {
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
            if (walletAddress.equals(to)) {
                solDelta = solDelta.add(amount);
            }
            if (walletAddress.equals(from)) {
                solDelta = solDelta.subtract(amount);
            }
        }
        if (solDelta.abs().compareTo(NATIVE_SOL_DUST_THRESHOLD) >= 0) {
            net.merge(SolanaChain.WSOL_MINT, solDelta, BigDecimal::add);
        }
    }

    private static boolean isLendingValueMint(String mint) {
        return SolanaChain.WSOL_MINT.equals(mint) || NetworkStablecoinContracts.forNetwork(NetworkId.SOLANA).contains(mint);
    }

    /**
     * Deterministic Jupiter Lend loan correlation/order id: {@code solana:jupiter-lend:<debtMint>:<wallet>}.
     * The debt mint is the borrowed/repaid stablecoin leg (BUY on BORROW, SELL on REPAY). Returns
     * {@code null} when no stablecoin debt leg is present so the id is never fabricated.
     */
    private static String resolveBorrowLoanCorrelationId(NormalizedTransaction tx, String walletAddress) {
        if (walletAddress == null) {
            return null;
        }
        String debtMint = null;
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getAssetContract() == null) {
                continue;
            }
            if ((flow.getRole() == NormalizedLegRole.BUY || flow.getRole() == NormalizedLegRole.SELL)
                    && NetworkStablecoinContracts.forNetwork(NetworkId.SOLANA).contains(flow.getAssetContract())) {
                debtMint = flow.getAssetContract();
                break;
            }
        }
        if (debtMint == null) {
            return null;
        }
        return "solana:jupiter-lend:" + debtMint + ":" + walletAddress;
    }

    // --- VAULT ---

    private void buildVaultFlows(SolanaRawTransactionView view, String walletAddress, NormalizedTransactionType type, List<NormalizedTransaction.Flow> flows) {
        for (Document transfer : view.tokenTransfers()) {
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            boolean deposit = type == NormalizedTransactionType.VAULT_DEPOSIT;
            if (deposit && walletAddress.equals(from)) {
                NormalizedTransaction.Flow flow = tokenTransferToFlow(transfer, NormalizedLegRole.TRANSFER, true);
                if (flow != null) {
                    flows.add(flow);
                }
            } else if (!deposit && walletAddress.equals(to)) {
                NormalizedTransaction.Flow flow = tokenTransferToFlow(transfer, NormalizedLegRole.TRANSFER, false);
                if (flow != null) {
                    flows.add(flow);
                }
            }
        }
    }

    // --- STAKING ---

    private void buildStakingFlows(SolanaRawTransactionView view, String walletAddress, NormalizedTransactionType type, List<NormalizedTransaction.Flow> flows) {
        // Staking typically involves native SOL
        for (Document transfer : view.nativeTransfers()) {
            String from = transfer.getString("fromUserAccount");
            String to = transfer.getString("toUserAccount");
            Object amtObj = transfer.get("amount");
            if (amtObj == null) {
                continue;
            }
            long lamports = toLong(amtObj);
            boolean deposit = type == NormalizedTransactionType.STAKING_DEPOSIT;
            if (deposit && walletAddress.equals(from) && lamports > 0) {
                NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
                flow.setRole(NormalizedLegRole.TRANSFER);
                flow.setAssetContract(SolanaChain.WSOL_MINT);
                flow.setAssetSymbol(SOL_SYMBOL);
                flow.setQuantityDelta(BigDecimal.valueOf(-lamports).divide(LAMPORTS_PER_SOL));
                flow.setCounterpartyAddress(to);
                flows.add(flow);
            } else if (!deposit && walletAddress.equals(to) && lamports > 0) {
                NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
                flow.setRole(NormalizedLegRole.TRANSFER);
                flow.setAssetContract(SolanaChain.WSOL_MINT);
                flow.setAssetSymbol(SOL_SYMBOL);
                flow.setQuantityDelta(BigDecimal.valueOf(lamports).divide(LAMPORTS_PER_SOL));
                flow.setCounterpartyAddress(from);
                flows.add(flow);
            }
        }

        // Also check token transfers (liquid staking tokens)
        buildLpFlows(view, walletAddress, NormalizedTransactionType.LP_ENTRY, flows);
    }

    // --- GENERIC fallback ---

    private void buildGenericFlows(SolanaRawTransactionView view, String walletAddress, List<NormalizedTransaction.Flow> flows) {
        buildSwapFlowsFromTokenTransfers(view.tokenTransfers(), walletAddress, flows);
    }

    // --- helpers ---

    private static boolean isPositionScopedLpType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_ENTRY || type == NormalizedTransactionType.LP_EXIT;
    }

    /**
     * ADR-081 (C1): marks every wallet flow that moves the Meteora DAMM LP mint (the fungible MLP
     * receipt) as an LP receipt. Keyed on the deterministic {@code lpMint} account resolved from the
     * DAMM liquidity instruction — never on the confusable {@code MLP} symbol — so it is exact across
     * pools that share the symbol. No-op when this is not a DAMM liquidity tx ({@code lpMint == null}).
     */
    private static void markDammLpReceiptLeg(NormalizedTransaction tx, String lpMint) {
        if (lpMint == null || lpMint.isBlank()) {
            return;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow != null && lpMint.equals(flow.getAssetContract())) {
                flow.setLpReceipt(Boolean.TRUE);
            }
        }
    }

    /**
     * ADR-081 (C1): flags the SPL LP-receipt leg(s) of a Meteora-farm {@code LP_POSITION_STAKE}/
     * {@code LP_POSITION_UNSTAKE}. A Meteora farm only stakes LP tokens (reward harvests are
     * classified {@code REWARD_CLAIM}), so the sole non-native SPL {@code TRANSFER} leg of the move
     * is the LP receipt. Flagging it lets replay stamp {@code FAMILY:LP_RECEIPT} on the stake/unstake
     * bucket point regardless of the confusable MLP symbol. Deterministic across reruns (keyed on the
     * Meteora-farm program id + leg role/contract), never on a wallet or transaction hash.
     */
    private static void markFarmStakedReceiptLegs(SolanaRawTransactionView view, NormalizedTransaction tx) {
        if (!view.hasProgramId(SolanaProtocolPrograms.METEORA_FARM_ID) || tx.getFlows() == null) {
            return;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() != NormalizedLegRole.TRANSFER) {
                continue;
            }
            String contract = flow.getAssetContract();
            if (contract == null || contract.isBlank()) {
                continue;
            }
            flow.setLpReceipt(Boolean.TRUE);
        }
    }

    private NormalizedTransaction.Flow tokenTransferToFlow(Document transfer, NormalizedLegRole role, boolean negate) {
        if (transfer == null) {
            return null;
        }
        String mint = transfer.getString("mint");
        if (mint == null || mint.isBlank()) {
            return null;
        }
        Object amtObj = transfer.get("tokenAmount");
        if (amtObj == null) {
            return null;
        }
        BigDecimal amount = parseBigDecimal(amtObj);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetContract(mint.trim());

        // Extract symbol from Helius metadata if present; fall back to the seeded SPL registry
        // (USDC/USDT/wSOL) so stablecoins resolve their symbol-driven STABLE_USD family even when
        // Helius returns a null symbol. An unknown/unseeded mint keeps a null symbol (never fails).
        String payloadSymbol = transfer.getString("symbol");
        if (payloadSymbol == null || payloadSymbol.isBlank()) {
            payloadSymbol = transfer.getString("tokenSymbol");
        }
        String symbol = resolveSymbol(payloadSymbol, mint.trim());
        if (symbol != null) {
            flow.setAssetSymbol(symbol);
        }

        flow.setQuantityDelta(negate ? amount.negate() : amount);
        return flow;
    }

    private NormalizedTransaction.Flow nativeSolFlow(Document nativeEntry, NormalizedLegRole role, boolean negate) {
        if (nativeEntry == null) {
            return null;
        }
        Object amtObj = nativeEntry.get("amount");
        if (amtObj == null) {
            return null;
        }
        long lamports = toLong(amtObj);
        if (lamports <= 0) {
            return null;
        }

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetContract(SolanaChain.WSOL_MINT);
        flow.setAssetSymbol(SOL_SYMBOL);
        BigDecimal qty = BigDecimal.valueOf(lamports).divide(LAMPORTS_PER_SOL);
        flow.setQuantityDelta(negate ? qty.negate() : qty);
        return flow;
    }

    private static List<Document> docList(Document parent, String key) {
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

    private static long toLong(Object value) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static BigDecimal parseBigDecimal(Object value) {
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
}
