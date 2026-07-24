package com.walletradar.application.normalization.pipeline.solana;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W12 golden test — regression gate that asserts the {@code protocol-registry.json} is a
 * faithful representation of every program the classifier currently recognises, and that
 * {@link SolanaProtocolPrograms#classify(String)} returns the exact
 * {@code {protocol, family, protocolKey, displayName}} the classifier hardcodes.
 *
 * <p>Run this test BEFORE changing classifier dispatch so any registry/classifier drift is
 * detected early. A failing row means the registry must be updated to match the classifier
 * (behavior-preserving direction) — never the other way.</p>
 */
class SolanaProgramRegistryGoldenTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static SolanaProgramClass require(String programId) {
        Optional<SolanaProgramClass> pc = SolanaProtocolPrograms.classify(programId);
        assertThat(pc)
                .as("Program %s must be present in protocol-registry.json", programId)
                .isPresent();
        return pc.get();
    }

    private static void assertProgram(
            String programId,
            String expectedDisplayName,
            String expectedProtocolKey,
            String expectedFamily
    ) {
        SolanaProgramClass pc = require(programId);
        assertThat(pc.displayName())
                .as("displayName for %s", programId)
                .isEqualTo(expectedDisplayName);
        assertThat(pc.protocolKey())
                .as("protocolKey for %s", programId)
                .isEqualTo(expectedProtocolKey);
        assertThat(pc.family())
                .as("family for %s", programId)
                .isEqualTo(expectedFamily);
    }

    // -----------------------------------------------------------------------
    // Golden assertions: program → (displayName, protocolKey, family)
    // These mirror the hardcoded values in SolanaTransactionClassifier before W12.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("W12-golden: Kamino Lend registry == classifier hardcoded")
    void kaminoLend() {
        assertProgram("KLend2g3cP87fffoy8q1mQqGKjrxjC8boSyAYavgmjD",
                "Kamino Lend", "kamino-lend", "LENDING");
    }

    @Test
    @DisplayName("W12-golden: Kamino Vault registry == classifier hardcoded")
    void kaminoVault() {
        assertProgram("kvauTFR8qm1dhniz6pYuBZkuene38GjkNbFxHWe4s1o",
                "Kamino Vault", "kamino-vault", "YIELD");
    }

    @Test
    @DisplayName("W12-golden: Meteora DLMM registry == classifier hardcoded")
    void meteoraDlmm() {
        assertProgram("LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo",
                "Meteora DLMM", "meteora-dlmm", "LP");
    }

    @Test
    @DisplayName("W12-golden: Meteora Vault registry == classifier hardcoded")
    void meteoraVault() {
        assertProgram("24Uqj9JCLxUeoC3hGfh5W3s9FM9uCHDS2SG3LYwBpyTi",
                "Meteora Vault", "meteora-vault", "YIELD");
    }

    @Test
    @DisplayName("W12-golden: Meteora Farm registry == classifier hardcoded")
    void meteoraFarm() {
        assertProgram("FarmuwXPWXvefWUeqFAa5w6rifLkq5X6E8bimYvrhCB1",
                "Meteora Farm", "meteora-farm", "STAKING");
    }

    @Test
    @DisplayName("W12-golden: Hawksight registry == classifier hardcoded (Meteora DLMM via Hawksight)")
    void hawksight() {
        assertProgram("FqGg2Y1FNxMiGd51Q6UETixQWkF5fB92MysbYogRJb3P",
                "Meteora DLMM (via Hawksight)", "meteora-dlmm", "LP");
    }

    @Test
    @DisplayName("W12-golden: Meteora Dynamic AMM (DAMM v1) registry == classifier hardcoded")
    void meteoraDynamicAmmV1() {
        assertProgram("Eo7WjKq67rjJQSZxS6z3YkapzY3eMj6Xy8X5EQVn5UaB",
                "Meteora Dynamic AMM", "meteora-damm", "LP");
    }

    @Test
    @DisplayName("W12-golden: Meteora DAMM v2 registry == classifier hardcoded (both emit same name)")
    void meteoraDynamicAmmV2() {
        assertProgram("cpamdpZCGKUy5JxQXB4dcpGPiikHawvSWAd6mEn1sGG",
                "Meteora Dynamic AMM", "meteora-damm", "LP");
    }

    @Test
    @DisplayName("W12-golden: Raydium CLMM registry == classifier hardcoded")
    void raydiumClmm() {
        assertProgram("CAMMCzo5YL8w4VFF8KVHrK22GGUsp5VTaW7grrKgrWqK",
                "Raydium CLMM", "raydium-clmm", "LP");
    }

    @Test
    @DisplayName("W12-golden: Raydium AMM v4 registry == classifier hardcoded")
    void raydiumAmmV4() {
        assertProgram("675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8",
                "Raydium AMM v4", "raydium-amm", "DEX");
    }

    @Test
    @DisplayName("W12-golden: Raydium CPMM registry == classifier hardcoded")
    void raydiumCpmm() {
        assertProgram("CPMMoo8L3F4NbTegBCKVNunggL7H1ZpdTHKxQB5qKP1C",
                "Raydium CPMM", "raydium-cpmm", "DEX");
    }

    @Test
    @DisplayName("W12-golden: Marinade registry == classifier hardcoded")
    void marinade() {
        assertProgram("MarBmsSgKXdrN1egZf5sqe1TMai9K1rChYNDJgjq7aD",
                "Marinade", "marinade", "STAKING");
    }

    @Test
    @DisplayName("W12-golden: Jito registry == classifier hardcoded")
    void jito() {
        assertProgram("Jito4APyf642JPZPx3hGc6WWJ8zPKtRbRs4P815Awbb",
                "Jito", "jito", "STAKING");
    }

    @Test
    @DisplayName("W12-golden: Jupiter Swap V6 registry == classifier hardcoded")
    void jupiterSwapV6() {
        assertProgram("JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4",
                "Jupiter", "jupiter", "AGGREGATOR");
    }

    @Test
    @DisplayName("W12-golden: Jupiter Swap V4 registry == classifier hardcoded")
    void jupiterSwapV4() {
        assertProgram("JUP4Fb2cqiRUcaTHdrPC8h2gNsA2ETXiPDD33WcGuJB",
                "Jupiter", "jupiter", "AGGREGATOR");
    }

    @Test
    @DisplayName("W12-golden: Jupiter RFQ Order Engine registry == classifier hardcoded")
    void jupiterRfq() {
        assertProgram("61DFfeTKM7trxYcPQCM78bJ794ddZprZpAwAnLiwTpYH",
                "Jupiter RFQ", "jupiter-rfq", "AGGREGATOR");
    }

    @Test
    @DisplayName("W12-golden: DFlow registry present with AGGREGATOR family")
    void dflow() {
        SolanaProgramClass pc = require("DF1ow4tspfHX9JwWJsAb9epbkA8hmpSEAtxXy1V27QBH");
        assertThat(pc.protocolKey()).isEqualTo("dflow");
        assertThat(pc.family()).isEqualTo("AGGREGATOR");
    }

    @Test
    @DisplayName("W12-golden: OKX DEX Router registry == classifier hardcoded")
    void okxDexRouter() {
        assertProgram("routeUGWgWzqBWFcrCfv8tritsqukccJPu3q5GPP3xS",
                "OKX", "okx", "AGGREGATOR");
    }

    @Test
    @DisplayName("W12-golden: Bubblegum (compressed NFT) present in registry")
    void bubblegum() {
        SolanaProgramClass pc = require("BGUMAp9Gq7iTEuizy4pqaxsTyUCBK68MDfK752saRPUY");
        assertThat(pc.protocolKey()).isEqualTo("solana-nft");
        // family is null for this non-DeFi program (classifier emits family=null for NFT_MINT)
        assertThat(pc.family()).isNull();
        assertThat(pc.displayName()).isEqualTo("Solana NFT");
    }

    @Test
    @DisplayName("W12-golden: all Jupiter Lend program IDs present in registry and classify as jupiter-lend")
    void jupiterLendAll() {
        assertThat(SolanaProtocolPrograms.jupiterLendProgramIds())
                .as("jupiterLendProgramIds() must be non-empty")
                .isNotEmpty();

        for (String pid : SolanaProtocolPrograms.jupiterLendProgramIds()) {
            SolanaProgramClass pc = require(pid);
            assertThat(pc.protocolKey())
                    .as("Jupiter Lend program %s must have classifier_key=jupiter-lend", pid)
                    .isEqualTo("jupiter-lend");
            assertThat(pc.family())
                    .as("Jupiter Lend program %s must have family=LENDING", pid)
                    .isEqualTo("LENDING");
        }
    }

    @Test
    @DisplayName("W12-golden: named program ID constants load from registry without error")
    void namedConstantsBootstrap() {
        assertThat(SolanaProtocolPrograms.KAMINO_LEND_ID).isEqualTo("KLend2g3cP87fffoy8q1mQqGKjrxjC8boSyAYavgmjD");
        assertThat(SolanaProtocolPrograms.KAMINO_VAULT_ID).isEqualTo("kvauTFR8qm1dhniz6pYuBZkuene38GjkNbFxHWe4s1o");
        assertThat(SolanaProtocolPrograms.METEORA_DLMM_ID).isEqualTo("LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo");
        assertThat(SolanaProtocolPrograms.METEORA_VAULT_ID).isEqualTo("24Uqj9JCLxUeoC3hGfh5W3s9FM9uCHDS2SG3LYwBpyTi");
        assertThat(SolanaProtocolPrograms.METEORA_DYNAMIC_AMM_ID).isEqualTo("Eo7WjKq67rjJQSZxS6z3YkapzY3eMj6Xy8X5EQVn5UaB");
        assertThat(SolanaProtocolPrograms.METEORA_DAMM_V2_ID).isEqualTo("cpamdpZCGKUy5JxQXB4dcpGPiikHawvSWAd6mEn1sGG");
        assertThat(SolanaProtocolPrograms.METEORA_FARM_ID).isEqualTo("FarmuwXPWXvefWUeqFAa5w6rifLkq5X6E8bimYvrhCB1");
        assertThat(SolanaProtocolPrograms.HAWKSIGHT_ID).isEqualTo("FqGg2Y1FNxMiGd51Q6UETixQWkF5fB92MysbYogRJb3P");
        assertThat(SolanaProtocolPrograms.RAYDIUM_CLMM_ID).isEqualTo("CAMMCzo5YL8w4VFF8KVHrK22GGUsp5VTaW7grrKgrWqK");
        assertThat(SolanaProtocolPrograms.RAYDIUM_AMM_V4_ID).isEqualTo("675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8");
        assertThat(SolanaProtocolPrograms.RAYDIUM_CPMM_ID).isEqualTo("CPMMoo8L3F4NbTegBCKVNunggL7H1ZpdTHKxQB5qKP1C");
        assertThat(SolanaProtocolPrograms.MARINADE_ID).isEqualTo("MarBmsSgKXdrN1egZf5sqe1TMai9K1rChYNDJgjq7aD");
        assertThat(SolanaProtocolPrograms.JITO_ID).isEqualTo("Jito4APyf642JPZPx3hGc6WWJ8zPKtRbRs4P815Awbb");
        assertThat(SolanaProtocolPrograms.JUPITER_SWAP_V6_ID).isEqualTo("JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4");
        assertThat(SolanaProtocolPrograms.JUPITER_SWAP_V4_ID).isEqualTo("JUP4Fb2cqiRUcaTHdrPC8h2gNsA2ETXiPDD33WcGuJB");
        assertThat(SolanaProtocolPrograms.JUPITER_RFQ_ID).isEqualTo("61DFfeTKM7trxYcPQCM78bJ794ddZprZpAwAnLiwTpYH");
        assertThat(SolanaProtocolPrograms.DFLOW_ID).isEqualTo("DF1ow4tspfHX9JwWJsAb9epbkA8hmpSEAtxXy1V27QBH");
        assertThat(SolanaProtocolPrograms.OKX_DEX_ROUTER_ID).isEqualTo("routeUGWgWzqBWFcrCfv8tritsqukccJPu3q5GPP3xS");
        assertThat(SolanaProtocolPrograms.BUBBLEGUM_ID).isEqualTo("BGUMAp9Gq7iTEuizy4pqaxsTyUCBK68MDfK752saRPUY");
    }
}
