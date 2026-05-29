package com.walletradar.ingestion.pipeline.classification.lp;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.support.LpPositionCorrelationSupport;
import com.walletradar.ingestion.pipeline.classification.support.LpPrincipalCloseEvidence;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
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

        List<NormalizedTransaction.Flow> enriched = LpNftClFlowMaterializer.enrich(
                view,
                legs,
                NormalizedTransactionType.LP_EXIT,
                "Velodrome",
                base
        );

        assertThat(enriched)
                .anySatisfy(flow -> {
                    assertThat(flow.getAssetSymbol()).isEqualTo("LP-RECEIPT:OPTIMISM:VELODROME:1702");
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
