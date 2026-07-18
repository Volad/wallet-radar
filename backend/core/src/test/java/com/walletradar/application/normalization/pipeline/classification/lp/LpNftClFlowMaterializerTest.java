package com.walletradar.application.normalization.pipeline.classification.lp;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.support.LpPositionCorrelationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.LpPrincipalCloseEvidence;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LpNftClFlowMaterializerTest {

    private static final String UNISWAP_V4_MODIFY_EXIT_INPUT =
            "0xdd46508f0000000000000000000000000000000000000000000000000000000000000040"
                    + "00000000000000000000000000000000000000000000000000000000680132e8"
                    + "0000000000000000000000000000000000000000000000000000000000000240"
                    + "0000000000000000000000000000000000000000000000000000000000000040"
                    + "0000000000000000000000000000000000000000000000000000000000000080"
                    + "0000000000000000000000000000000000000000000000000000000000000002"
                    + "0111000000000000000000000000000000000000000000000000000000000000"
                    + "0000000000000000000000000000000000000000000000000000000000000002"
                    + "0000000000000000000000000000000000000000000000000000000000000040"
                    + "0000000000000000000000000000000000000000000000000000000000000120"
                    + "00000000000000000000000000000000000000000000000000000000000000c0"
                    + "000000000000000000000000000000000000000000000000000000000000a717"
                    + "000000000000000000000000000000000000000000000000000033c04e004746"
                    + "000000000000000000000000000000000000000000000000095058f2d83fbb0d"
                    + "00000000000000000000000000000000000000000000000000000000355d00ae"
                    + "00000000000000000000000000000000000000000000000000000000000000a0"
                    + "0000000000000000000000000000000000000000000000000000000000000000"
                    + "0000000000000000000000000000000000000000000000000000000000000060"
                    + "0000000000000000000000000000000000000000000000000000000000000000"
                    + "0000000000000000000000009151434b16b9763660705744891fa906f660ecc5"
                    + "0000000000000000000000000000000000000000000000000000000000000001";

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String POSITION_MANAGER = "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364";

    @Test
    void principalExitMaterializesOutboundLpReceiptAndPrincipalLegs() {
        RawTransaction raw = baseRaw(NetworkId.OPTIMISM);
        raw.getRawData().put("to", POSITION_MANAGER);
        raw.getRawData().put("methodId", "0x0c49ccbe");
        raw.getRawData().put("input", "0x0c49ccbe00000000000000000000000000000000000000000000000000000000000006a68d");
        raw.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x01bff41798a0bcf287b996046ca68b395dbc1071")
                        .append("tokenSymbol", "USDT0")
                        .append("tokenDecimal", "6")
                        .append("from", POSITION_MANAGER)
                        .append("to", WALLET)
                        .append("value", "226952468")
        )).append("internalTransfers", List.of()));

        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(raw);
        List<RawLeg> legs = List.of(
                RawLeg.asset("0x01bff41798a0bcf287b996046ca68b395dbc1071", "USDT0", new BigDecimal("226.952468"))
        );
        List<NormalizedTransaction.Flow> base = List.of(flow("USDT0", "226.952468", NormalizedLegRole.TRANSFER));

        // RC-1/RC-5 (ADR-018): the classifier now derives the contract-keyed correlationId and passes
        // it to enrich(); the receipt symbol is a pure function of that identity, not the protocol slug.
        String correlationId = LpPositionCorrelationSupport.contractKeyedLifecycleCorrelationId(
                view,
                NormalizedTransactionType.LP_EXIT,
                LpPositionCorrelationSupport.resolvePositionManagerContract(view)
        );

        List<NormalizedTransaction.Flow> enriched = LpNftClFlowMaterializer.enrich(
                view,
                legs,
                NormalizedTransactionType.LP_EXIT,
                correlationId,
                base
        );

        assertThat(enriched)
                .anySatisfy(flow -> {
                    // RC-1 (ADR-018): receipt symbol is keyed by the NFPM contract (rawData.to),
                    // not the protocol slug, so entry and exit cannot split into two pools.
                    assertThat(flow.getAssetSymbol())
                            .isEqualTo("LP-RECEIPT:OPTIMISM:0X46A15B0B27311CEDF172AB29E4F4766FBE7F4364:1702");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("-1");
                    assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
                })
                .anySatisfy(flow -> assertThat(flow.getAssetSymbol()).isEqualTo("USDT0"));
    }

    @Test
    void modifyLiquiditiesExitCalldataCarriesDecreaseAction() {
        RawTransaction raw = baseRaw(NetworkId.UNICHAIN);
        raw.getRawData().put("to", POSITION_MANAGER);
        raw.getRawData().put("methodId", "0xdd46508f");
        raw.getRawData().put("input", UNISWAP_V4_MODIFY_EXIT_INPUT);
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(raw);

        assertThat(LpPositionCorrelationSupport.hasDecreaseOrBurnActionInCalldata(view)).isTrue();
        assertThat(LpPrincipalCloseEvidence.hasPositionReductionEvidence(view)).isTrue();
    }

    @Test
    void cakeOnlyInboundRefinesToFeeClaimWithoutPositionReduction() {
        List<RawLeg> legs = List.of(
                RawLeg.asset("0x0", "CAKE", new BigDecimal("12.5"))
        );
        assertThat(LpPrincipalCloseEvidence.isHarvestOnlyRewardPattern(legs)).isTrue();
        assertThat(LpPrincipalCloseEvidence.refineLifecycleType(
                null,
                legs,
                NormalizedTransactionType.LP_EXIT
        )).isEqualTo(NormalizedTransactionType.LP_FEE_CLAIM);
    }

    @Test
    void gmxMarketCorrelationUsesSettlementShareSymbol() {
        String correlation = GmxMarketCorrelationSupport.correlationIdFromMovementLegs(
                OnChainRawTransactionView.wrap(baseRaw(NetworkId.ARBITRUM)),
                List.of(RawLeg.asset("0x70d95587d40a2caf56bd97485ab3eec10bee6336", "GM: ETH/USD [WETH-USDC]", new BigDecimal("1")))
        );
        assertThat(correlation).isEqualTo("gmx-lp:arbitrum:weth-usdc");
    }

    // -------------------------------------------------------------------------
    // BLOCKER-3: Balancer V3 LP_POSITION_STAKE / UNSTAKE / EXIT BPT canonicalization
    // -------------------------------------------------------------------------

    private static final String BPT_POOL = "0xfcec3c8d86329defb548202fe1b86ff2188603a8";
    private static final String BALANCER_V3_RECEIPT = "LP-RECEIPT:AVALANCHE:BALANCERV3:" + BPT_POOL;
    private static final String BALANCER_V3_CORR = "lp-position:avalanche:balancerv3:" + BPT_POOL;

    @Test
    void balancerV3StakeReplacesRawBptWithLpReceipt() {
        List<RawLeg> legs = List.of(
                RawLeg.asset(BPT_POOL, "Aave GHO/USDT/USDC", new BigDecimal("-2144.92")),
                RawLeg.asset("0xgaugeaddr", "stBPT", new BigDecimal("2144.92"))
        );
        List<NormalizedTransaction.Flow> base = List.of(
                flowWithContract(BPT_POOL, "Aave GHO/USDT/USDC", "-2144.92", NormalizedLegRole.TRANSFER),
                flowWithContract("0xgaugeaddr", "stBPT", "2144.92", NormalizedLegRole.TRANSFER)
        );

        List<NormalizedTransaction.Flow> enriched = LpNftClFlowMaterializer.enrich(
                OnChainRawTransactionView.wrap(baseRaw(NetworkId.AVALANCHE)),
                legs,
                NormalizedTransactionType.LP_POSITION_STAKE,
                BALANCER_V3_CORR,
                base
        );

        // Raw BPT outbound should be gone; LP-RECEIPT outbound should be present
        assertThat(enriched)
                .noneMatch(f -> "Aave GHO/USDT/USDC".equals(f.getAssetSymbol()))
                .anySatisfy(f -> {
                    assertThat(f.getAssetSymbol()).isEqualTo(BALANCER_V3_RECEIPT);
                    assertThat(f.getQuantityDelta()).isEqualByComparingTo("-2144.92");
                    assertThat(f.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
                });
    }

    @Test
    void balancerV3UnstakeReplacesRawBptWithLpReceipt() {
        List<NormalizedTransaction.Flow> base = List.of(
                flowWithContract(BPT_POOL, "Aave GHO/USDT/USDC", "2144.92", NormalizedLegRole.TRANSFER),
                flowWithContract("0xgaugeaddr", "stBPT", "-2144.92", NormalizedLegRole.TRANSFER)
        );

        List<NormalizedTransaction.Flow> enriched = LpNftClFlowMaterializer.enrich(
                OnChainRawTransactionView.wrap(baseRaw(NetworkId.AVALANCHE)),
                List.of(),
                NormalizedTransactionType.LP_POSITION_UNSTAKE,
                BALANCER_V3_CORR,
                base
        );

        assertThat(enriched)
                .noneMatch(f -> "Aave GHO/USDT/USDC".equals(f.getAssetSymbol()))
                .anySatisfy(f -> {
                    assertThat(f.getAssetSymbol()).isEqualTo(BALANCER_V3_RECEIPT);
                    assertThat(f.getQuantityDelta()).isEqualByComparingTo("2144.92");
                });
    }

    @Test
    void balancerV3ExitRemovesDuplicateRawBptBurn() {
        // Base flows as produced before fix: raw BPT burn AND LP-RECEIPT burn
        List<NormalizedTransaction.Flow> base = List.of(
                flow("USDC", "1000", NormalizedLegRole.TRANSFER),
                flow("GHO", "500", NormalizedLegRole.TRANSFER),
                flowWithContract(BPT_POOL, "Aave GHO/USDT/USDC", "-2102.02", NormalizedLegRole.TRANSFER)
        );
        List<RawLeg> legs = List.of(
                RawLeg.asset("0xusdcaddr", "USDC", new BigDecimal("1000")),
                RawLeg.asset("0xghoaddr", "GHO", new BigDecimal("500")),
                RawLeg.asset(BPT_POOL, "Aave GHO/USDT/USDC", new BigDecimal("-2102.02"))
        );

        List<NormalizedTransaction.Flow> enriched = LpNftClFlowMaterializer.enrich(
                OnChainRawTransactionView.wrap(baseRaw(NetworkId.AVALANCHE)),
                legs,
                NormalizedTransactionType.LP_EXIT,
                BALANCER_V3_CORR,
                base
        );

        // Raw BPT burn should be gone; LP-RECEIPT burn should be present; principal legs preserved
        assertThat(enriched)
                .noneMatch(f -> "Aave GHO/USDT/USDC".equals(f.getAssetSymbol()))
                .anySatisfy(f -> {
                    assertThat(f.getAssetSymbol()).isEqualTo(BALANCER_V3_RECEIPT);
                    assertThat(f.getQuantityDelta()).isEqualByComparingTo("-2102.02");
                })
                .anySatisfy(f -> assertThat(f.getAssetSymbol()).isEqualTo("USDC"))
                .anySatisfy(f -> assertThat(f.getAssetSymbol()).isEqualTo("GHO"));
    }

    @Test
    void nonBalancerStakePassesThroughUnchanged() {
        // LP_POSITION_STAKE for a non-Balancer V3 correlation should return base flows unchanged
        String nonBalancerCorr = "lp-position:avalanche:some-nfpm:12345";
        List<NormalizedTransaction.Flow> base = List.of(
                flow("TOKEN", "100", NormalizedLegRole.TRANSFER)
        );
        List<NormalizedTransaction.Flow> enriched = LpNftClFlowMaterializer.enrich(
                OnChainRawTransactionView.wrap(baseRaw(NetworkId.AVALANCHE)),
                List.of(),
                NormalizedTransactionType.LP_POSITION_STAKE,
                nonBalancerCorr,
                base
        );
        assertThat(enriched).isEqualTo(base);
    }

    private static NormalizedTransaction.Flow flowWithContract(
            String contract, String symbol, String qty, NormalizedLegRole role
    ) {
        NormalizedTransaction.Flow f = flow(symbol, qty, role);
        f.setAssetContract(contract);
        return f;
    }

    private static NormalizedTransaction.Flow flow(String symbol, String qty, NormalizedLegRole role) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        flow.setRole(role);
        return flow;
    }

    private static RawTransaction baseRaw(NetworkId networkId) {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(networkId.name());
        raw.setWalletAddress(WALLET);
        raw.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("value", "0")
                .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of())));
        return raw;
    }
}
