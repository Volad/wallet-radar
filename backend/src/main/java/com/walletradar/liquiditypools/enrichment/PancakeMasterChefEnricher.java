package com.walletradar.liquiditypools.enrichment;

import com.walletradar.ingestion.adapter.evm.abi.EvmAbiSupport;
import com.walletradar.liquiditypools.persistence.LpPositionSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads pending CAKE farming rewards from PancakeSwap MasterChef V3 for staked LP positions.
 *
 * When a PancakeSwap V3 NFT position is staked in MasterChef, the standard NFPM
 * {@code positions()} call still returns swap-fee accruals (tokensOwed), but the
 * CAKE farming rewards are tracked separately in MasterChef and are missed by
 * {@link ConcentratedLiquidityReader}.
 *
 * This enricher calls {@code pendingCake(uint256 tokenId)} on the network-specific
 * MasterChef V3 contract and merges the result into {@code unclaimedFeesByToken}
 * so that CAKE rewards appear in the Fees & Rewards card and are included in APR.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PancakeMasterChefEnricher implements LpSnapshotEnricher {

    /** pendingCake(uint256 tokenId) → uint256 reward */
    private static final String PENDING_CAKE_SELECTOR =
            "0x" + EvmAbiSupport.selector("pendingCake(uint256)");

    private static final int CAKE_DECIMALS = 18;
    private static final String CAKE_SYMBOL = "CAKE";

    /**
     * PancakeSwap MasterChef V3 contract addresses by network ID.
     * Source: https://docs.pancakeswap.finance/developers/smart-contracts/pancakeswap-exchange/v3-contracts
     */
    private static final Map<String, String> MASTER_CHEF_BY_NETWORK = Map.of(
            "BSC",      "0x556B9306565093C855AEA9AE92A594704c2Cd59e",
            "ETHEREUM", "0xe9c7f3196ab8c09f6616365e8873daeb207c0391",
            "ARBITRUM", "0x5e09acf80c0296740ec5d6f643005a4ef8dcaa75",
            "BASE",     "0xC6A2Db661D5a5690172d8eB0a7DEA2d3008665A3",
            "ZKSYNC",   "0x825d989F5258B61e8a5E7b1bC2b8fFfBc57b8cC8",
            "LINEA",    "0x22E2f236065B780FA33EC8C4E58b99ebc8B55c57"
    );

    private final LpRpcSupport rpc;

    @Override
    public boolean supports(LpPositionContext context) {
        return context.staked()
                && context.tokenId() != null
                && isPancakeSwap(context.protocol())
                && masterChefAddress(context) != null;
    }

    @Override
    public void enrich(LpPositionContext context, LpPositionSnapshot snapshot) {
        String masterChef = masterChefAddress(context);
        if (masterChef == null) {
            return;
        }
        String network = networkName(context);
        try {
            BigInteger tokenId = new BigInteger(context.tokenId());
            String data = PENDING_CAKE_SELECTOR + EvmAbiSupport.encodeUint256(tokenId);
            String result = rpc.call(network, masterChef, data).orElse(null);
            if (result == null) {
                log.debug("PancakeMasterChef: no response corrId={} network={}", context.correlationId(), network);
                return;
            }
            BigInteger pendingWei = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(result, 0));
            if (pendingWei == null || pendingWei.signum() <= 0) {
                return;
            }
            BigDecimal pendingCake = new BigDecimal(pendingWei).scaleByPowerOfTen(-CAKE_DECIMALS);
            Map<String, BigDecimal> fees = snapshot.getUnclaimedFeesByToken();
            if (fees == null) {
                fees = new LinkedHashMap<>();
                snapshot.setUnclaimedFeesByToken(fees);
            }
            fees.merge(CAKE_SYMBOL, pendingCake, BigDecimal::add);
            log.debug("PancakeMasterChef: pendingCake={} corrId={}", pendingCake.toPlainString(), context.correlationId());
        } catch (NumberFormatException nfe) {
            log.debug("PancakeMasterChef: non-numeric tokenId={} corrId={}", context.tokenId(), context.correlationId());
        } catch (Exception e) {
            log.warn("PancakeMasterChef enrichment failed corrId={} network={} error={}",
                    context.correlationId(), network, e.toString());
        }
    }

    private static boolean isPancakeSwap(String protocol) {
        return protocol != null && protocol.toLowerCase(Locale.ROOT).contains("pancake");
    }

    private static String networkName(LpPositionContext context) {
        return context.networkId() != null ? context.networkId().name() : "";
    }

    private static String masterChefAddress(LpPositionContext context) {
        return MASTER_CHEF_BY_NETWORK.get(networkName(context));
    }
}
