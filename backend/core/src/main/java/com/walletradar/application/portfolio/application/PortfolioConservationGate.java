package com.walletradar.application.portfolio.application;

import com.walletradar.application.costbasis.domain.BorrowLiability;
import com.walletradar.application.costbasis.domain.BorrowLiabilityRepository;
import com.walletradar.application.costbasis.domain.CounterpartyBasisPool;
import com.walletradar.application.costbasis.domain.CounterpartyBasisPoolRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.costbasis.application.ReplayToleranceProperties;
import com.walletradar.application.pricing.persistence.HistoricalPriceCacheService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Portfolio conservation invariant at dashboard read time (ADR-014 §D2).
 */
@Service
public class PortfolioConservationGate {

    private static final Logger log = LoggerFactory.getLogger(PortfolioConservationGate.class);
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int DIAGNOSTIC_LIMIT = 20;
    private static final String BYBIT_PREFIX = "bybit:";
    /** Matches {@code BYBIT:<uid>:FUND} deposit/withdraw anchors (Cycle/11 S3). */
    private static final String BYBIT_FUND_WALLET_PATTERN = "^BYBIT:[^:]+:FUND$";
    /** Normalized (lowercase) EVM address pattern — 0x + 40 hex chars. */
    private static final String EVM_ADDRESS_PATTERN = "^0x[0-9a-f]{40}$";
    /** Matches {@code DZENGI:<uid>} umbrella wallets (ADR-048 single-account model). */
    private static final String DZENGI_UMBRELLA_WALLET_PATTERN = "^DZENGI:[^:]+$";
    /** Counterparty sentinel used when a tx touches multiple external addresses. */
    private static final String MULTI_COUNTERPARTY = "MULTI";
    private static final Set<String> STABLECOIN_SYMBOLS = Set.of(
            "USDT", "USDC", "USDE", "USDS", "USDD", "USD"
    );
    /**
     * Known bridge payout / solver addresses.  Inbound transactions from these addresses are
     * always bridge receipts (never direct external capital), but may or may not correspond to
     * intra-universe corridors.  We apply amount+time matching against outbound legs to decide.
     */
    private static final Set<String> KNOWN_BRIDGE_PAYOUT_ADDRESSES = Set.of(
            // Relay Protocol solvers
            "0xcad97616f91872c02ba3553db315db4015cbe850",
            "0x7ff8bbf9c8ab106db589e7863fb100525f61cce5",
            "0xf70da97812cb96acdf810712aa562db8dfa3dbef",
            "0x91604f590d66ace8975eed6bd16cf55647d1c499",
            // LiFi relayer / agent
            "0x8c826f795466e39acbff1bb4eeeb759609377ba1",
            // Hyperlane / LiFi bridge payout — BRIDGE_OUT 2829.12 USDC → EXT_IN 2828.31 USDC 19 min later (2026-01-12)
            "0xf5f93d26229482adca3e42f84d08d549cf131658",
            // Relay/LiFi bridge payout — BRIDGE_IN $500.38/$29.86 USDC paired with EXT_OUT $500.66/$30.00 (2025-07-31)
            "0xc38e4e6a15593f908255214653d3d947ca1c2338",
            // EtherFi protocol address — inbound weETH and stablecoin flows from this address
            // are staking yield distributions (rebasing), not external capital deposits.
            "0xeeeeee9ec4769a09a76a83c7bc42b185872860ee",
            // LiFi solver on Mantle — delivers USDC to destination wallet; the originating
            // BRIDGE_OUT (corrId set) is not linked to the BRIDGE_IN (corrId=null) due to a
            // LiFi destination-discovery gap. Pattern 1 matches against BRIDGE_OUT $100.
            "0x00a55649e597d463fd212fbe48a3b40f0e227d06",
            // Across Protocol SpokePool (zkSync/L2) — relayer that delivers bridge payouts.
            // BRIDGE_IN corrId=null because LiFi routed through Across without destination linking.
            "0x4c1d3fc3fc3c177c3b633427c2f769276c547463",
            // Sep-29-2025 USDe EXT_IN source EOA — $16.96 USDe received as part of a vbUSDC→USDe
            // swap/bridge (BRIDGE_OUT vbUSDC $17.03 same day); Pattern 1 pairs them (0.4% diff).
            "0x113a327221d2c4660684449bfc39bc14ad1aaf38",
            // Bridge solver delivering USDC on inbound legs — Jul-01-2025 $895.05 USDC (corrId=null)
            // and multiple Jul-11-2025 corrId-linked receipts. Paired with BRIDGE_OUT USDT0 $895.04
            // (same day, <0.01% diff) via Pattern 1; symmetric exclusion keeps NEC stable.
            "0x875d6d37ec55c8cf220b9e5080717549d8aa8eca",
            // Bridge solver delivering USDC on Jul-11-2025 $895.98 (corrId=null).
            // Corresponding BRIDGE_OUT USDC $896.03 same day (corrId=null); Pattern 1 pairs them.
            "0x27a16dc786820b16e5c9028b75b99f6f604b5d26",
            // Bridge-related ETH transfer (fee refund / tip) on Jul-11-2025 $3.88 (corrId=null).
            // Corresponding BRIDGE_OUT WETH $3.88 same day (corrId=null); Pattern 1 pairs them.
            "0x09aea4b2242abc8bb4bb78d537a67a245a7bec64"
    );
    /**
     * Known Relay Protocol source / depositor addresses.  EXT_OUT flows to these addresses
     * are bridge-start transactions and should be paired with BRIDGE_IN from Relay solvers.
     */
    private static final Set<String> RELAY_SOURCE_ADDRESSES = Set.of(
            "0x2ec2c4c3dc212c990d1bc2b48b0392a3951d926e"
    );
    /**
     * Known LP pool addresses.  Inbound flows from these are LP exit receipts, not external
     * capital (e.g. Katana vbETH / vbUSDC pool paying back the user's LP share).
     */
    private static final Set<String> KNOWN_LP_POOL_ADDRESSES = Set.of(
            "0x2a2c512beaa8eb15495726c235472d82effb7a6b",  // Katana vbETH-vbUSDC LP pool
            // Katana vault/bridge contract (Nov-1-2025): delivers vbUSDC + ETH on Katana exit.
            // BRIDGE_IN from this address is always an LP position withdrawal, not new capital.
            // Deposit to Katana was Oct-22-2025, outside the 72-h Pattern-1 window.
            "0x2659c6085d26144117d904c46b48b6d180393d27",
            // Katana weETH vault (Nov-21-2025): ETH leg of weETH+ETH LP exit, flow-level cp.
            // Paired with weETH vault 0xba9dd716... in the same BRIDGE_IN transaction.
            "0x223ec22d67716fca620aee72b25ffe4ece436f25",
            // Katana weETH LP source vault (Nov-21-2025): weETH leg of the same LP exit.
            "0xba9dd716ba2a4b9fa7818802beb631f10bd28073"
    );

    /**
     * Legitimate WETH contract addresses across all supported chains.
     * A flow labeled ETH or WETH that carries a non-null assetContract not in this set
     * is a scam/airdrop fake token (e.g. "DisperseClone:Scam") and must be excluded from NEC.
     * Native ETH has no assetContract; real WETH uses chain-specific canonical contracts.
     */
    private static final Set<String> KNOWN_WETH_CONTRACTS = Set.of(
            "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",  // WETH — Ethereum mainnet
            "0x4200000000000000000000000000000000000006",  // WETH — Base / Optimism / Linea / Unichain
            "0x82af49447d8a07e3bd95bd0d56f35241523fbab1",  // WETH — Arbitrum One
            "0x5aea5775959fbc2557cc8789bc1bf90a239d9a91",  // WETH — zkSync Era
            "0x000000000000000000000000000000000000800a",  // ETH (native) — zkSync Era system proxy
            "0xdeaddeaddeaddeaddeaddeaddeaddeaddead1111",  // WETH — Mantle (L2 precompile address)
            "0xe5d7c2a44ffddf6b295a15c148167daaaf5cf34f",  // WETH — Linea
            "0x2def4285787d58a2f811af24755a8150622f4361",  // WETH — Cronos zkEVM
            "0x49d5c2bdffac6ce2bfdb6640f4f80f226bc10bab"   // WETH.e — Avalanche C-Chain
    );

    /** Maximum time window (minutes) to match a BRIDGE_IN payout with its originating outbound. */
    private static final long BRIDGE_PAIR_WINDOW_MINUTES = 240; // 4 hours
    /**
     * Minimum USD value for a Bybit FUND inbound flow to count as external capital.
     * Tiny test deposits / dust are excluded.
     */
    private static final BigDecimal MIN_FUND_FLOW_USD = new BigDecimal("5");
    /**
     * Minimum USD value for an EVM inbound flow (BRIDGE_IN / EXT_IN) to count as external capital.
     * Bridge dust (gas refunds, fractional payouts) is excluded.
     */
    private static final BigDecimal MIN_EVM_FLOW_USD = new BigDecimal("2");
    /** Maximum relative amount difference to accept a bridge amount match (≈ bridge fee). */
    /** Maximum time window (hours) to match an EXT_IN return with its originating BRIDGE_OUT. */
    private static final long BRIDGE_RETURN_WINDOW_HOURS = 48;
    /**
     * Canonical ETH-family symbols used for cross-flow price inference.
     * When a BRIDGE_OUT principal flow lacks pricing, a FEE flow carrying the same
     * ETH-family asset price in the same transaction can supply the unit price.
     */
    private static final Set<String> ETH_FAMILY_SYMBOLS = Set.of(
            "ETH", "WETH", "WEETH", "STETH", "WSTETH", "RETH", "CBETH",
            "AETHWETH", "AARBWETH",
            "NATIVE:ETHEREUM", "NATIVE:BASE", "NATIVE:ARBITRUM", "NATIVE:MANTLE",
            "NATIVE:UNICHAIN", "NATIVE:LINEA", "NATIVE:OPTIMISM",
            "NATIVE:ZKSYNC", "NATIVE:KATANA", "NATIVE:AVALANCHE"
    );
    /**
     * Canonical symbols tried in order when resolving ETH-family price via the historical
     * price cache fallback (e.g., for WETH on Mantle where FEE flows are MNT not ETH).
     */
    private static final List<String> ETH_CANONICAL_PRICE_SYMBOLS = List.of("ETH", "WETH");
    /**
     * Price sources tried in priority order for the ETH historical price fallback.
     */
    private static final List<PriceSource> ETH_PRICE_SOURCE_PRIORITY = List.of(
            PriceSource.BINANCE, PriceSource.BYBIT, PriceSource.COINGECKO
    );

    /** Inflow + outflow contribution from on-chain EVM universe member wallets. */
    private record EvmNecContribution(BigDecimal inflow, BigDecimal outflow) {
        static final EvmNecContribution ZERO = new EvmNecContribution(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private final CounterpartyBasisPoolRepository counterpartyBasisPoolRepository;
    private final BorrowLiabilityRepository borrowLiabilityRepository;
    private final AccountingUniverseRepository accountingUniverseRepository;
    private final MongoOperations mongoOperations;
    private final HistoricalPriceCacheService historicalPriceCacheService;
    private final ReplayToleranceProperties replayToleranceProperties;

    public PortfolioConservationGate(
            CounterpartyBasisPoolRepository counterpartyBasisPoolRepository,
            BorrowLiabilityRepository borrowLiabilityRepository,
            AccountingUniverseRepository accountingUniverseRepository,
            MongoOperations mongoOperations,
            HistoricalPriceCacheService historicalPriceCacheService,
            ReplayToleranceProperties replayToleranceProperties
    ) {
        this.counterpartyBasisPoolRepository = counterpartyBasisPoolRepository;
        this.borrowLiabilityRepository = borrowLiabilityRepository;
        this.accountingUniverseRepository = accountingUniverseRepository;
        this.mongoOperations = mongoOperations;
        this.historicalPriceCacheService = historicalPriceCacheService;
        this.replayToleranceProperties = replayToleranceProperties;
    }

    public record ConservationResult(
            BigDecimal netExternalCapitalUsd,
            BigDecimal lifetimeExternalInflowUsd,
            BigDecimal markToMarketUsd,
            BigDecimal expectedPnlUsd,
            BigDecimal reportedPnlUsd,
            BigDecimal conservationDeltaUsd,
            BigDecimal conservationThresholdUsd,
            boolean conservationBreached
    ) {
    }

    public record ConservationInputs(
            String accountingUniverseId,
            BigDecimal dashboardMarkToMarketUsd,
            BigDecimal totalRealisedPnlUsd,
            BigDecimal totalUnrealisedPnlUsd,
            List<SessionDashboardQueryService.TokenPositionView> tokenPositions
    ) {
    }

    public ConservationResult evaluate(ConservationInputs inputs) {
        if (inputs == null || blank(inputs.accountingUniverseId())) {
            return emptyResult(inputs);
        }
        String universeId = inputs.accountingUniverseId().trim();
        List<CounterpartyBasisPool> pools = counterpartyBasisPoolRepository.findByUniverseId(universeId);

        Map<String, AccountingUniverse.Member> membersByRef = loadMembersByRef(universeId);
        // Cycle/11 S3: NEC counts priced EXTERNAL_TRANSFER into BYBIT:*:FUND from non-universe
        // counterparties (crypto + fiat) plus EXTERNAL_TRANSFER_IN/OUT and BRIDGE_IN/OUT on
        // on-chain EVM universe member wallets from/to non-universe external counterparties.
        // Query order: BYBIT FUND IN → BYBIT FUND OUT → EVM capital flows → DZENGI umbrella.
        BigDecimal fundInflow = computeLifetimeFundInflow(membersByRef);
        BigDecimal fundOutflow = computeLifetimeFundOutflow(membersByRef);
        Set<String> evmMemberAddresses = extractEvmMemberAddresses(membersByRef);
        EvmNecContribution evmContrib = computeEvmNecContribution(membersByRef, evmMemberAddresses);
        // ADR-048: Dzengi single-umbrella NEC — count stablecoin EXTERNAL_TRANSFER_IN/OUT
        // on DZENGI:<uid> wallets (no sub-account split, unlike Bybit FUND/UTA).
        BigDecimal dzengiInflow = computeDzengiLifetimeTransfers(NormalizedTransactionType.EXTERNAL_TRANSFER_IN, membersByRef);
        // FIAT_EXIT is a sub-type of EXTERNAL_TRANSFER_OUT — include both in NEC outflow.
        BigDecimal dzengiOutflow = computeDzengiLifetimeTransfers(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT, membersByRef)
                .add(computeDzengiLifetimeTransfers(NormalizedTransactionType.FIAT_EXIT, membersByRef), MC);
        BigDecimal lifetimeInflow = fundInflow.add(evmContrib.inflow(), MC).add(dzengiInflow, MC);
        BigDecimal lifetimeOutflow = fundOutflow.add(evmContrib.outflow(), MC).add(dzengiOutflow, MC);
        BigDecimal nec = lifetimeInflow.subtract(lifetimeOutflow, MC);
        BigDecimal mtm = computeMarkToMarket(inputs, pools, membersByRef);
        BigDecimal totalLiabilityUsd = computeOpenLiabilityUsd(universeId);
        BigDecimal adjustedMtm = mtm.subtract(totalLiabilityUsd, MC);

        BigDecimal reportedPnl = zero(inputs.totalRealisedPnlUsd()).add(zero(inputs.totalUnrealisedPnlUsd()), MC);
        BigDecimal expectedPnl = adjustedMtm.subtract(nec, MC);
        BigDecimal delta = reportedPnl.subtract(expectedPnl, MC);
        BigDecimal threshold = conservationThreshold(adjustedMtm);
        boolean breached = delta.abs().compareTo(threshold) > 0;

        if (breached) {
            logConservationBreach(
                    universeId, delta, expectedPnl, reportedPnl, nec,
                    lifetimeInflow, lifetimeOutflow,
                    adjustedMtm, threshold, pools, inputs.tokenPositions()
            );
        }

        return new ConservationResult(
                nec,
                lifetimeInflow,
                adjustedMtm,
                expectedPnl,
                reportedPnl,
                delta,
                threshold,
                breached
        );
    }

    public BigDecimal conservationThreshold(BigDecimal markToMarketUsd) {
        BigDecimal mtm = zero(markToMarketUsd).abs();
        BigDecimal relative = mtm.multiply(replayToleranceProperties.getRelativeMtmFraction(), MC);
        return replayToleranceProperties.getAbsoluteFloorUsd().max(relative);
    }

    /**
     * Cycle/11 S3: gross lifetime deposits into {@code BYBIT:*:FUND} shown as dashboard "Net Inflow".
     *
     * <p>Counts priced {@code EXTERNAL_TRANSFER_IN} on the FUND sub-account where the
     * counterparty is <em>not</em> a universe member (excludes internal Bybit↔EVM corridors and
     * registered external venues like Paradex/MEX).</p>
     */
    private BigDecimal computeLifetimeFundInflow(Map<String, AccountingUniverse.Member> membersByRef) {
        return sumFundExternalTransfers(
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                membersByRef
        );
    }

    /**
     * Cycle/11 S3: gross lifetime withdrawals from {@code BYBIT:*:FUND}, symmetric to inflow.
     */
    private BigDecimal computeLifetimeFundOutflow(Map<String, AccountingUniverse.Member> membersByRef) {
        return sumFundExternalTransfers(
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                membersByRef
        );
    }

    /**
     * Extracts normalized (lowercase) wallet addresses of on-chain EVM universe members that
     * have backfill enabled (i.e. are active EVM wallets, not OOS networks like SOL/TON).
     */
    private static Set<String> extractEvmMemberAddresses(
            Map<String, AccountingUniverse.Member> membersByRef
    ) {
        if (membersByRef == null || membersByRef.isEmpty()) {
            return Set.of();
        }
        return membersByRef.values().stream()
                .filter(m -> m.getType() == AccountingUniverse.MemberType.ON_CHAIN_WALLET)
                .filter(m -> !Boolean.FALSE.equals(m.getBackfillEnabled()))
                .map(m -> normalizeRef(m.getRef()))
                .filter(ref -> ref.matches(EVM_ADDRESS_PATTERN))
                .collect(Collectors.toSet());
    }

    /**
     * Loads and aggregates NEC contribution from on-chain EVM universe member wallets.
     *
     * <p>Counts:
     * <ul>
     *   <li>{@code EXTERNAL_TRANSFER_IN} / {@code EXTERNAL_TRANSFER_OUT} where counterparty
     *       is not a universe member.</li>
     *   <li>{@code BRIDGE_IN} / {@code BRIDGE_OUT} from/to a non-universe, non-MULTI counterparty
     *       that are <em>not</em> part of a paired intra-universe corridor (identified by a shared
     *       {@code correlationId} appearing in both BRIDGE_OUT and BRIDGE_IN legs within the
     *       universe — those are handled by carry semantics).</li>
     * </ul>
     * Transactions with {@code excludedFromAccounting=true} are skipped.</p>
     */
    private EvmNecContribution computeEvmNecContribution(
            Map<String, AccountingUniverse.Member> membersByRef,
            Set<String> evmAddresses
    ) {
        if (evmAddresses.isEmpty()) {
            return EvmNecContribution.ZERO;
        }
        List<NormalizedTransaction> txList = loadEvmCapitalFlows(evmAddresses);
        if (txList.isEmpty()) {
            return EvmNecContribution.ZERO;
        }

        // Detect paired intra-universe bridge corridors: a correlationId that appears in
        // both BRIDGE_OUT and BRIDGE_IN legs means both sides are tracked universe wallets;
        // the carry semantics handle cost basis, so these should not be counted as NEC.
        Set<String> bridgeInCorrelationIds = new HashSet<>();
        Set<String> bridgeOutCorrelationIds = new HashSet<>();
        for (NormalizedTransaction tx : txList) {
            if (tx == null || tx.getCorrelationId() == null) {
                continue;
            }
            if (tx.getType() == NormalizedTransactionType.BRIDGE_IN) {
                bridgeInCorrelationIds.add(tx.getCorrelationId());
            } else if (tx.getType() == NormalizedTransactionType.BRIDGE_OUT) {
                bridgeOutCorrelationIds.add(tx.getCorrelationId());
            }
        }
        Set<String> pairedCorrelationIds = new HashSet<>(bridgeInCorrelationIds);
        pairedCorrelationIds.retainAll(bridgeOutCorrelationIds);

        // RC2b + RC3a: identify intra-universe bridge corridors by amount+time proximity.
        // Returns a set containing BOTH the inbound AND the matched outbound txHash so that
        // corridor pairing is symmetric: removing the inbound from inflow also removes the
        // corresponding outbound from outflow, keeping NEC (and conservation delta) stable.
        Set<String> corridorPairedHashes = buildCorridorPairedHashes(txList);

        BigDecimal inflow = BigDecimal.ZERO;
        BigDecimal outflow = BigDecimal.ZERO;
        Set<String> seenDedupeKeys = new HashSet<>();

        for (NormalizedTransaction tx : txList) {
            if (tx == null || tx.getFlows() == null) {
                continue;
            }
            if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                continue;
            }
            if (!evmAddresses.contains(normalizeRef(tx.getWalletAddress()))) {
                continue;
            }

            NormalizedTransactionType type = tx.getType();
            boolean isInbound = type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                    || type == NormalizedTransactionType.BRIDGE_IN;
            boolean isOutbound = type == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                    || type == NormalizedTransactionType.BRIDGE_OUT;
            if (!isInbound && !isOutbound) {
                continue;
            }

            // Skip paired intra-universe bridge corridor legs (both wallets tracked).
            boolean isBridge = type == NormalizedTransactionType.BRIDGE_IN
                    || type == NormalizedTransactionType.BRIDGE_OUT;
            if (isBridge && tx.getCorrelationId() != null
                    && pairedCorrelationIds.contains(tx.getCorrelationId())) {
                continue;
            }

            // RC2b + RC3a: skip both legs of amount+time-matched bridge corridors (BRIDGE_OUT /
            // EXT_OUT paired with BRIDGE_IN / EXT_IN from known solver/payout addresses, or
            // EXT_IN returns from the same bridge-entry address as a same-day BRIDGE_OUT).
            // Checking ALL transaction types so that NEC remains balanced (both directions excluded).
            if (tx.getTxHash() != null
                    && corridorPairedHashes.contains(tx.getTxHash().trim().toLowerCase(Locale.ROOT))) {
                continue;
            }

            // RC-direct: unconditional guard for known bridge solver/payout addresses.
            // Any inbound (BRIDGE_IN or EXT_IN) from these addresses is always a bridge
            // receipt, never a fresh external capital deposit.  The corresponding outbound
            // is excluded from outflow via corridorPairedHashes (Pattern 1), so this guard
            // completes the symmetric exclusion when Pattern 1 matched the outbound but the
            // inbound hash was not retained in corridorPairedHashes for any reason.
            if (isInbound) {
                String cp = normalizeRef(txCounterparty(tx));
                if (KNOWN_BRIDGE_PAYOUT_ADDRESSES.contains(cp)) {
                    continue;
                }
            }

            // RC-dapp-reward: EXT_IN with no counterparty address but a recognised DeFi
            // protocol name is a protocol-originated reward or PnL settlement (e.g. GMX V2
            // fee/profit distribution).  These are not external capital injections.
            if (isInbound && type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
                String cp = normalizeRef(txCounterparty(tx));
                String proto = tx.getProtocolName();
                if ((cp == null || cp.isBlank()) && proto != null && !proto.isBlank()) {
                    continue;
                }
            }

            String dedupeKey = depositDedupeKey(tx);
            if (dedupeKey != null && !seenDedupeKeys.add(dedupeKey)) {
                continue;
            }

            for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                    continue;
                }
                if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                    continue;
                }
                // RC-evm-ext-asset: For plain EXT_IN on EVM wallets, only stablecoin and
                // ETH-family flows count as external capital.  Non-standard tokens that happen
                // to have a USD price (airdrops, protocol reward tokens, LP receipt tokens, etc.)
                // are not capital injections.  BRIDGE_IN is exempt: legitimate bridges may carry
                // any asset (AVAX, BNB, etc.) as real capital.
                // Guard only applies when assetSymbol is known; unknown/null passes through.
                if (isInbound && type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
                    String rawSymUpper = flow.getAssetSymbol() != null
                            ? flow.getAssetSymbol().trim().toUpperCase(Locale.ROOT)
                            : "";
                    if (!rawSymUpper.isEmpty()) {
                        String normalizedSym = normalizeStablecoinSymbol(flow.getAssetSymbol());
                        boolean isStable = STABLECOIN_SYMBOLS.contains(normalizedSym);
                        boolean isEthFamily = ETH_FAMILY_SYMBOLS.contains(rawSymUpper);
                        if (!isStable && !isEthFamily) {
                            continue;
                        }
                    }
                }

                // For BRIDGE_IN: MULTI counterparty means ambiguous multi-sender router — skip.
                // For BRIDGE_OUT: assets departing to a MULTI router are a genuine external
                // outflow regardless of router labeling — do NOT suppress.
                if (isBridge && type == NormalizedTransactionType.BRIDGE_IN) {
                    String cp = resolveCounterpartyAddress(tx, flow);
                    if (MULTI_COUNTERPARTY.equalsIgnoreCase(cp)) {
                        continue;
                    }
                }
                // RC-fake-native: a flow labeled ETH/WETH that carries a non-null ERC-20 contract
                // address not in KNOWN_WETH_CONTRACTS is a scam/airdrop fake token distributed via
                // phishing contracts (e.g. DisperseClone:Scam). Real native ETH has no assetContract;
                // real WETH uses well-known chain-specific contracts. Applies to both inflow and
                // outflow so that NEC remains balanced (a fake drain is not a real capital loss).
                String rawSymForFakeCheck = flow.getAssetSymbol() != null
                        ? flow.getAssetSymbol().trim().toUpperCase(Locale.ROOT) : "";
                if (ETH_FAMILY_SYMBOLS.contains(rawSymForFakeCheck)) {
                    String contract = flow.getAssetContract();
                    if (contract != null && !contract.isBlank()
                            && !KNOWN_WETH_CONTRACTS.contains(contract.trim().toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                }

                // RC3b: skip inbound flows from known LP pool addresses (LP exit receipts are
                // not external capital; the pool returns the user's own previously-deployed assets).
                if (isInbound) {
                    String cp = resolveCounterpartyAddress(tx, flow);
                    if (cp != null && KNOWN_LP_POOL_ADDRESSES.contains(normalizeRef(cp))) {
                        continue;
                    }
                }
                if (!isNonUniverseCounterparty(membersByRef, tx, flow)) {
                    continue;
                }
                BigDecimal valueUsd = pricedFlowValueUsd(flow, tx.getFlows(), tx.getBlockTimestamp());
                if (valueUsd == null || valueUsd.signum() == 0) {
                    continue;
                }
                // RC-evm-dust: skip tiny inbound flows (bridge gas refunds, fractional payouts).
                // Outflows are not filtered — even small outbound amounts represent real capital
                // departures whose corresponding inbound may be larger.
                if (isInbound && valueUsd.compareTo(MIN_EVM_FLOW_USD) < 0) {
                    continue;
                }
                if (isInbound) {
                    inflow = inflow.add(valueUsd, MC);
                } else {
                    outflow = outflow.add(valueUsd, MC);
                }
            }
        }

        return new EvmNecContribution(inflow, outflow);
    }

    private List<NormalizedTransaction> loadEvmCapitalFlows(Set<String> evmAddresses) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(evmAddresses),
                Criteria.where("type").in(
                        NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                        NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                        NormalizedTransactionType.BRIDGE_IN,
                        NormalizedTransactionType.BRIDGE_OUT
                )
        ));
        query.fields()
                .include("walletAddress")
                .include("txHash")
                .include("type")
                .include("flows")
                .include("counterpartyAddress")
                .include("matchedCounterparty")
                .include("correlationId")
                .include("protocolName")
                .include("excludedFromAccounting")
                .include("blockTimestamp");
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private BigDecimal sumFundExternalTransfers(
            NormalizedTransactionType transferType,
            Map<String, AccountingUniverse.Member> membersByRef
    ) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(transferType),
                Criteria.where("walletAddress").regex(BYBIT_FUND_WALLET_PATTERN)
        ));
        query.fields()
                .include("walletAddress")
                .include("txHash")
                .include("flows")
                .include("counterpartyAddress")
                .include("matchedCounterparty");
        List<NormalizedTransaction> transactions = mongoOperations.find(query, NormalizedTransaction.class);
        if (transactions.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Set<String> seenDepositKeys = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (NormalizedTransaction transaction : transactions) {
            if (transaction == null || transaction.getFlows() == null) {
                continue;
            }
            if (!isUniverseMember(membersByRef, transaction.getWalletAddress())) {
                continue;
            }
            String dedupeKey = depositDedupeKey(transaction);
            if (dedupeKey != null && !seenDepositKeys.add(dedupeKey)) {
                continue;
            }
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                    continue;
                }
                if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                    continue;
                }
                // RC1: Bybit FUND inflows must be stablecoin-denominated to count as net external
                // capital. Non-stablecoin crypto (MNT, DOGS, SOL, …) deposited to FUND from an
                // external wallet is a crypto-to-crypto movement, not a fiat injection.
                if (transferType == NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
                    String normalizedSym = normalizeStablecoinSymbol(flow.getAssetSymbol());
                    if (!STABLECOIN_SYMBOLS.contains(normalizedSym)) {
                        continue;
                    }
                }
                if (!isNonUniverseCounterparty(membersByRef, transaction, flow)) {
                    continue;
                }
                BigDecimal valueUsd = pricedFlowValueUsd(flow);
                if (valueUsd == null || valueUsd.signum() == 0) {
                    continue;
                }
                // RC-fund-dust: skip tiny flows (test deposits, fractional earn credits, etc.)
                if (transferType == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                        && valueUsd.compareTo(MIN_FUND_FLOW_USD) < 0) {
                    continue;
                }
                total = total.add(valueUsd, MC);
            }
        }
        return total;
    }

    /**
     * ADR-048: Dzengi NEC contribution — EXTERNAL_TRANSFER_IN (deposits) or EXTERNAL_TRANSFER_OUT
     * (withdrawals) on {@code DZENGI:<uid>} umbrella wallets.
     * Unlike Bybit, there is no FUND sub-account; the single umbrella wallet is the capital gate.
     * All priced flows are counted (BYN + USD + stablecoins) — Dzengi is a fiat-entry exchange
     * where users deposit Belarusian Rubles or USD directly.
     */
    private BigDecimal computeDzengiLifetimeTransfers(
            NormalizedTransactionType transferType,
            Map<String, AccountingUniverse.Member> membersByRef
    ) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(transferType),
                Criteria.where("walletAddress").regex(DZENGI_UMBRELLA_WALLET_PATTERN)
        ));
        query.fields()
                .include("walletAddress")
                .include("txHash")
                .include("flows")
                .include("counterpartyAddress")
                .include("matchedCounterparty");
        List<NormalizedTransaction> transactions = mongoOperations.find(query, NormalizedTransaction.class);
        if (transactions.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (NormalizedTransaction transaction : transactions) {
            if (transaction == null || transaction.getFlows() == null) {
                continue;
            }
            if (!isUniverseMember(membersByRef, transaction.getWalletAddress())) {
                continue;
            }
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                    continue;
                }
                if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                    continue;
                }
                // Dzengi accepts both BYN (Belarusian Ruble) and USD fiat deposits — accept any
                // priced flow (BYN priced via DzengiFxPriceSourceAdapter, USD at 1:1).
                BigDecimal valueUsd = pricedFlowValueUsd(flow);
                if (valueUsd == null || valueUsd.signum() == 0) {
                    continue;
                }
                if (transferType == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                        && valueUsd.compareTo(MIN_FUND_FLOW_USD) < 0) {
                    continue;
                }
                total = total.add(valueUsd, MC);
            }
        }
        return total;
    }

    /**
     * RC2b + RC3a: Builds a SYMMETRIC set of txHashes covering BOTH legs of identified
     * intra-universe bridge corridors.  Including both sides in the exclusion set keeps NEC
     * (and therefore {@code conservationDeltaUsd}) stable while removing the overcounting from
     * {@code lifetimeExternalInflowUsd}.
     *
     * <h4>Pattern 1 — Relay/solver payout corridors (RC3a)</h4>
     * For each BRIDGE_IN (or EXT_IN) from a {@link #KNOWN_BRIDGE_PAYOUT_ADDRESSES} address
     * that has no {@code correlationId}: find a matching BRIDGE_OUT or EXT_OUT to a known
     * Relay source address in the same universe within ±4 h and ±1.5% USD amount.  Add BOTH
     * txHashes to the set.
     *
     * <h4>Pattern 2 — Bridge return / cancellation (RC2b)</h4>
     * For each EXT_IN whose counterparty also appears as the counterparty of a same-wallet
     * BRIDGE_OUT within ±48 h and ±1.5% USD amount: this is a bridge return (e.g. Hyperlane
     * refund returning USDC same-day).  Add BOTH txHashes.
     */
    private Set<String> buildCorridorPairedHashes(List<NormalizedTransaction> txList) {
        // ── collect outbound legs ──────────────────────────────────────────────────────────
        record OutLeg(String txHash, String counterparty, Instant ts, BigDecimal amountUsd) {}
        List<OutLeg> outLegs = new ArrayList<>();
        for (NormalizedTransaction tx : txList) {
            // Include ALL outbound transactions as potential corridor legs so that
            // bridge receipts from known solver/payout addresses (Pattern 1) can be
            // matched against any same-wallet EXT_OUT or BRIDGE_OUT (not just those
            // sent to the few known Relay source addresses).  Pattern 1 still guards
            // the inbound side with KNOWN_BRIDGE_PAYOUT_ADDRESSES, so only corridors
            // whose receipt comes from a known bridge solver/payout can ever be paired.
            boolean isExtOut = tx.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
            boolean isBridgeOut = tx.getType() == NormalizedTransactionType.BRIDGE_OUT;
            if (!isExtOut && !isBridgeOut) {
                continue;
            }
            if (tx.getTxHash() == null || tx.getBlockTimestamp() == null || tx.getFlows() == null) {
                continue;
            }
            BigDecimal outUsd = BigDecimal.ZERO;
            for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                    continue;
                }
                if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() >= 0) {
                    continue;
                }
                // Exclude fake-native tokens from corridor outLegs (same guard as main loop).
                String symUp = flow.getAssetSymbol() != null
                        ? flow.getAssetSymbol().trim().toUpperCase(Locale.ROOT) : "";
                if (ETH_FAMILY_SYMBOLS.contains(symUp)) {
                    String c = flow.getAssetContract();
                    if (c != null && !c.isBlank()
                            && !KNOWN_WETH_CONTRACTS.contains(c.trim().toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                }
                BigDecimal usd = pricedFlowValueUsd(flow, tx.getFlows(), tx.getBlockTimestamp());
                if (usd != null && usd.signum() > 0) {
                    outUsd = outUsd.add(usd, MC);
                }
            }
            if (outUsd.signum() > 0) {
                outLegs.add(new OutLeg(
                        tx.getTxHash().trim().toLowerCase(Locale.ROOT),
                        normalizeRef(txCounterparty(tx)),
                        tx.getBlockTimestamp(),
                        outUsd
                ));
            }
        }
        if (outLegs.isEmpty()) {
            return Set.of();
        }

        Set<String> corridorHashes = new HashSet<>();

        // ── Pattern 1: solver-payout BRIDGE_IN / EXT_IN ↔ any EXT_OUT / BRIDGE_OUT ─────────
        // We do NOT skip BRIDGE_INs with a correlationId here: a BRIDGE_IN from a known
        // payout address may carry a corrId yet its paired BRIDGE_OUT might be absent from the
        // database (different source wallet, missing backfill segment, etc.).  In that case the
        // correlationId check in the main loop already leaves it in the inflow, so we must
        // also attempt to match it via amount+time proximity to exclude it correctly.
        for (NormalizedTransaction tx : txList) {
            boolean isBridgeIn = tx.getType() == NormalizedTransactionType.BRIDGE_IN;
            boolean isExtIn = tx.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN;
            if (!isBridgeIn && !isExtIn) {
                continue;
            }
            if (tx.getTxHash() == null || tx.getBlockTimestamp() == null || tx.getFlows() == null) {
                continue;
            }
            String cp = normalizeRef(txCounterparty(tx));
            if (!KNOWN_BRIDGE_PAYOUT_ADDRESSES.contains(cp)) {
                continue;
            }
            BigDecimal inUsd = BigDecimal.ZERO;
            for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                    continue;
                }
                if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
                    continue;
                }
                BigDecimal usd = pricedFlowValueUsd(flow, tx.getFlows(), tx.getBlockTimestamp());
                if (usd != null && usd.signum() > 0) {
                    inUsd = inUsd.add(usd, MC);
                }
            }
            if (inUsd.signum() == 0) {
                continue;
            }
            final BigDecimal finalInUsd = inUsd;
            final Instant inTs = tx.getBlockTimestamp();
            String inTxKey = tx.getTxHash().trim().toLowerCase(Locale.ROOT);
            for (OutLeg out : outLegs) {
                long diffMinutes = Math.abs(Duration.between(out.ts(), inTs).toMinutes());
                if (diffMinutes > BRIDGE_PAIR_WINDOW_MINUTES) {
                    continue;
                }
                if (out.amountUsd().signum() == 0) {
                    continue;
                }
                BigDecimal ratio = finalInUsd.divide(out.amountUsd(), MC).subtract(BigDecimal.ONE).abs();
                if (ratio.compareTo(replayToleranceProperties.getBridgePairAmountTolerance()) <= 0) {
                    corridorHashes.add(inTxKey);         // inbound leg excluded from inflow
                    corridorHashes.add(out.txHash());    // outbound leg excluded from outflow
                    break;
                }
            }
        }

        // ── Pattern 2: bridge return / cancellation — EXT_IN ↔ BRIDGE_OUT same address ────
        for (NormalizedTransaction tx : txList) {
            if (tx.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
                continue;
            }
            if (tx.getTxHash() == null || tx.getBlockTimestamp() == null || tx.getFlows() == null) {
                continue;
            }
            String inCp = normalizeRef(txCounterparty(tx));
            if (inCp.isBlank()) {
                continue;
            }
            BigDecimal extInUsd = BigDecimal.ZERO;
            for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                    continue;
                }
                if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
                    continue;
                }
                BigDecimal usd = pricedFlowValueUsd(flow, tx.getFlows(), tx.getBlockTimestamp());
                if (usd != null && usd.signum() > 0) {
                    extInUsd = extInUsd.add(usd, MC);
                }
            }
            if (extInUsd.signum() == 0) {
                continue;
            }
            final String finalInCp = inCp;
            final Instant inTs = tx.getBlockTimestamp();
            final BigDecimal finalExtInUsd = extInUsd;
            String inTxKey = tx.getTxHash().trim().toLowerCase(Locale.ROOT);
            for (OutLeg out : outLegs) {
                if (!finalInCp.equals(out.counterparty())) {
                    continue;
                }
                long diffHours = Math.abs(Duration.between(out.ts(), inTs).toHours());
                if (diffHours > BRIDGE_RETURN_WINDOW_HOURS) {
                    continue;
                }
                if (out.amountUsd().signum() == 0) {
                    continue;
                }
                BigDecimal ratio = finalExtInUsd.divide(out.amountUsd(), MC).subtract(BigDecimal.ONE).abs();
                if (ratio.compareTo(replayToleranceProperties.getBridgePairAmountTolerance()) <= 0) {
                    corridorHashes.add(inTxKey);         // EXT_IN (return) excluded from inflow
                    corridorHashes.add(out.txHash());    // BRIDGE_OUT excluded from outflow
                    break;
                }
            }
        }

        return corridorHashes;
    }

    /**
     * Resolves a single counterparty address for a whole transaction (tx-level lookup,
     * not per-flow).  Returns an empty string when no counterparty can be resolved.
     */
    private static String txCounterparty(NormalizedTransaction tx) {
        if (tx.getCounterpartyAddress() != null && !tx.getCounterpartyAddress().isBlank()) {
            return tx.getCounterpartyAddress();
        }
        if (tx.getMatchedCounterparty() != null && !tx.getMatchedCounterparty().isBlank()) {
            return tx.getMatchedCounterparty();
        }
        return "";
    }

    private static boolean isNonUniverseCounterparty(
            Map<String, AccountingUniverse.Member> membersByRef,
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        String counterparty = resolveCounterpartyAddress(transaction, flow);
        if (counterparty == null || counterparty.isBlank()) {
            return true;
        }
        return !isUniverseMember(membersByRef, counterparty);
    }

    private static String resolveCounterpartyAddress(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (flow != null && flow.getCounterpartyAddress() != null && !flow.getCounterpartyAddress().isBlank()) {
            return flow.getCounterpartyAddress();
        }
        if (transaction != null && transaction.getCounterpartyAddress() != null && !transaction.getCounterpartyAddress().isBlank()) {
            return transaction.getCounterpartyAddress();
        }
        if (transaction != null && transaction.getMatchedCounterparty() != null && !transaction.getMatchedCounterparty().isBlank()) {
            return transaction.getMatchedCounterparty();
        }
        return null;
    }

    private static String depositDedupeKey(NormalizedTransaction transaction) {
        if (transaction.getTxHash() != null && !transaction.getTxHash().isBlank()) {
            return transaction.getWalletAddress() + "|" + transaction.getTxHash().trim().toLowerCase(Locale.ROOT);
        }
        return transaction.getId();
    }

    private static boolean isUniverseMember(
            Map<String, AccountingUniverse.Member> membersByRef,
            String counterpartyAddress
    ) {
        return resolveMember(membersByRef, counterpartyAddress) != null;
    }

    /**
     * Cycle/9 S1: Bybit wallet refs ({@code BYBIT:<uid>:FUND/UTA/EARN}) are stored as
     * {@code BYBIT:<uid>} in {@link AccountingUniverse#getMembers()}. Strip the sub-account
     * suffix before lookup so that all three sub-ledgers of an integrated Bybit account count
     * as universe members.
     */

    private BigDecimal pricedFlowValueUsd(NormalizedTransaction.Flow flow) {
        return pricedFlowValueUsd(flow, List.of(), null);
    }

    /**
     * Resolves USD value for a flow, with cross-flow price inference and historical-price fallback.
     *
     * <p>Resolution priority:
     * <ol>
     *   <li>Direct {@code valueUsd} or {@code unitPriceUsd} on the flow itself.</li>
     *   <li>Stablecoin identity: quantity treated as USD.</li>
     *   <li>Cross-flow ETH-family inference: another flow in the same transaction that
     *       shares the same ETH-family asset and carries a usable unit price.</li>
     *   <li>Historical price cache fallback for ETH-family assets when no ETH-family
     *       sibling is present (e.g. WETH on Mantle where FEE flows are MNT, not ETH).</li>
     * </ol>
     * </p>
     */
    private BigDecimal pricedFlowValueUsd(
            NormalizedTransaction.Flow flow,
            List<NormalizedTransaction.Flow> allFlows,
            @Nullable Instant blockTimestamp
    ) {
        if (flow.getValueUsd() != null && flow.getValueUsd().signum() != 0) {
            return flow.getValueUsd().abs();
        }
        if (flow.getUnitPriceUsd() != null
                && flow.getQuantityDelta() != null
                && flow.getUnitPriceUsd().signum() != 0) {
            return flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), MC);
        }
        String symbol = flow.getAssetSymbol();
        if (symbol != null && flow.getQuantityDelta() != null) {
            String normalized = normalizeStablecoinSymbol(symbol);
            if (STABLECOIN_SYMBOLS.contains(normalized)) {
                return flow.getQuantityDelta().abs();
            }
        }
        if (symbol != null && flow.getQuantityDelta() != null && isEthFamily(symbol)) {
            // Cross-flow ETH-family price inference: use unit price from another ETH-family flow.
            if (allFlows != null) {
                BigDecimal inferredUnitPrice = resolveEthFamilyUnitPriceFromSiblings(allFlows, flow);
                if (inferredUnitPrice != null && inferredUnitPrice.signum() != 0) {
                    return flow.getQuantityDelta().abs().multiply(inferredUnitPrice, MC);
                }
            }
            // Fallback: on networks where FEE flows use a non-ETH native token (e.g. Mantle/MNT),
            // no ETH-family sibling is present. Look up the canonical ETH price from the
            // historical price cache so WETH BRIDGE_OUT flows are counted in NEC outflow.
            if (blockTimestamp != null && historicalPriceCacheService != null) {
                BigDecimal cachedUnitPrice = resolveEthFamilyUnitPriceFromCache(blockTimestamp);
                if (cachedUnitPrice != null && cachedUnitPrice.signum() != 0) {
                    return flow.getQuantityDelta().abs().multiply(cachedUnitPrice, MC);
                }
            }
        }
        return null;
    }

    private static boolean isEthFamily(String symbol) {
        return symbol != null && ETH_FAMILY_SYMBOLS.contains(symbol.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Scans sibling flows for a flow whose asset is in the ETH family and that has a
     * derivable unit price. Returns the first matching unit price found, or {@code null}.
     */
    private static BigDecimal resolveEthFamilyUnitPriceFromSiblings(
            List<NormalizedTransaction.Flow> allFlows,
            NormalizedTransaction.Flow targetFlow
    ) {
        for (NormalizedTransaction.Flow sibling : allFlows) {
            if (sibling == null || sibling == targetFlow) {
                continue;
            }
            String sibSymbol = sibling.getAssetSymbol();
            if (!isEthFamily(sibSymbol)) {
                continue;
            }
            if (sibling.getUnitPriceUsd() != null && sibling.getUnitPriceUsd().signum() != 0) {
                return sibling.getUnitPriceUsd();
            }
            if (sibling.getValueUsd() != null && sibling.getValueUsd().signum() != 0
                    && sibling.getQuantityDelta() != null && sibling.getQuantityDelta().signum() != 0) {
                return sibling.getValueUsd().abs().divide(sibling.getQuantityDelta().abs(), MC);
            }
        }
        return null;
    }

    /**
     * Resolves the ETH canonical unit price from the historical price cache at the given
     * timestamp. Tries {@link #ETH_CANONICAL_PRICE_SYMBOLS} across {@link #ETH_PRICE_SOURCE_PRIORITY}.
     * Returns the first matching non-zero price, or {@code null} if none found.
     */
    private BigDecimal resolveEthFamilyUnitPriceFromCache(Instant blockTimestamp) {
        for (PriceSource source : ETH_PRICE_SOURCE_PRIORITY) {
            var quote = historicalPriceCacheService.findCanonicalQuote(
                    ETH_CANONICAL_PRICE_SYMBOLS, blockTimestamp, source
            );
            if (quote.isPresent() && quote.get().unitPriceUsd() != null
                    && quote.get().unitPriceUsd().signum() != 0) {
                return quote.get().unitPriceUsd();
            }
        }
        return null;
    }

    /**
     * Normalizes a token symbol for stablecoin lookup.
     *
     * <ul>
     *   <li>Strips leading vault/aToken prefixes: {@code vb}, {@code a}, {@code s}
     *       (e.g. {@code vbUSDC} → {@code USDC}, {@code aUSDC} → {@code USDC})</li>
     *   <li>Replaces Unicode tether sign {@code ₮} (U+20AE) with {@code T}
     *       (e.g. {@code USD₮0} → {@code USDT0} → stripped trailing {@code 0} → {@code USDT})</li>
     *   <li>Strips trailing digit {@code 0} after the core symbol</li>
     * </ul>
     */
    static String normalizeStablecoinSymbol(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        // Replace Unicode tether sign ₮ (U+20AE) with T
        s = s.replace('\u20AE', 'T');
        // Strip leading vault/aToken prefixes (case-insensitive, already uppercased)
        if (s.startsWith("VB") && s.length() > 2) {
            s = s.substring(2);
        } else if ((s.startsWith("A") || s.startsWith("S")) && s.length() > 1) {
            String candidate = s.substring(1);
            if (STABLECOIN_SYMBOLS.contains(candidate) || looksLikeStablecoinRoot(candidate)) {
                s = candidate;
            }
        }
        // Strip trailing digit 0 (OFT bridged token suffix, e.g. USDT0 → USDT)
        if (s.endsWith("0") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static boolean looksLikeStablecoinRoot(String symbol) {
        return symbol.startsWith("USD") || symbol.startsWith("DAI") || symbol.startsWith("FRAX");
    }

    private BigDecimal computeMarkToMarket(
            ConservationInputs inputs,
            List<CounterpartyBasisPool> pools,
            Map<String, AccountingUniverse.Member> membersByRef
    ) {
        BigDecimal mtm = zero(inputs.dashboardMarkToMarketUsd());
        if (pools == null || pools.isEmpty()) {
            return mtm;
        }
        for (CounterpartyBasisPool pool : pools) {
            if (!Boolean.TRUE.equals(pool.getIsMemberAtLastTouch())) {
                continue;
            }
            BigDecimal qtyHeld = zero(pool.getQtyHeld());
            if (qtyHeld.signum() <= 0) {
                continue;
            }
            String counterparty = pool.getCounterpartyAddress();
            if (counterparty == null || counterparty.isBlank()) {
                continue;
            }
            AccountingUniverse.Member member = resolveMember(membersByRef, counterparty);
            if (member == null) {
                continue;
            }
            if (memberBackfillEnabled(member)) {
                continue;
            }
            mtm = mtm.add(qtyHeld.multiply(zero(pool.getAvcoUsd()), MC), MC);
        }
        return mtm;
    }

    private BigDecimal computeOpenLiabilityUsd(String universeId) {
        return borrowLiabilityRepository.findByUniverseId(universeId).stream()
                .filter(liability -> liability.getQtyOpen() != null && liability.getQtyOpen().signum() > 0)
                .map(this::liabilityMarketValueUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
    }

    private BigDecimal liabilityMarketValueUsd(BorrowLiability liability) {
        BigDecimal qty = zero(liability.getQtyOpen());
        BigDecimal avco = zero(liability.getPortfolioAvcoAtOpen());
        return qty.multiply(avco, MC);
    }

    private Map<String, AccountingUniverse.Member> loadMembersByRef(String universeId) {
        List<AccountingUniverse.Member> members = accountingUniverseRepository.findById(universeId)
                .map(AccountingUniverse::getMembers)
                .orElse(List.of());
        if (members == null || members.isEmpty()) {
            return Map.of();
        }
        return members.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        member -> normalizeRef(member.getRef()),
                        member -> member,
                        (left, right) -> left
                ));
    }

    private static AccountingUniverse.Member resolveMember(
            Map<String, AccountingUniverse.Member> membersByRef,
            String counterpartyAddress
    ) {
        if (membersByRef == null || membersByRef.isEmpty() || counterpartyAddress == null || counterpartyAddress.isBlank()) {
            return null;
        }
        String normalized = normalizeRef(counterpartyAddress);
        AccountingUniverse.Member direct = membersByRef.get(normalized);
        if (direct != null) {
            return direct;
        }
        if (normalized.startsWith(BYBIT_PREFIX)) {
            String rootBybitRef = bybitRootRef(normalized);
            if (rootBybitRef != null) {
                AccountingUniverse.Member root = membersByRef.get(rootBybitRef);
                if (root != null) {
                    return root;
                }
            }
        }
        return null;
    }

    private static String bybitRootRef(String normalizedRef) {
        if (normalizedRef == null || !normalizedRef.startsWith(BYBIT_PREFIX)) {
            return null;
        }
        int firstColon = normalizedRef.indexOf(':');
        int secondColon = normalizedRef.indexOf(':', firstColon + 1);
        if (secondColon <= 0) {
            return normalizedRef;
        }
        return normalizedRef.substring(0, secondColon);
    }

    private static boolean memberBackfillEnabled(AccountingUniverse.Member member) {
        if (member == null || member.getBackfillEnabled() == null) {
            return true;
        }
        return member.getBackfillEnabled();
    }

    private void logConservationBreach(
            String universeId,
            BigDecimal delta,
            BigDecimal expectedPnl,
            BigDecimal reportedPnl,
            BigDecimal nec,
            BigDecimal lifetimeInflowUsd,
            BigDecimal lifetimeOutflowUsd,
            BigDecimal mtm,
            BigDecimal threshold,
            List<CounterpartyBasisPool> pools,
            List<SessionDashboardQueryService.TokenPositionView> positions
    ) {
        List<Map<String, Object>> topNonMemberPools = pools.stream()
                .filter(pool -> !Boolean.TRUE.equals(pool.getIsMemberAtLastTouch()))
                .sorted(Comparator.<CounterpartyBasisPool, BigDecimal>comparing(
                        pool -> zero(pool.getLifetimeInBasisUsd()).subtract(zero(pool.getLifetimeOutBasisUsd()), MC).abs()
                ).reversed())
                .limit(DIAGNOSTIC_LIMIT)
                .map(pool -> Map.<String, Object>of(
                        "counterpartyAddress", String.valueOf(pool.getCounterpartyAddress()),
                        "networkId", pool.getNetworkId() == null ? "" : pool.getNetworkId().name(),
                        "assetFamily", String.valueOf(pool.getAssetFamily()),
                        "lifetimeInBasisUsd", zero(pool.getLifetimeInBasisUsd()),
                        "lifetimeOutBasisUsd", zero(pool.getLifetimeOutBasisUsd()),
                        "lastTouchedAt", pool.getLastTouchedAt() == null ? "" : pool.getLastTouchedAt().toString()
                ))
                .toList();

        List<Map<String, Object>> topMemberPools = pools.stream()
                .filter(pool -> Boolean.TRUE.equals(pool.getIsMemberAtLastTouch()))
                .filter(pool -> zero(pool.getQtyHeld()).signum() > 0)
                .sorted(Comparator.<CounterpartyBasisPool, BigDecimal>comparing(
                        pool -> zero(pool.getQtyHeld()).multiply(zero(pool.getAvcoUsd()), MC).abs()
                ).reversed())
                .limit(DIAGNOSTIC_LIMIT)
                .map(pool -> Map.<String, Object>of(
                        "counterpartyAddress", String.valueOf(pool.getCounterpartyAddress()),
                        "qtyHeld", zero(pool.getQtyHeld()),
                        "avcoUsd", zero(pool.getAvcoUsd())
                ))
                .toList();

        List<Map<String, Object>> pendingPositions = positions == null ? List.of() : positions.stream()
                .sorted(Comparator.<SessionDashboardQueryService.TokenPositionView, BigDecimal>comparing(
                        position -> zero(position.unrealizedPnlUsd()).abs()
                ).reversed())
                .limit(DIAGNOSTIC_LIMIT)
                .map(position -> Map.<String, Object>of(
                        "walletAddress", position.walletAddress(),
                        "symbol", position.symbol(),
                        "unrealisedPnlUsd", zero(position.unrealizedPnlUsd())
                ))
                .toList();

        log.warn(
                "conservationBreached universeId={} conservationDelta={} expectedPnl={} reportedPnl={} nec={} "
                        + "lifetimeInflowUsd={} lifetimeOutflowUsd={} mtm={} threshold={} "
                        + "topNonMemberPoolsByNetCapitalDelta={} topMemberPoolsByQtyHeld={} pendingPositions={}",
                universeId,
                delta,
                expectedPnl,
                reportedPnl,
                nec,
                lifetimeInflowUsd,
                lifetimeOutflowUsd,
                mtm,
                threshold,
                topNonMemberPools,
                topMemberPools,
                pendingPositions
        );
    }

    private ConservationResult emptyResult(ConservationInputs inputs) {
        BigDecimal reported = inputs == null
                ? BigDecimal.ZERO
                : zero(inputs.totalRealisedPnlUsd()).add(zero(inputs.totalUnrealisedPnlUsd()), MC);
        return new ConservationResult(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                reported,
                reported,
                replayToleranceProperties.getAbsoluteFloorUsd(),
                true
        );
    }

    private static String normalizeRef(String ref) {
        return ref == null ? "" : ref.trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
