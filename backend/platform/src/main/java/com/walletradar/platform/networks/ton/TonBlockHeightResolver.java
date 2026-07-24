package com.walletradar.platform.networks.ton;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.BlockHeightResolver;
import com.walletradar.platform.networks.RpcException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Resolves the current masterchain seqno (block-height equivalent) for TON.
 * Registered with {@link Order}(1) so it takes priority over the EVM generic resolver for TON.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class TonBlockHeightResolver implements BlockHeightResolver {

    private final TonRpcClient rpcClient;

    @Override
    public boolean supports(NetworkId networkId) {
        return networkId == NetworkId.TON;
    }

    @Override
    public long getCurrentBlock(NetworkId networkId) {
        try {
            long seqno = rpcClient.getMasterchainSeqno();
            log.debug("TON masterchain seqno: {}", seqno);
            return seqno;
        } catch (RpcException e) {
            log.warn("TonBlockHeightResolver failed: {}", e.getMessage());
            throw e;
        }
    }
}
