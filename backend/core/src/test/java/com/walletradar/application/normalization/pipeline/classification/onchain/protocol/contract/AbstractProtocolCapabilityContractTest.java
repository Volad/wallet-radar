package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.contract;

import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test-kit stub (Track B3) for protocol capability contracts. Subclasses provide golden fixtures
 * and wire a {@link ProtocolSemanticClassifier}; full terminal-type assertions land with A5 wiring.
 *
 * <p>Guide: {@code docs/reference/extensibility/add-a-protocol.md#contract-tests-b3}
 */
public abstract class AbstractProtocolCapabilityContractTest {

  protected abstract String protocolKey();

  protected abstract ProtocolSemanticClassifier classifier();

  protected abstract List<ProtocolCapabilityFixture> fixtures();

  @Test
  void protocolKey_isStable() {
    assertFalse(protocolKey().isBlank(), "protocolKey must be set");
  }

  @Test
  void fixtures_declareAllowedTerminalTypes() {
    for (ProtocolCapabilityFixture fixture : fixtures()) {
      assertFalse(fixture.allowedTerminalTypes().isEmpty(),
          fixture.name() + " must declare allowed terminal types");
      assertFalse(fixture.disallowedFallbacks().isEmpty(),
          fixture.name() + " must declare at least one disallowed fallback guard");
    }
  }

  @Test
  void semanticHints_matchProtocolKey() {
    for (ProtocolCapabilityFixture fixture : fixtures()) {
      List<ProtocolSemanticHint> hints = classifier().classify(fixture.context());
      for (ProtocolSemanticHint hint : hints) {
        assertTrue(hint.protocolKey().equals(protocolKey()),
            fixture.name() + " hint protocolKey must match " + protocolKey());
      }
    }
  }

  /**
   * Golden fixture: synthetic context + expected type bounds from the approved rule doc.
   */
  public record ProtocolCapabilityFixture(
      String name,
      ProtocolSemanticContext context,
      Set<NormalizedTransactionType> allowedTerminalTypes,
      Set<String> disallowedFallbacks
  ) {
  }
}
