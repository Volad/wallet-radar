package com.walletradar.application.liquiditypools.enrichment;

import com.walletradar.platform.networks.evm.abi.EvmAbiSupport;
import com.walletradar.application.liquiditypools.persistence.LpPositionSnapshot;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceCatalog;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceDefinition;
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
public class PancakeMasterChefEnricher implements LpSnapshotEnricher {

    /** pendingCake(uint256 tokenId) → uint256 reward */
    private static final String PENDING_CAKE_SELECTOR =
            "0x" + EvmAbiSupport.selector("pendingCake(uint256)");

    private static final int CAKE_DECIMALS = 18;
    private static final String CAKE_SYMBOL = "CAKE";
    private static final String PROTOCOL = "PancakeSwap";
    private static final String PROTOCOL_VERSION = "v3";

    private final LpRpcSupport rpc;

    /**
     * PancakeSwap MasterChef V3 reward contract by uppercase network name, loaded (Wave W8) from
     * {@code protocols/pancake.json} ({@code contractSets} keyed by network id) instead of hardcoded.
     */
    private final Map<String, String> masterChefByNetwork;

    public PancakeMasterChefEnricher(LpRpcSupport rpc, ProtocolResourceCatalog protocolResourceCatalog) {
        this.rpc = rpc;
        ProtocolResourceDefinition definition = protocolResourceCatalog.find(PROTOCOL, PROTOCOL_VERSION)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing protocols/pancake.json protocol resource for " + PROTOCOL + " " + PROTOCOL_VERSION));
        Map<String, String> byNetwork = new LinkedHashMap<>();
        definition.contractSets().forEach((network, addresses) -> {
            if (network != null && addresses != null && !addresses.isEmpty()) {
                byNetwork.put(network.toUpperCase(Locale.ROOT), addresses.getFirst());
            }
        });
        if (byNetwork.isEmpty()) {
            throw new IllegalStateException(
                    "protocols/pancake.json must define a non-empty contractSets map (network -> MasterChef V3 address)");
        }
        this.masterChefByNetwork = Map.copyOf(byNetwork);
        log.info("Loaded PancakeSwap MasterChef V3 registry: {} networks", masterChefByNetwork.size());
    }

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

    private String masterChefAddress(LpPositionContext context) {
        return masterChefByNetwork.get(networkName(context));
    }
}
