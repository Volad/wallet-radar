package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Flow-level counterparty helpers (ADR-010) and transaction-level derivation.
 */
public final class FlowCounterpartySupport {

    public static final String MULTI_COUNTERPARTY = "MULTI";
    public static final String MULTI_COUNTERPARTY_TYPE = "MULTI";

    private FlowCounterpartySupport() {
    }

    public static String onChainAccountRef(NetworkId networkId, String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return null;
        }
        String trimmed = walletAddress.trim();
        if (networkId == NetworkId.SOLANA) {
            return "solana:" + trimmed;
        }
        if (networkId == NetworkId.TON) {
            return "ton:" + trimmed;
        }
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return "evm:" + trimmed.toLowerCase(Locale.ROOT);
        }
        return "evm:" + trimmed;
    }

    /**
     * Derives the transaction-level counterparty from its flows.
     *
     * <p>ADR-032 / WS-3a: FEE legs (role = FEE) and synthetic placeholder counterparties
     * matching {@code UNKNOWN:*} are excluded from the distinct-address set before the size&gt;1
     * → MULTI check. MULTI must only reflect genuine multi-principal transactions (e.g. swaps
     * with multiple real recipients), not the presence of a gas-fee pseudoparty.</p>
     */
    public static void applyTransactionCounterparty(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return;
        }
        Set<String> addresses = new LinkedHashSet<>();
        Set<String> types = new LinkedHashSet<>();
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null) {
                continue;
            }
            // Exclude FEE legs — they carry a synthetic network-fee pseudoparty, not a real principal
            if (flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            String cp = flow.getCounterpartyAddress();
            // Exclude synthetic UNKNOWN:* placeholders (no real on-chain counterparty resolved)
            if (!present(cp) || cp.trim().toUpperCase(Locale.ROOT).startsWith("UNKNOWN:")) {
                continue;
            }
            addresses.add(cp.trim());
            if (present(flow.getCounterpartyType())) {
                types.add(flow.getCounterpartyType().trim());
            }
        }
        if (addresses.isEmpty()) {
            return;
        }
        if (addresses.size() == 1) {
            transaction.setCounterpartyAddress(addresses.iterator().next());
        } else {
            transaction.setCounterpartyAddress(MULTI_COUNTERPARTY);
        }
        if (types.size() == 1) {
            transaction.setCounterpartyType(types.iterator().next());
        } else if (types.size() > 1) {
            transaction.setCounterpartyType(MULTI_COUNTERPARTY_TYPE);
        }
    }

    public static void syncFlowsFromTransaction(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return;
        }
        String address = transaction.getCounterpartyAddress();
        String type = transaction.getCounterpartyType();
        if (!present(address) && !present(type)) {
            return;
        }
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null) {
                continue;
            }
            if (!present(flow.getCounterpartyAddress()) && present(address)
                    && !MULTI_COUNTERPARTY.equalsIgnoreCase(address)) {
                flow.setCounterpartyAddress(address);
            }
            if (!present(flow.getCounterpartyType()) && present(type)
                    && !MULTI_COUNTERPARTY_TYPE.equalsIgnoreCase(type)) {
                flow.setCounterpartyType(type);
            }
        }
    }

    public static void enrichOnChainFlows(
            NormalizedTransaction transaction,
            OnChainRawTransactionView view,
            BiFunction<String, NetworkId, String> counterpartyTypeResolver
    ) {
        if (transaction == null || view == null || transaction.getFlows() == null) {
            return;
        }
        String wallet = normalizeAddress(view.walletAddress());
        String accountRef = onChainAccountRef(view.networkId(), view.walletAddress());
        NetworkId networkId = view.networkId();
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null) {
                continue;
            }
            flow.setAccountRef(accountRef);
            if (flow.getRole() == NormalizedLegRole.FEE) {
                if (!present(flow.getCounterpartyAddress())) {
                    flow.setCounterpartyAddress("UNKNOWN:NETWORK_FEE");
                }
                if (!present(flow.getCounterpartyType())) {
                    flow.setCounterpartyType(CounterpartyType.GENUINE_MISSING_SOURCE);
                }
                continue;
            }
            String peer = resolvePeerForFlow(flow, view, wallet);
            if (!present(peer)) {
                peer = transaction.getCounterpartyAddress();
            }
            if (!present(peer)) {
                peer = "UNKNOWN:" + syntheticFlowKey(transaction, flow);
                appendMissingDataReason(transaction, "COUNTERPARTY_ADDRESS_INFERRED");
            }
            flow.setCounterpartyAddress(peer);
            if (!present(flow.getCounterpartyType()) && counterpartyTypeResolver != null) {
                flow.setCounterpartyType(counterpartyTypeResolver.apply(peer, networkId));
            }
        }
        applyTransactionCounterparty(transaction);
    }

    private static String resolvePeerForFlow(
            NormalizedTransaction.Flow flow,
            OnChainRawTransactionView view,
            String wallet
    ) {
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
            return null;
        }
        int flowSign = flow.getQuantityDelta().signum();
        String flowSymbol = flow.getAssetSymbol();
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() == 0) {
                continue;
            }
            if (!symbolMatches(flowSymbol, view.tokenTransferSymbol(transfer))) {
                continue;
            }
            boolean inbound = sameAddress(view.tokenTransferTo(transfer), wallet);
            boolean outbound = sameAddress(view.tokenTransferFrom(transfer), wallet);
            if (flowSign > 0 && inbound) {
                return normalizeAddress(view.tokenTransferFrom(transfer));
            }
            if (flowSign < 0 && outbound) {
                return normalizeAddress(view.tokenTransferTo(transfer));
            }
        }
        for (Document transfer : view.explorerInternalTransfers()) {
            BigDecimal quantity = view.internalTransferQuantity(transfer);
            if (quantity == null || quantity.signum() == 0) {
                continue;
            }
            String nativeSymbol = view.networkId() == null ? null : view.networkId().name();
            if (flowSymbol != null && nativeSymbol != null && !flowSymbol.equalsIgnoreCase(nativeSymbol)) {
                continue;
            }
            boolean inbound = sameAddress(view.internalTransferTo(transfer), wallet);
            boolean outbound = sameAddress(view.internalTransferFrom(transfer), wallet);
            if (flowSign > 0 && inbound) {
                return normalizeAddress(view.internalTransferFrom(transfer));
            }
            if (flowSign < 0 && outbound) {
                return normalizeAddress(view.internalTransferTo(transfer));
            }
        }
        if (flowSign > 0 && present(view.fromAddress()) && !sameAddress(view.fromAddress(), wallet)) {
            return normalizeAddress(view.fromAddress());
        }
        if (flowSign < 0 && present(view.toAddress()) && !sameAddress(view.toAddress(), wallet)) {
            return normalizeAddress(view.toAddress());
        }
        return null;
    }

    private static String syntheticFlowKey(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        return String.join(":",
                transaction.getId() == null ? "tx" : transaction.getId(),
                flow.getRole() == null ? "role" : flow.getRole().name(),
                flow.getAssetSymbol() == null ? "asset" : flow.getAssetSymbol(),
                flow.getLogIndex() == null ? "0" : flow.getLogIndex().toString()
        );
    }

    private static boolean symbolMatches(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private static boolean sameAddress(String left, String right) {
        return present(left) && present(right) && left.equalsIgnoreCase(right);
    }

    private static String normalizeAddress(String value) {
        if (!present(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static void appendMissingDataReason(NormalizedTransaction transaction, String reason) {
        if (transaction == null || !present(reason)) {
            return;
        }
        List<String> reasons = transaction.getMissingDataReasons();
        if (reasons == null || reasons.isEmpty()) {
            transaction.setMissingDataReasons(new ArrayList<>(List.of(reason)));
            return;
        }
        if (reasons.contains(reason)) {
            return;
        }
        List<String> mutable = new ArrayList<>(reasons);
        mutable.add(reason);
        transaction.setMissingDataReasons(mutable);
    }

    public static boolean flowsMissingCounterparty(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return true;
        }
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (!present(flow.getCounterpartyAddress())) {
                return true;
            }
        }
        return false;
    }
}
