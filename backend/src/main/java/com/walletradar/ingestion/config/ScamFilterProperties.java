package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Configuration for phishing/scam transaction filter. Addresses in blocklist (contracts, EOAs)
 * cause transactions involving them to be skipped during ingestion.
 */
@ConfigurationProperties(prefix = "walletradar.scam-filter")
@Getter
@Setter
public class ScamFilterProperties {

    /**
     * When false, no filtering is applied. Default true.
     */
    private boolean enabled = true;

    /**
     * Blocklist of addresses (EVM: 0x..., Solana: base58). Case-insensitive.
     * Add known scam contracts, drainers, phishing addresses.
     */
    private List<String> blocklist = List.of();

    /**
     * Final score threshold to drop a transaction.
     */
    private int dropThreshold = 100;

    /**
     * Score added when tx contains blocklisted address.
     */
    private int blocklistScore = 1000;

    /**
     * Score added for unsolicited inbound tx (sender != wallet, wallet only receives).
     */
    private int unsolicitedInboundScore = 35;

    /**
     * Score added for mass single-token unsolicited airdrop pattern.
     */
    private int massAirdropScore = 90;

    /**
     * Score added for relay sweep spam pattern.
     */
    private int relaySweepScore = 100;

    /**
     * Score added for inbound fanout spam pattern.
     */
    private int inboundFanoutScore = 85;

    /**
     * Score added for zero-amount poisoning pattern.
     */
    private int zeroAmountPoisoningScore = 95;

    /**
     * Score added when token symbol/name contains phishing URL or claim/visit bait text.
     */
    private int suspiciousAirdropTokenTextScore = 80;

    /**
     * Score added when unsolicited inbound token transfer has suspicious tiny integer amount (e.g. 1,5,10).
     */
    private int suspiciousAirdropTinyValueScore = 20;

    /**
     * Score added when unsolicited inbound token has zero decimals and suspicious display characters.
     */
    private int suspiciousAirdropZeroDecimalsScore = 15;

    /**
     * Score added when function name indicates airdrop distribution.
     */
    private int suspiciousAirdropFunctionScore = 20;

    /**
     * Score added when unsolicited inbound transfer comes from a multicall tx
     * with many ERC20 transfer subcalls (mass airdrop-like pattern).
     */
    private int suspiciousMulticallAirdropScore = 100;

    /**
     * Minimum number of ERC20 transfer selectors in multicall input
     * to treat transaction as suspicious mass airdrop.
     */
    private int suspiciousMulticallAirdropMinTransferCalls = 20;

    /**
     * Score credit for wallet-initiated tx.
     */
    private int walletInitiatedCredit = 60;

    /**
     * Score credit when wallet has both inbound and outbound transfer legs in same tx.
     */
    private int mixedWalletFlowCredit = 30;

    /**
     * Score credit when wallet-initiated tx also includes approvals (common swap/deposit pattern).
     */
    private int walletApprovalCredit = 20;

    /**
     * Minimum transfer logs for mass unsolicited airdrop detection.
     */
    private int massAirdropMinTransferLogs = 20;

    /**
     * Minimum transfer logs for relay/fanout spam heuristics.
     */
    private int massRelaySpamMinTransferLogs = 30;

    /**
     * Minimum unique recipients for inbound fanout spam.
     */
    private int massInboundFanoutMinRecipients = 40;

    /**
     * Minimum transfer logs for zero-amount poisoning.
     */
    private int zeroAmountPoisoningMinTransferLogs = 100;

    /**
     * Minimum zero-amount transfers for zero-amount poisoning.
     */
    private int zeroAmountPoisoningMinZeroTransfers = 20;

    /**
     * Deterministic high-confidence inbound spam fingerprints captured from production data.
     * Used as an upstream hard-drop before normalization/pricing.
     */
    private List<InboundSpamFingerprint> knownInboundSpamFingerprints = List.of();

    /**
     * Normalized blocklist (lowercase) for fast lookup. Built from blocklist.
     */
    public Set<String> getBlocklistNormalized() {
        if (blocklist == null || blocklist.isEmpty()) {
            return Set.of();
        }
        return blocklist.stream()
                .filter(a -> a != null && !a.isBlank())
                .map(a -> a.strip().toLowerCase())
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * Normalized known inbound spam fingerprints for fast lookup.
     * Key format: NETWORK|tokenContract|methodId
     */
    public Set<String> getKnownInboundSpamFingerprintKeysNormalized() {
        if (knownInboundSpamFingerprints == null || knownInboundSpamFingerprints.isEmpty()) {
            return Set.of();
        }
        return knownInboundSpamFingerprints.stream()
                .map(this::toNormalizedInboundSpamFingerprintKey)
                .filter(key -> key != null && !key.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
    }

    private String toNormalizedInboundSpamFingerprintKey(InboundSpamFingerprint fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        String networkId = normalizeNetworkId(fingerprint.getNetworkId());
        String tokenContract = normalizeAddress(fingerprint.getTokenContract());
        String methodId = normalizeMethodId(fingerprint.getMethodId());
        if (networkId == null || tokenContract == null || methodId == null) {
            return null;
        }
        return networkId + "|" + tokenContract + "|" + methodId;
    }

    private static String normalizeNetworkId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().toUpperCase(Locale.ROOT);
    }

    private static String normalizeAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizeMethodId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return normalized.startsWith("0x") ? normalized : "0x" + normalized;
    }

    @Getter
    @Setter
    public static class InboundSpamFingerprint {
        private String networkId;
        private String tokenContract;
        private String methodId;
    }
}
