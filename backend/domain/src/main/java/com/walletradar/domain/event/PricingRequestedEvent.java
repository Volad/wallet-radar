package com.walletradar.domain.event;

/**
 * Explicit request to resume the pricing stage without routing through linking.
 */
public record PricingRequestedEvent(String sessionId, String trigger) {
}
