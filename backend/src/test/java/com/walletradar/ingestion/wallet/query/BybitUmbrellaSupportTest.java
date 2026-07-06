package com.walletradar.ingestion.wallet.query;

import com.walletradar.domain.session.UserSession;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BybitUmbrellaSupportTest {

    @Test
    void enabledBybitAccountRefsSkipsDisabledIntegrations() {
        UserSession session = new UserSession();
        UserSession.SessionIntegration enabled = new UserSession.SessionIntegration();
        enabled.setStatus(UserSession.IntegrationStatus.READY);
        enabled.setAccountRef("BYBIT:33625378");
        UserSession.SessionIntegration disabled = new UserSession.SessionIntegration();
        disabled.setStatus(UserSession.IntegrationStatus.DISABLED);
        disabled.setAccountRef("BYBIT:99999999");
        session.setIntegrations(List.of(enabled, disabled));

        assertThat(BybitUmbrellaSupport.enabledBybitAccountRefs(session)).containsExactly("bybit:33625378");
    }

    @Test
    void ledgerWalletKeyForAggregationCollapsesVenueSuffixes() {
        Set<String> enabled = new LinkedHashSet<>(List.of("bybit:33625378"));

        assertThat(BybitUmbrellaSupport.ledgerWalletKeyForAggregation("BYBIT:33625378:UTA", enabled))
                .isEqualTo("bybit:33625378");
        assertThat(BybitUmbrellaSupport.ledgerWalletKeyForAggregation("BYBIT:33625378:EARN", enabled))
                .isEqualTo("bybit:33625378");
        assertThat(BybitUmbrellaSupport.bybitLedgerMatchesEnabledVenue("BYBIT:33625378:FUND", enabled))
                .isTrue();
        assertThat(BybitUmbrellaSupport.bybitLedgerMatchesEnabledVenue("BYBIT:99999999:UTA", enabled))
                .isFalse();
    }

    @Test
    void liveQuantityForCandidatesSumsMatchedSymbols() {
        Map<String, BigDecimal> live = Map.of(
                "ETH", new BigDecimal("1.5"),
                "WETH", new BigDecimal("0.5")
        );

        BigDecimal qty = BybitUmbrellaSupport.liveQuantityForCandidates(
                live,
                BybitUmbrellaSupport.priceLookupCandidates("ETH"),
                "ETH"
        );

        assertThat(qty).isEqualByComparingTo("2.0");
    }

    @Test
    void scaleUmbrellaToLiveClampsWhenLedgerExceedsLive() {
        BybitUmbrellaSupport.ScaledUmbrellaTotals scaled = BybitUmbrellaSupport.scaleUmbrellaToLive(
                new BigDecimal("100"),
                new BigDecimal("80"),
                new BigDecimal("160"),
                new BigDecimal("40")
        );

        assertThat(scaled.dropped()).isFalse();
        assertThat(scaled.quantity()).isEqualByComparingTo("40");
        assertThat(scaled.coveredQuantity()).isEqualByComparingTo("32");
        assertThat(scaled.totalCostBasisUsd()).isEqualByComparingTo("64");
        assertThat(scaled.ledgerScale()).isEqualByComparingTo("0.4");
    }

    @Test
    void scaleUmbrellaToLiveInflatesQuantityWhenLedgerBelowLive() {
        BybitUmbrellaSupport.ScaledUmbrellaTotals scaled = BybitUmbrellaSupport.scaleUmbrellaToLive(
                new BigDecimal("249.82"),
                new BigDecimal("200"),
                new BigDecimal("36"),
                new BigDecimal("661.17")
        );

        assertThat(scaled.dropped()).isFalse();
        assertThat(scaled.quantity()).isEqualByComparingTo("661.17");
        assertThat(scaled.coveredQuantity()).isEqualByComparingTo("200");
        assertThat(scaled.totalCostBasisUsd()).isEqualByComparingTo("36");
        assertThat(scaled.ledgerScale()).isEqualByComparingTo("1");
    }

    @Test
    void scaleUmbrellaToLivePassesThroughWhenEqual() {
        BybitUmbrellaSupport.ScaledUmbrellaTotals scaled = BybitUmbrellaSupport.scaleUmbrellaToLive(
                new BigDecimal("100"),
                new BigDecimal("80"),
                new BigDecimal("160"),
                new BigDecimal("100")
        );

        assertThat(scaled.dropped()).isFalse();
        assertThat(scaled.quantity()).isEqualByComparingTo("100");
        assertThat(scaled.coveredQuantity()).isEqualByComparingTo("80");
        assertThat(scaled.totalCostBasisUsd()).isEqualByComparingTo("160");
        assertThat(scaled.ledgerScale()).isEqualByComparingTo("1");
    }
}
