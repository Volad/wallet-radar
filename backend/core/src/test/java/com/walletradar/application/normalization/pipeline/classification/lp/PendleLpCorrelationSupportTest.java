package com.walletradar.application.normalization.pipeline.classification.lp;

import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PendleLpCorrelationSupportTest {

    @Test
    void eqbPendleLptMapsToSameMktIdAsPendleLpt() {
        assertThat(PendleLpCorrelationSupport.marketIdFromSymbol("eqbPENDLE-LPT"))
                .isEqualTo(PendleLpCorrelationSupport.marketIdFromSymbol("PENDLE-LPT"));
    }

    @Test
    void pnpWrapperMapsToSameMktIdAsBasePendleLpt() {
        // ADR-081 (C2): the Penpie (pnp) staking wrapper maps to the base market slug, exactly like eqb.
        assertThat(PendleLpCorrelationSupport.marketIdFromSymbol("pnpPENDLE-LPT")).isEqualTo("pendle-lpt");
        assertThat(PendleLpCorrelationSupport.marketIdFromSymbol("pnpPENDLE-LPT"))
                .isEqualTo(PendleLpCorrelationSupport.marketIdFromSymbol("PENDLE-LPT"));
    }

    @Test
    void fourSegmentKeyIsPerMarketPerWalletAndLowercasesWallet() {
        // ADR-081 (C2): entry (base PENDLE-LPT) and the Equilibria wrapped exit (eqbPENDLE-LPT) must
        // produce the SAME 4-segment key so the exit closes the entry BY LINK. The wallet segment is
        // canonicalized to lowercase.
        String entry = PendleLpCorrelationSupport.correlationIdFromMovementLegs(
                view(NetworkId.MANTLE, "0xA0DD1234"),
                List.of(RawLeg.asset("0xmarket", "PENDLE-LPT", new BigDecimal("0.33782"))));
        String wrappedExit = PendleLpCorrelationSupport.correlationIdFromMovementLegs(
                view(NetworkId.MANTLE, "0xa0dd1234"),
                List.of(RawLeg.asset("0xwrapper", "eqbPENDLE-LPT", new BigDecimal("-0.33782"))));
        assertThat(entry).isEqualTo("pendle-lp:mantle:pendle-lpt:0xa0dd1234");
        assertThat(wrappedExit).isEqualTo(entry);
    }

    @Test
    void fourSegmentKeyDisambiguatesAcrossWallets() {
        // The ADR-023 D3 symbol-only 3-segment key collapsed the same market across wallets; the new
        // wallet segment keeps distinct per-wallet positions in separate pools.
        String walletA = PendleLpCorrelationSupport.correlationIdFromMovementLegs(
                view(NetworkId.MANTLE, "0xaaaa"),
                List.of(RawLeg.asset("0xmarket", "PENDLE-LPT", BigDecimal.ONE)));
        String walletB = PendleLpCorrelationSupport.correlationIdFromMovementLegs(
                view(NetworkId.MANTLE, "0xbbbb"),
                List.of(RawLeg.asset("0xmarket", "PENDLE-LPT", BigDecimal.ONE)));
        assertThat(walletA).isNotEqualTo(walletB);
        assertThat(walletA).isEqualTo("pendle-lp:mantle:pendle-lpt:0xaaaa");
    }

    @Test
    void formatCorrelationIdDegradesToLegacyThreeSegmentWhenWalletMissing() {
        assertThat(PendleLpCorrelationSupport.formatCorrelationId(NetworkId.MANTLE, "pendle-lpt", null))
                .isEqualTo("pendle-lp:mantle:pendle-lpt");
    }

    private static OnChainRawTransactionView view(NetworkId networkId, String wallet) {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(networkId.name());
        raw.setWalletAddress(wallet);
        return OnChainRawTransactionView.wrap(raw);
    }

    @Test
    void eqbPendleLptMapsToExpectedSlug() {
        assertThat(PendleLpCorrelationSupport.marketIdFromSymbol("eqbPENDLE-LPT")).isEqualTo("pendle-lpt");
    }

    @Test
    void regularPendleLptUnchanged() {
        assertThat(PendleLpCorrelationSupport.marketIdFromSymbol("PENDLE-LPT")).isEqualTo("pendle-lpt");
    }

    @Test
    void nonPendleTokenReturnsNull() {
        assertThat(PendleLpCorrelationSupport.marketIdFromSymbol("USDC")).isNull();
        assertThat(PendleLpCorrelationSupport.marketIdFromSymbol("cmETH")).isNull();
    }

    @Test
    void eqbWithoutPendleOrLptReturnsNull() {
        assertThat(PendleLpCorrelationSupport.marketIdFromSymbol("eqbUSDC")).isNull();
    }
}
