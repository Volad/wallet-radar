package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.application.pipeline.pipeline.support.BsonCoercionSupport;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves protocolName for already-normalized on-chain rows from persisted raw/clarification evidence
 * without changing transaction type or flows.
 */
@Service
@RequiredArgsConstructor
public class ProtocolNameResolutionService {

    private final ProtocolRegistryService protocolRegistryService;

    public Optional<ResolvedProtocolName> resolve(NormalizedTransaction normalizedTransaction, RawTransaction rawTransaction) {
        if (normalizedTransaction == null || rawTransaction == null) {
            return Optional.empty();
        }

        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        NetworkId networkId = view.networkId();
        if (networkId == null) {
            return Optional.empty();
        }

        Optional<ResolvedProtocolName> directToAddress = resolveDirect(view.interactionToAddress(), networkId);
        if (directToAddress.isPresent()) {
            return directToAddress;
        }

        Optional<ResolvedProtocolName> contractAddress = resolveDirect(view.contractAddress(), networkId);
        if (contractAddress.isPresent() && isRoleRelevant(normalizedTransaction.getType(), contractAddress.get().role())) {
            return contractAddress;
        }

        Optional<ResolvedProtocolName> fromAddress = resolveDirect(view.fromAddress(), networkId);
        if (fromAddress.isPresent()
                && isSourcePreferredForType(normalizedTransaction.getType(), MatchSource.FROM_ADDRESS)
                && isRoleRelevant(normalizedTransaction.getType(), fromAddress.get().role())) {
            return fromAddress;
        }

        Optional<ResolvedProtocolName> providerBackedStatus = resolveProviderBackedStatus(normalizedTransaction, rawTransaction);
        if (providerBackedStatus.isPresent()) {
            return providerBackedStatus;
        }

        List<ResolvedCandidate> candidates = collectCandidates(view, normalizedTransaction.getType());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        Map<String, ProtocolAggregate> byProtocol = new LinkedHashMap<>();
        for (ResolvedCandidate candidate : candidates) {
            if (!present(candidate.protocolName())) {
                continue;
            }
            ProtocolAggregate aggregate = byProtocol.computeIfAbsent(
                    canonicalKey(candidate.protocolName(), candidate.protocolVersion()),
                    ignored -> new ProtocolAggregate(
                            candidate.protocolName(),
                            candidate.protocolVersion(),
                            candidate.role(),
                            candidate.score()
                    )
            );
            aggregate.include(candidate);
        }

        if (byProtocol.isEmpty()) {
            return Optional.empty();
        }
        if (byProtocol.size() == 1) {
            ProtocolAggregate only = byProtocol.values().iterator().next();
            return Optional.of(new ResolvedProtocolName(only.protocolName, only.protocolVersion, only.role));
        }

        List<ProtocolAggregate> ranked = byProtocol.values().stream()
                .sorted((left, right) -> Integer.compare(right.bestScore, left.bestScore))
                .toList();
        ProtocolAggregate best = ranked.getFirst();
        ProtocolAggregate second = ranked.size() > 1 ? ranked.get(1) : null;
        if (second != null && best.bestScore == second.bestScore) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProtocolName(best.protocolName, best.protocolVersion, best.role));
    }

    private List<ResolvedCandidate> collectCandidates(OnChainRawTransactionView view, NormalizedTransactionType type) {
        Set<String> dedupe = new LinkedHashSet<>();
        List<ResolvedCandidate> matches = new ArrayList<>();

        addCandidate(matches, dedupe, view.networkId(), view.contractAddress(), MatchSource.CONTRACT_ADDRESS, type);
        addCandidate(matches, dedupe, view.networkId(), view.fromAddress(), MatchSource.FROM_ADDRESS, type);
        for (Document log : view.persistedLogs()) {
            addCandidate(matches, dedupe, view.networkId(), normalizeAddress(log == null ? null : log.get("address")), MatchSource.LOG_ADDRESS, type);
        }
        return matches;
    }

    private Optional<ResolvedProtocolName> resolveDirect(String address, NetworkId networkId) {
        if (!present(address) || networkId == null) {
            return Optional.empty();
        }
        return protocolRegistryService.lookup(networkId, address)
                .filter(entry -> present(entry.protocolName()))
                .map(entry -> new ResolvedProtocolName(entry.protocolName(), entry.protocolVersion(), entry.role()));
    }

    private void addCandidate(
            List<ResolvedCandidate> candidates,
            Set<String> dedupe,
            NetworkId networkId,
            String address,
            MatchSource source,
            NormalizedTransactionType type
    ) {
        if (!present(address) || networkId == null || !isSourcePreferredForType(type, source)) {
            return;
        }
        ProtocolRegistryEntry entry = protocolRegistryService.lookup(networkId, address).orElse(null);
        if (entry == null || !present(entry.protocolName()) || !isRoleRelevant(type, entry.role())) {
            return;
        }
        int score = sourceWeight(source) + roleWeight(type, entry.role());
        String key = source.name() + "|" + entry.protocolName() + "|" + Objects.toString(entry.protocolVersion(), "") + "|" + address;
        if (!dedupe.add(key)) {
            return;
        }
        candidates.add(new ResolvedCandidate(
                entry.protocolName(),
                entry.protocolVersion(),
                entry.role(),
                score
        ));
    }

    private boolean isSourcePreferredForType(NormalizedTransactionType type, MatchSource source) {
        if (source == MatchSource.CONTRACT_ADDRESS || source == MatchSource.LOG_ADDRESS) {
            return true;
        }
        if (source == MatchSource.FROM_ADDRESS) {
            return type == NormalizedTransactionType.BRIDGE_IN
                    || type == NormalizedTransactionType.SPONSORED_GAS_IN
                    || type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                    || type == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
        }
        return false;
    }

    private boolean isRoleRelevant(NormalizedTransactionType type, ProtocolRegistryRole role) {
        if (role == null) {
            return false;
        }
        if (type == null) {
            return false;
        }
        return switch (type) {
            case SWAP -> role == ProtocolRegistryRole.ROUTER
                    || role == ProtocolRegistryRole.EXCHANGE_ROUTER
                    || role == ProtocolRegistryRole.POOL
                    || role == ProtocolRegistryRole.POSITION_MANAGER
                    || role == ProtocolRegistryRole.ORDER_BOOK
                    || role == ProtocolRegistryRole.ORDER_VAULT;
            case BRIDGE_OUT -> role == ProtocolRegistryRole.BRIDGE_ENTRY
                    || role == ProtocolRegistryRole.ROUTER
                    || role == ProtocolRegistryRole.EXCHANGE_ROUTER;
            case BRIDGE_IN -> role == ProtocolRegistryRole.BRIDGE_EXIT
                    || role == ProtocolRegistryRole.BRIDGE_ENTRY
                    || role == ProtocolRegistryRole.ROUTER;
            case WRAP, UNWRAP -> role == ProtocolRegistryRole.WRAPPER_TOKEN
                    || role == ProtocolRegistryRole.WRAPPER_CONTRACT;
            case LENDING_DEPOSIT, LENDING_WITHDRAW, BORROW, REPAY -> role == ProtocolRegistryRole.POOL
                    || role == ProtocolRegistryRole.VAULT
                    || role == ProtocolRegistryRole.WRAPPER_CONTRACT
                    || role == ProtocolRegistryRole.WRAPPER_TOKEN;
            case VAULT_DEPOSIT, VAULT_WITHDRAW -> role == ProtocolRegistryRole.VAULT
                    || role == ProtocolRegistryRole.FACTORY
                    || role == ProtocolRegistryRole.WRAPPER_TOKEN
                    || role == ProtocolRegistryRole.WRAPPER_CONTRACT;
            case LP_ENTRY, LP_EXIT, LP_ENTRY_REQUEST, LP_EXIT_REQUEST, LP_ENTRY_SETTLEMENT, LP_EXIT_SETTLEMENT,
                    LP_EXIT_PARTIAL, LP_EXIT_FINAL, LP_FEE_CLAIM, LP_POSITION_STAKE, LP_POSITION_UNSTAKE -> role == ProtocolRegistryRole.POOL
                    || role == ProtocolRegistryRole.POSITION_MANAGER
                    || role == ProtocolRegistryRole.FACTORY
                    || role == ProtocolRegistryRole.STAKE_CONTRACT
                    || role == ProtocolRegistryRole.REWARD_ROUTER;
            case STAKING_DEPOSIT, STAKING_WITHDRAW, STAKING_WITHDRAW_REQUEST -> role == ProtocolRegistryRole.STAKE_CONTRACT
                    || role == ProtocolRegistryRole.VAULT
                    || role == ProtocolRegistryRole.WRAPPER_TOKEN
                    || role == ProtocolRegistryRole.WRAPPER_CONTRACT;
            case REWARD_CLAIM -> role == ProtocolRegistryRole.REWARD_ROUTER
                    || role == ProtocolRegistryRole.STAKE_CONTRACT
                    || role == ProtocolRegistryRole.VAULT
                    || role == ProtocolRegistryRole.POOL;
            case APPROVE -> role == ProtocolRegistryRole.ROUTER
                    || role == ProtocolRegistryRole.EXCHANGE_ROUTER
                    || role == ProtocolRegistryRole.POOL
                    || role == ProtocolRegistryRole.POSITION_MANAGER
                    || role == ProtocolRegistryRole.VAULT
                    || role == ProtocolRegistryRole.STAKE_CONTRACT
                    || role == ProtocolRegistryRole.BRIDGE_ENTRY;
            case EXTERNAL_TRANSFER_IN, EXTERNAL_TRANSFER_OUT -> role == ProtocolRegistryRole.BRIDGE_ENTRY
                    || role == ProtocolRegistryRole.BRIDGE_EXIT
                    || role == ProtocolRegistryRole.VAULT
                    || role == ProtocolRegistryRole.WRAPPER_CONTRACT
                    || role == ProtocolRegistryRole.REWARD_ROUTER;
            case SPONSORED_GAS_IN -> role == ProtocolRegistryRole.GAS_PAYER;
            default -> false;
        };
    }

    private int roleWeight(NormalizedTransactionType type, ProtocolRegistryRole role) {
        if (type == null || role == null) {
            return 0;
        }
        return switch (type) {
            case SWAP -> switch (role) {
                case ROUTER -> 60;
                case EXCHANGE_ROUTER -> 58;
                case POOL -> 54;
                case POSITION_MANAGER -> 50;
                case ORDER_BOOK, ORDER_VAULT -> 46;
                default -> 0;
            };
            case BRIDGE_OUT, BRIDGE_IN -> switch (role) {
                case BRIDGE_ENTRY, BRIDGE_EXIT -> 60;
                case ROUTER, EXCHANGE_ROUTER -> 48;
                default -> 0;
            };
            case WRAP, UNWRAP -> switch (role) {
                case WRAPPER_TOKEN, WRAPPER_CONTRACT -> 60;
                default -> 0;
            };
            case LENDING_DEPOSIT, LENDING_WITHDRAW, BORROW, REPAY -> switch (role) {
                case POOL -> 60;
                case WRAPPER_CONTRACT -> 56;
                case VAULT -> 52;
                case WRAPPER_TOKEN -> 46;
                default -> 0;
            };
            case VAULT_DEPOSIT, VAULT_WITHDRAW -> switch (role) {
                case VAULT -> 60;
                case FACTORY -> 50;
                case WRAPPER_TOKEN, WRAPPER_CONTRACT -> 44;
                default -> 0;
            };
            case LP_ENTRY, LP_EXIT, LP_ENTRY_REQUEST, LP_EXIT_REQUEST, LP_ENTRY_SETTLEMENT, LP_EXIT_SETTLEMENT,
                    LP_EXIT_PARTIAL, LP_EXIT_FINAL, LP_FEE_CLAIM, LP_POSITION_STAKE, LP_POSITION_UNSTAKE -> switch (role) {
                case POOL -> 60;
                case POSITION_MANAGER -> 56;
                case FACTORY -> 48;
                case STAKE_CONTRACT -> 44;
                case REWARD_ROUTER -> 40;
                default -> 0;
            };
            case STAKING_DEPOSIT, STAKING_WITHDRAW, STAKING_WITHDRAW_REQUEST -> switch (role) {
                case STAKE_CONTRACT -> 60;
                case VAULT -> 48;
                case WRAPPER_TOKEN, WRAPPER_CONTRACT -> 42;
                default -> 0;
            };
            case REWARD_CLAIM -> switch (role) {
                case REWARD_ROUTER -> 60;
                case STAKE_CONTRACT -> 56;
                case VAULT -> 46;
                case POOL -> 42;
                default -> 0;
            };
            case APPROVE -> switch (role) {
                case ROUTER, EXCHANGE_ROUTER -> 45;
                case POSITION_MANAGER, POOL, VAULT, STAKE_CONTRACT -> 40;
                case BRIDGE_ENTRY -> 38;
                default -> 0;
            };
            case EXTERNAL_TRANSFER_IN, EXTERNAL_TRANSFER_OUT -> switch (role) {
                case BRIDGE_ENTRY, BRIDGE_EXIT -> 48;
                case VAULT, WRAPPER_CONTRACT -> 40;
                case REWARD_ROUTER -> 32;
                default -> 0;
            };
            case SPONSORED_GAS_IN -> role == ProtocolRegistryRole.GAS_PAYER ? 60 : 0;
            default -> 0;
        };
    }

    private int sourceWeight(MatchSource source) {
        return switch (source) {
            case CONTRACT_ADDRESS -> 92;
            case LOG_ADDRESS -> 72;
            case FROM_ADDRESS -> 20;
        };
    }

    private Optional<ResolvedProtocolName> resolveProviderBackedStatus(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction
    ) {
        if (normalizedTransaction.getType() != NormalizedTransactionType.BRIDGE_OUT
                && normalizedTransaction.getType() != NormalizedTransactionType.BRIDGE_IN) {
            return Optional.empty();
        }

        Document clarificationEvidence = rawTransaction.getClarificationEvidence();
        if (clarificationEvidence == null || clarificationEvidence.isEmpty()) {
            clarificationEvidence = BsonCoercionSupport.asDocument(rawTransaction.getRawData() == null
                    ? null
                    : rawTransaction.getRawData().get("clarificationEvidence"));
        }
        Document protocolStatus = BsonCoercionSupport.asDocument(clarificationEvidence == null
                ? null
                : clarificationEvidence.get("protocolStatus"));
        if (protocolStatus == null || protocolStatus.isEmpty()) {
            return Optional.empty();
        }

        String provider = protocolStatus.getString("provider");
        if (!present(provider)) {
            return Optional.empty();
        }
        return switch (provider.trim().toUpperCase(Locale.ROOT)) {
            case "LIFI" -> Optional.of(new ResolvedProtocolName("LiFi", null, ProtocolRegistryRole.ROUTER));
            case "MAYAN" -> Optional.of(new ResolvedProtocolName("Mayan", null, ProtocolRegistryRole.BRIDGE_ENTRY));
            default -> Optional.empty();
        };
    }

    private String canonicalKey(String protocolName, String protocolVersion) {
        String normalizedName = protocolName == null ? "" : protocolName.trim().toLowerCase(Locale.ROOT);
        String normalizedVersion = protocolVersion == null ? "" : protocolVersion.trim().toLowerCase(Locale.ROOT);
        return normalizedName + "|" + normalizedVersion;
    }

    private String normalizeAddress(Object value) {
        return OnChainRawTransactionView.normalizeAddress(value == null ? null : String.valueOf(value));
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public record ResolvedProtocolName(
            String protocolName,
            String protocolVersion,
            ProtocolRegistryRole role
    ) {
    }

    private enum MatchSource {
        CONTRACT_ADDRESS,
        LOG_ADDRESS,
        FROM_ADDRESS
    }

    private record ResolvedCandidate(
            String protocolName,
            String protocolVersion,
            ProtocolRegistryRole role,
            int score
    ) {
    }

    private static final class ProtocolAggregate {
        private final String protocolName;
        private String protocolVersion;
        private ProtocolRegistryRole role;
        private int bestScore;

        private ProtocolAggregate(
                String protocolName,
                String protocolVersion,
                ProtocolRegistryRole role,
                int bestScore
        ) {
            this.protocolName = protocolName;
            this.protocolVersion = protocolVersion;
            this.role = role;
            this.bestScore = bestScore;
        }

        private void include(ResolvedCandidate candidate) {
            if (candidate == null) {
                return;
            }
            if (candidate.score() > bestScore) {
                this.bestScore = candidate.score();
                this.protocolVersion = candidate.protocolVersion();
                this.role = candidate.role();
            }
        }
    }
}
