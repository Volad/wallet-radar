package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationResult;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassifier;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryLoader;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.application.session.application.TrackedWalletLookupService;
import com.walletradar.testsupport.NetworkTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * R6a (M1) regression: the Aura {@code withdrawAndUnwrap} unstake must classify as
 * {@link NormalizedTransactionType#LP_POSITION_UNSTAKE} through the <em>full</em> on-chain classifier
 * pipeline (real {@code protocol-registry.json}, real movement-leg extraction), NOT fall through to
 * a generic {@code HEURISTIC LP_EXIT}.
 *
 * <p>Anchor tx (evidence only, never a runtime key):
 * {@code 0x2447dc7f857603fa5fcaa309f7f63e1c6b1e51d8da44e01e5ac7f09d8d82f11e} on Avalanche. The call
 * target is the Aura BaseRewardPool / deposit-vault {@code 0x7037358a…} (a {@code STAKE_CONTRACT}
 * whose {@code underlyingPositionManager} is the Balancer V3 BPT {@code 0xfcec3c8d…}), and the tx
 * returns the BPT + BAL/AURA/WAVAX rewards while burning the deposit-vault token. The prior
 * mis-classification anchored on the Aura Booster {@code 0x98ef32…} (the STAKE target), not the
 * deposit-vault (the UNSTAKE target), so the unstake path was never exercised end-to-end.</p>
 */
class AuraUnstakeFullPipelineClassificationTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String DEPOSIT_VAULT = "0x7037358a6f2c1d9e5cc9b4a29e7415a7060dadcc";
    private static final String BOOSTER = "0x98ef32edd24e2c92525e59afc4475c1242a30184";
    private static final String BPT = "0xfcec3c8d86329defb548202fe1b86ff2188603a8";
    private static final String EXPECTED_CORRELATION =
            "lp-position:avalanche:balancerv3:0xfcec3c8d86329defb548202fe1b86ff2188603a8";
    private static final String LP_RECEIPT_SYMBOL =
            "LP-RECEIPT:AVALANCHE:BALANCERV3:0xfcec3c8d86329defb548202fe1b86ff2188603a8";

    @Test
    @DisplayName("Aura withdrawAndUnwrap (BPT + rewards + deposit-vault burn) classifies as LP_POSITION_UNSTAKE")
    void auraWithdrawAndUnwrapClassifiesAsLpPositionUnstake() {
        com.walletradar.domain.common.NetworkStablecoinContracts.bind(
                networkId -> NetworkTestFixtures.registry().usdStableContracts(networkId));
        ProtocolRegistryService registry = new ProtocolRegistryService(
                new ProtocolRegistryLoader(new ObjectMapper()));
        OnChainClassifier classifier = new OnChainClassifier(
                registry,
                mock(TrackedWalletLookupService.class),
                new NativeAssetSymbolResolver(NetworkTestFixtures.registry()));

        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0x2447dc7f857603fa5fcaa309f7f63e1c6b1e51d8da44e01e5ac7f09d8d82f11e")
                .setNetworkId(NetworkId.AVALANCHE.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document("to", DEPOSIT_VAULT)
                        .append("from", WALLET)
                        .append("methodId", "0xc32e7202")
                        .append("functionName", "withdrawAndUnwrap(uint256 amount, bool claim) returns (bool)")
                        .append("explorer", new Document("tokenTransfers", List.of(
                                // BPT returned to the wallet from the Aura Booster.
                                tokenTransfer(BOOSTER, WALLET, BPT, "42898493206184378445", "Aave GHO/USDT/USDC"),
                                // Deposit-vault (auraBPT wrapper) burned to the zero address.
                                tokenTransfer(WALLET, "0x0000000000000000000000000000000000000000", DEPOSIT_VAULT,
                                        "42898493206184378445", "auraAave GHO/USDT/USDC-vault"),
                                // BAL / AURA (x2) / WAVAX reward accrual claimed alongside the unwrap.
                                tokenTransfer(DEPOSIT_VAULT, WALLET, "0xe15bcb9e0ea69e6ab9fa080c4c4a5632896298c3",
                                        "83920744258171159", "BAL"),
                                tokenTransfer("0x8b2970c237656d3895588b99a8bfe977d5618201", WALLET,
                                        "0x1509706a6c66ca549ff0cb464de88231ddbe213b", "82588898588616934", "AURA"),
                                tokenTransfer("0xed741b14a26ae0ae466746d6f7b96fc31940ff05", WALLET,
                                        "0x1509706a6c66ca549ff0cb464de88231ddbe213b", "212195409207323942", "AURA"),
                                tokenTransfer("0x16468aa3d0ef13d5f3dc0b958dfbe2908a1299b8", WALLET,
                                        "0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7", "779301159036005", "WAVAX")
                        )).append("internalTransfers", List.<Document>of())));

        OnChainClassificationResult result = classifier.classify(rawTransaction);

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_POSITION_UNSTAKE);
        assertThat(result.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
        assertThat(result.correlationId()).isEqualTo(EXPECTED_CORRELATION);

        // The BPT principal returned to the wallet is canonicalized to the pool LP-RECEIPT marker so
        // the replay re-credits the position and carries basis back through the wrapper.
        assertThat(result.flows())
                .anySatisfy(flow -> assertThat(flow.getAssetSymbol()).isEqualTo(LP_RECEIPT_SYMBOL));
        // The deposit-vault (auraBPT wrapper) is the outbound (burned) leg — the CARRY_OUT counterpart.
        assertThat(result.flows())
                .anySatisfy(flow -> {
                    assertThat(flow.getAssetSymbol()).isEqualTo("auraAave GHO/USDT/USDC-vault");
                    assertThat(flow.getQuantityDelta().signum()).isNegative();
                });
        // Reward legs are still present (booked zero-cost by the replay dispatcher, not principal).
        assertThat(result.flows())
                .anySatisfy(flow -> assertThat(flow.getAssetSymbol()).isEqualTo("BAL"))
                .anySatisfy(flow -> assertThat(flow.getAssetSymbol()).isEqualTo("WAVAX"));
    }

    private static Document tokenTransfer(String from, String to, String contract, String value, String symbol) {
        return new Document("from", from)
                .append("to", to)
                .append("contractAddress", contract)
                .append("value", value)
                .append("tokenSymbol", symbol)
                .append("tokenDecimal", "18")
                .append("methodId", "0xc32e7202")
                .append("functionName", "withdrawAndUnwrap(uint256 amount, bool claim) returns (bool)");
    }
}
