package com.walletradar.platform.networks.ton;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.BlockTimestampResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * TON block-timestamp resolver.
 *
 * <p>TON Center v3 does not provide a convenient per-seqno timestamp lookup in the free tier.
 * TON transactions carry their own {@code now} unix-timestamp field, so per-transaction
 * timestamps are populated directly in the {@link TonNetworkAdapter} payload.
 * This resolver returns {@link Instant#EPOCH} as a safe fallback; the actual timestamp
 * is always read from {@code rawData.transaction.now} during normalization.</p>
 *
 * <p>Registered with {@link Order}(1) so it takes priority over the EVM generic resolver.</p>
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class TonBlockTimestampResolver implements BlockTimestampResolver {

    @Override
    public boolean supports(NetworkId networkId) {
        return networkId == NetworkId.TON;
    }

    @Override
    public Instant getBlockTimestamp(NetworkId networkId, long blockNumber) {
        // Per-transaction timestamps come from rawData.transaction.now directly.
        return Instant.EPOCH;
    }
}
