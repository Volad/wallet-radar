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
    private static final BigDecimal MAX_WEI_DUST = new BigDecimal("0.000000000000000001");
    private static final BigDecimal MAX_GWEI_DUST = new BigDecimal("0.000000001");

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

        List<NormalizedTransaction> candidates = loadCandidates(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            if (isPoisoningDust(tx, trackedWallets, fullFingerprints, suffixes, prefixes,
                    knownScamEoas, scamSuffixes)) {
                markExcluded(tx, now);
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("ADDRESS_POISONING_DETECTOR excluded={}", dirty.size());
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

    private void markExcluded(NormalizedTransaction tx, Instant now) {
        tx.setExcludedFromAccounting(true);
        tx.setAccountingExclusionReason(REASON);
        List<String> reasons = tx.getMissingDataReasons();
        if (reasons == null) {
            reasons = new ArrayList<>();
            tx.setMissingDataReasons(reasons);
        }
        if (!reasons.contains(REASON)) {
            reasons.add(REASON);
        }
        tx.setUpdatedAt(now);
    }
}
