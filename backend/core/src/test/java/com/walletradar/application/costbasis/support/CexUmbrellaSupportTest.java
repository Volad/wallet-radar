package com.walletradar.application.costbasis.support;

import com.walletradar.domain.session.UserSession;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests for {@link CexUmbrellaSupport} — verifies that the generic
 * venue-neutral helper produces results identical to the venue-specific
 * {@link BybitUmbrellaSupport} and {@link DzengiUmbrellaSupport} for their respective inputs.
 */
class CexUmbrellaSupportTest {

    // ---- enabledCexAccountRefs ----

    @Test
    void enabledCexAccountRefsSkipsDisabledIntegrations_Bybit() {
        UserSession session = new UserSession();
        UserSession.SessionIntegration enabled = new UserSession.SessionIntegration();
        enabled.setStatus(UserSession.IntegrationStatus.READY);
        enabled.setAccountRef("BYBIT:33625378");
        UserSession.SessionIntegration disabled = new UserSession.SessionIntegration();
        disabled.setStatus(UserSession.IntegrationStatus.DISABLED);
        disabled.setAccountRef("BYBIT:99999999");
        session.setIntegrations(List.of(enabled, disabled));

        assertThat(CexUmbrellaSupport.enabledCexAccountRefs(session)).containsExactly("bybit:33625378");
    }

    @Test
    void enabledCexAccountRefsSkipsDisabledIntegrations_Dzengi() {
        UserSession session = new UserSession();
        UserSession.SessionIntegration enabled = new UserSession.SessionIntegration();
        enabled.setStatus(UserSession.IntegrationStatus.READY);
        enabled.setAccountRef("DZENGI:user_abc");
        UserSession.SessionIntegration disabled = new UserSession.SessionIntegration();
        disabled.setStatus(UserSession.IntegrationStatus.DISABLED);
        disabled.setAccountRef("DZENGI:user_xyz");
        session.setIntegrations(List.of(enabled, disabled));

        assertThat(CexUmbrellaSupport.enabledCexAccountRefs(session)).containsExactly("dzengi:user_abc");
    }

    @Test
    void enabledCexAccountRefsHandlesMixedVenues() {
        UserSession session = new UserSession();
        UserSession.SessionIntegration bybit = new UserSession.SessionIntegration();
        bybit.setStatus(UserSession.IntegrationStatus.READY);
        bybit.setAccountRef("BYBIT:33625378");
        UserSession.SessionIntegration dzengi = new UserSession.SessionIntegration();
        dzengi.setStatus(UserSession.IntegrationStatus.READY);
        dzengi.setAccountRef("DZENGI:user_abc");
        UserSession.SessionIntegration onChain = new UserSession.SessionIntegration();
        onChain.setStatus(UserSession.IntegrationStatus.READY);
        onChain.setAccountRef("0x1234567890abcdef1234567890abcdef12345678");
        session.setIntegrations(List.of(bybit, dzengi, onChain));

        assertThat(CexUmbrellaSupport.enabledCexAccountRefs(session))
                .containsExactly("bybit:33625378", "dzengi:user_abc");
    }

    // ---- cexLedgerMatchesEnabledVenue ----

    @Test
    void cexLedgerMatchesEnabledVenue_BybitSubAccountMatchesUmbrella() {
        Set<String> enabled = new LinkedHashSet<>(List.of("bybit:33625378"));

        assertThat(CexUmbrellaSupport.cexLedgerMatchesEnabledVenue("BYBIT:33625378:FUND", enabled)).isTrue();
        assertThat(CexUmbrellaSupport.cexLedgerMatchesEnabledVenue("BYBIT:33625378:UTA", enabled)).isTrue();
        assertThat(CexUmbrellaSupport.cexLedgerMatchesEnabledVenue("BYBIT:33625378:EARN", enabled)).isTrue();
        assertThat(CexUmbrellaSupport.cexLedgerMatchesEnabledVenue("BYBIT:99999999:UTA", enabled)).isFalse();
    }

    @Test
    void cexLedgerMatchesEnabledVenue_DzengiExactMatch() {
        Set<String> enabled = new LinkedHashSet<>(List.of("dzengi:user_abc"));

        assertThat(CexUmbrellaSupport.cexLedgerMatchesEnabledVenue("DZENGI:user_abc", enabled)).isTrue();
        assertThat(CexUmbrellaSupport.cexLedgerMatchesEnabledVenue("DZENGI:user_xyz", enabled)).isFalse();
    }

    @Test
    void cexLedgerMatchesEnabledVenue_OnChainAddressReturnsFalse() {
        Set<String> enabled = new LinkedHashSet<>(List.of("bybit:33625378"));

        assertThat(CexUmbrellaSupport.cexLedgerMatchesEnabledVenue(
                "0x1234567890abcdef1234567890abcdef12345678", enabled
        )).isFalse();
    }

    // ---- ledgerWalletKeyForAggregation ----

    @Test
    void ledgerWalletKeyForAggregationCollapsesSubAccountSuffixes() {
        Set<String> enabled = new LinkedHashSet<>(List.of("bybit:33625378"));

        assertThat(CexUmbrellaSupport.ledgerWalletKeyForAggregation("BYBIT:33625378:UTA", enabled))
                .isEqualTo("bybit:33625378");
        assertThat(CexUmbrellaSupport.ledgerWalletKeyForAggregation("BYBIT:33625378:EARN", enabled))
                .isEqualTo("bybit:33625378");
        assertThat(CexUmbrellaSupport.ledgerWalletKeyForAggregation("BYBIT:33625378:FUND", enabled))
                .isEqualTo("bybit:33625378");
    }

    @Test
    void ledgerWalletKeyForAggregation_DzengiReturnsLowercaseRef() {
        Set<String> enabled = new LinkedHashSet<>(List.of("dzengi:user_abc"));

        assertThat(CexUmbrellaSupport.ledgerWalletKeyForAggregation("DZENGI:user_abc", enabled))
                .isEqualTo("dzengi:user_abc");
    }

    @Test
    void ledgerWalletKeyForAggregation_UnknownBybitUidNotInEnabled() {
        Set<String> enabled = new LinkedHashSet<>(List.of("bybit:33625378"));

        // Unknown UID — should not collapse, return as-is (lowercased)
        assertThat(CexUmbrellaSupport.ledgerWalletKeyForAggregation("BYBIT:99999999:UTA", enabled))
                .isEqualTo("bybit:99999999:uta");
    }

    // ---- liveQuantityForCandidates ----

    @Test
    void liveQuantityForCandidatesSumsMatchedSymbols() {
        Map<String, BigDecimal> live = Map.of(
                "ETH", new BigDecimal("1.5"),
                "WETH", new BigDecimal("0.5")
        );

        BigDecimal qty = CexUmbrellaSupport.liveQuantityForCandidates(
                live,
                CexUmbrellaSupport.priceLookupCandidates("ETH"),
                "ETH"
        );

        assertThat(qty).isEqualByComparingTo("2.0");
    }

    @Test
    void liveQuantityForCandidatesReturnsSingleSymbolDirectly() {
        Map<String, BigDecimal> live = Map.of("GOOGL", new BigDecimal("12.5"));

        BigDecimal qty = CexUmbrellaSupport.liveQuantityForCandidates(
                live,
                CexUmbrellaSupport.priceLookupCandidates("GOOGL"),
                "GOOGL"
        );

        assertThat(qty).isEqualByComparingTo("12.5");
    }

    // ---- scaleUmbrellaToLive ----

    @Test
    void scaleUmbrellaToLiveClampsWhenLedgerExceedsLive() {
        CexUmbrellaSupport.ScaledUmbrellaTotals scaled = CexUmbrellaSupport.scaleUmbrellaToLive(
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
        CexUmbrellaSupport.ScaledUmbrellaTotals scaled = CexUmbrellaSupport.scaleUmbrellaToLive(
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
        CexUmbrellaSupport.ScaledUmbrellaTotals scaled = CexUmbrellaSupport.scaleUmbrellaToLive(
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

    @Test
    void scaleUmbrellaToLiveDropsWhenLiveIsZero() {
        CexUmbrellaSupport.ScaledUmbrellaTotals scaled = CexUmbrellaSupport.scaleUmbrellaToLive(
                new BigDecimal("100"),
                new BigDecimal("80"),
                new BigDecimal("160"),
                BigDecimal.ZERO
        );

        assertThat(scaled.dropped()).isTrue();
        assertThat(scaled.quantity()).isEqualByComparingTo("0");
    }
}
