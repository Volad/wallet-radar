package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.support.GmxV2HandlerRegistry;
import com.walletradar.ingestion.pipeline.classification.support.KnownBridgeRouterRegistry;
import com.walletradar.ingestion.pipeline.classification.support.SpoofTokenQuarantineSupport;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detects address-poisoning dust IN transactions using multi-strategy matching:
 *
 * <ul>
 *   <li>Full fingerprint (prefix+suffix) match at up to 1 gwei</li>
 *   <li>Suffix-only or prefix-only partial match at up to 1 wei</li>
 *   <li>Known scam-EOA reverse-dust (cp equals an address seen in SUSPECTED_PHISHING_OUT)</li>
 *   <li>Scammer-fingerprint suffix match at up to 1 wei</li>
 *   <li>Generic 1-wei dust from non-protocol EOAs</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AddressPoisoningDetector {

    private static final String REASON = "ADDRESS_POISONING_DUST";
    private static final String REASON_MIRRORED_OUT = "ADDRESS_POISONING_MIRRORED_OUT";
    private static final BigDecimal MAX_WEI_DUST = new BigDecimal("0.000000000000000001");
    private static final BigDecimal MAX_GWEI_DUST = new BigDecimal("0.000000001");
    private static final List<NormalizedTransactionType> REAL_COUNTERPARTY_TRANSFER_TYPES = List.of(
            NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
            NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
            NormalizedTransactionType.INTERNAL_TRANSFER,
            NormalizedTransactionType.BRIDGE_OUT,
            NormalizedTransactionType.BRIDGE_IN
    );

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AccountingUniverseRepository accountingUniverseRepository;

    public int detectAndExclude(int batchSize) {
        Set<String> trackedWallets = loadTrackedWalletAddresses();
        if (trackedWallets.isEmpty()) {
            return 0;
        }
        Set<String> fullFingerprints = trackedWallets.stream()
                .map(AddressPoisoningDetector::fingerprint)
                .collect(Collectors.toSet());
        Set<String> suffixes = trackedWallets.stream()
                .map(AddressPoisoningDetector::suffix)
                .collect(Collectors.toSet());
        Set<String> prefixes = trackedWallets.stream()
                .map(AddressPoisoningDetector::prefix)
                .collect(Collectors.toSet());
        Set<String> knownScamEoas = loadKnownScamCounterparties();
        Set<String> scamSuffixes = knownScamEoas.stream()
                .map(AddressPoisoningDetector::suffix)
                .filter(s -> s != null && !s.isEmpty())
                .collect(Collectors.toSet());

        int excluded = detectAndExcludeInboundDust(batchSize, trackedWallets, fullFingerprints,
                suffixes, prefixes, knownScamEoas, scamSuffixes);
        excluded += detectAndExcludeMirroredOutbound(batchSize, trackedWallets);
        return excluded;
    }

    private int detectAndExcludeInboundDust(
            int batchSize,
            Set<String> trackedWallets,
            Set<String> fullFingerprints,
            Set<String> suffixes,
            Set<String> prefixes,
            Set<String> knownScamEoas,
            Set<String> scamSuffixes
    ) {
        List<NormalizedTransaction> candidates = loadCandidates(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            if (isPoisoningDust(tx, trackedWallets, fullFingerprints, suffixes, prefixes,
                    knownScamEoas, scamSuffixes)) {
                markExcluded(tx, now, REASON);
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("ADDRESS_POISONING_DETECTOR excluded={}", dirty.size());
        }
        return dirty.size();
    }

    /**
     * SF-2: defense-in-depth net for the outbound poisoning variant. A fake-contract
     * {@code EXTERNAL_TRANSFER_OUT} on a non-canonical, unpriceable token whose counterparty is a
     * vanity match (prefix AND suffix, via {@link #fingerprint}) of a real recent counterparty the
     * wallet actually transacted with — but is not that real address — is an address-poisoning
     * look-alike and is quarantined. The vanity-fingerprint match is mandatory (conservative) so a
     * legitimate outbound transfer to a real counterparty is never excluded.
     */
    private int detectAndExcludeMirroredOutbound(int batchSize, Set<String> trackedWallets) {
        List<NormalizedTransaction> candidates = loadOutboundCandidates(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }
        RealCounterpartyIndex realCounterparties = loadRealCounterparties();
        if (realCounterparties.fingerprints().isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            if (isMirroredOutboundSpoof(tx, trackedWallets, realCounterparties.addresses(),
                    realCounterparties.fingerprints())) {
                markExcluded(tx, now, REASON_MIRRORED_OUT);
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("ADDRESS_POISONING_MIRRORED_OUT excluded={}", dirty.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadCandidates(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                new Criteria().orOperator(
                        Criteria.where("excludedFromAccounting").exists(false),
                        Criteria.where("excludedFromAccounting").is(false)
                ),
                Criteria.where("missingDataReasons").nin(REASON)
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    boolean isPoisoningDust(
            NormalizedTransaction tx,
            Set<String> trackedWallets,
            Set<String> fullFingerprints,
            Set<String> suffixes,
            Set<String> prefixes,
            Set<String> knownScamEoas,
            Set<String> scamSuffixes
    ) {
        if (tx == null || tx.getFlows() == null || tx.getFlows().isEmpty()) {
            return false;
        }
        List<NormalizedTransaction.Flow> principalFlows = tx.getFlows().stream()
                .filter(f -> f != null && f.getRole() != NormalizedLegRole.FEE)
                .filter(f -> f.getQuantityDelta() != null && f.getQuantityDelta().signum() > 0)
                .toList();
        if (principalFlows.isEmpty()) {
            return false;
        }
        BigDecimal maxQty = principalFlows.stream()
                .map(NormalizedTransaction.Flow::getQuantityDelta)
                .reduce(BigDecimal.ZERO, BigDecimal::max);

        for (NormalizedTransaction.Flow flow : principalFlows) {
            String cp = resolveCounterparty(flow, tx);
            if (cp == null) {
                continue;
            }
            String cpLower = cp.toLowerCase(Locale.ROOT);
            if (!cpLower.startsWith("0x")) {
                continue;
            }
            if (trackedWallets.contains(cpLower)) {
                continue;
            }
            if (isKnownProtocol(cpLower)) {
                continue;
            }

            // F2c: exact scam-EOA reverse-dust (any amount at 1 wei threshold)
            if (knownScamEoas.contains(cpLower) && maxQty.compareTo(MAX_WEI_DUST) <= 0) {
                return true;
            }

            String cpFp = fingerprint(cpLower);
            String cpSuffix = suffix(cpLower);
            String cpPrefix = prefix(cpLower);

            // F2b: full fingerprint match at up to 1 gwei
            if (cpFp != null && fullFingerprints.contains(cpFp) && maxQty.compareTo(MAX_GWEI_DUST) <= 0) {
                return true;
            }

            if (maxQty.compareTo(MAX_WEI_DUST) <= 0) {
                // F2a: suffix-only match at 1 wei
                if (cpSuffix != null && suffixes.contains(cpSuffix)) {
                    return true;
                }
                // Prefix-only match at 1 wei
                if (cpPrefix != null && prefixes.contains(cpPrefix)) {
                    return true;
                }
                // Scammer-suffix match at 1 wei
                if (cpSuffix != null && scamSuffixes.contains(cpSuffix)) {
                    return true;
                }
                // F2d: generic 1-wei dust from any non-protocol EOA
                return true;
            }
        }
        return false;
    }

    /** Backward-compatible 2-arg overload used by existing tests. */
    boolean isPoisoningDust(
            NormalizedTransaction tx,
            Set<String> trackedWallets,
            Set<String> fullFingerprints
    ) {
        Set<String> suffixes = trackedWallets.stream()
                .map(AddressPoisoningDetector::suffix)
                .collect(Collectors.toSet());
        Set<String> prefixes = trackedWallets.stream()
                .map(AddressPoisoningDetector::prefix)
                .collect(Collectors.toSet());
        return isPoisoningDust(tx, trackedWallets, fullFingerprints, suffixes, prefixes,
                Set.of(), Set.of());
    }

    private Set<String> loadTrackedWalletAddresses() {
        return accountingUniverseRepository.findAll().stream()
                .flatMap(u -> u.getMembers().stream())
                .filter(m -> m.getType() == AccountingUniverse.MemberType.ON_CHAIN_WALLET)
                .map(m -> m.getRef() == null ? "" : m.getRef().toLowerCase(Locale.ROOT))
                .filter(r -> r.startsWith("0x"))
                .collect(Collectors.toSet());
    }

    Set<String> loadKnownScamCounterparties() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT),
                Criteria.where("missingDataReasons").is("SUSPECTED_PHISHING_OUT")
        ));
        List<NormalizedTransaction> scamTxns = mongoOperations.find(query, NormalizedTransaction.class);
        Set<String> scamCps = new HashSet<>();
        for (NormalizedTransaction tx : scamTxns) {
            addCp(scamCps, tx.getCounterpartyAddress());
            if (tx.getFlows() != null) {
                for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                    if (flow != null && flow.getRole() != NormalizedLegRole.FEE) {
                        addCp(scamCps, flow.getCounterpartyAddress());
                    }
                }
            }
        }
        return scamCps;
    }

    private static void addCp(Set<String> set, String address) {
        if (address != null && !address.isBlank() && address.startsWith("0x")) {
            set.add(address.toLowerCase(Locale.ROOT));
        }
    }

    private static boolean isKnownProtocol(String address) {
        return KnownBridgeRouterRegistry.isKnownBridgeRouter(address)
                || KnownBridgeRouterRegistry.isKnownRewardDistributor(address)
                || GmxV2HandlerRegistry.isKnownGmxV2Handler(address);
    }

    private static String resolveCounterparty(NormalizedTransaction.Flow flow, NormalizedTransaction tx) {
        String cp = flow.getCounterpartyAddress();
        if (cp == null || cp.isBlank()) {
            cp = tx.getCounterpartyAddress();
        }
        return (cp == null || cp.isBlank()) ? null : cp;
    }

    static String fingerprint(String address) {
        if (address == null || address.length() < 10 || !address.startsWith("0x")) {
            return null;
        }
        String hex = address.toLowerCase(Locale.ROOT);
        return hex.substring(2, 6) + ":" + hex.substring(hex.length() - 4);
    }

    static String suffix(String address) {
        if (address == null || address.length() < 6 || !address.startsWith("0x")) {
            return null;
        }
        return address.toLowerCase(Locale.ROOT).substring(address.length() - 4);
    }

    static String prefix(String address) {
        if (address == null || address.length() < 6 || !address.startsWith("0x")) {
            return null;
        }
        return address.toLowerCase(Locale.ROOT).substring(2, 6);
    }

    private void markExcluded(NormalizedTransaction tx, Instant now, String reason) {
        tx.setExcludedFromAccounting(true);
        tx.setAccountingExclusionReason(reason);
        List<String> reasons = tx.getMissingDataReasons();
        if (reasons == null) {
            reasons = new ArrayList<>();
            tx.setMissingDataReasons(reasons);
        }
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
        tx.setUpdatedAt(now);
    }

    private List<NormalizedTransaction> loadOutboundCandidates(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT),
                new Criteria().orOperator(
                        Criteria.where("excludedFromAccounting").exists(false),
                        Criteria.where("excludedFromAccounting").is(false)
                ),
                Criteria.where("missingDataReasons").nin(REASON_MIRRORED_OUT)
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    /**
     * Indexes counterparties seen on legitimate (non-spoof, canonical/priced) transfers so an
     * outbound look-alike can be distinguished from the genuine address it imitates.
     */
    RealCounterpartyIndex loadRealCounterparties() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").in(REAL_COUNTERPARTY_TRANSFER_TYPES),
                new Criteria().orOperator(
                        Criteria.where("excludedFromAccounting").exists(false),
                        Criteria.where("excludedFromAccounting").is(false)
                )
        ));
        List<NormalizedTransaction> txns = mongoOperations.find(query, NormalizedTransaction.class);
        Set<String> addresses = new HashSet<>();
        for (NormalizedTransaction tx : txns) {
            if (!hasLegitimateAsset(tx)) {
                continue;
            }
            addCp(addresses, tx.getCounterpartyAddress());
            if (tx.getFlows() != null) {
                for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                    if (flow != null && flow.getRole() != NormalizedLegRole.FEE) {
                        addCp(addresses, flow.getCounterpartyAddress());
                    }
                }
            }
        }
        Set<String> fingerprints = addresses.stream()
                .map(AddressPoisoningDetector::fingerprint)
                .filter(fp -> fp != null && !fp.isEmpty())
                .collect(Collectors.toSet());
        return new RealCounterpartyIndex(addresses, fingerprints);
    }

    boolean isMirroredOutboundSpoof(
            NormalizedTransaction tx,
            Set<String> trackedWallets,
            Set<String> realCounterparties,
            Set<String> realFingerprints
    ) {
        if (tx == null || tx.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_OUT) {
            return false;
        }
        if (tx.getFlows() == null || tx.getFlows().isEmpty()) {
            return false;
        }
        boolean hasUnpriceableNonCanonicalLeg = tx.getFlows().stream()
                .filter(f -> f != null && f.getRole() != NormalizedLegRole.FEE)
                .anyMatch(this::isUnpriceableNonCanonicalAsset);
        if (!hasUnpriceableNonCanonicalLeg) {
            return false;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            String cp = resolveCounterparty(flow, tx);
            if (cp == null) {
                continue;
            }
            String cpLower = cp.toLowerCase(Locale.ROOT);
            if (!cpLower.startsWith("0x") || trackedWallets.contains(cpLower) || isKnownProtocol(cpLower)) {
                continue;
            }
            // A real address transacted with is never a poison look-alike of itself.
            if (realCounterparties.contains(cpLower)) {
                continue;
            }
            String cpFp = fingerprint(cpLower);
            if (cpFp != null && realFingerprints.contains(cpFp)) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnpriceableNonCanonicalAsset(NormalizedTransaction.Flow flow) {
        if (flow == null) {
            return false;
        }
        return flow.getUnitPriceUsd() == null;
    }

    private boolean hasLegitimateAsset(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null) {
            return false;
        }
        return tx.getFlows().stream()
                .filter(f -> f != null && f.getRole() != NormalizedLegRole.FEE)
                .anyMatch(f -> isLegitimateAsset(tx.getNetworkId(), f));
    }

    private boolean isLegitimateAsset(com.walletradar.domain.common.NetworkId networkId, NormalizedTransaction.Flow flow) {
        if (SpoofTokenQuarantineSupport.isConfusableSpoofAsset(networkId, flow.getAssetContract(), flow.getAssetSymbol())) {
            return false;
        }
        return flow.getUnitPriceUsd() != null
                || CanonicalAssetCatalog.isKnownCanonicalContract(networkId, flow.getAssetContract());
    }

    record RealCounterpartyIndex(Set<String> addresses, Set<String> fingerprints) {
    }
}
