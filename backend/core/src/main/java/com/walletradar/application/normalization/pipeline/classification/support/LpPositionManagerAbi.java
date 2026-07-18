package com.walletradar.application.normalization.pipeline.classification.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;

/**
 * Shared, single-source-of-truth ABI vocabulary for concentrated-liquidity / staking
 * position-manager interactions, loaded once from {@code classpath:lp-position-manager-abi.json}.
 *
 * <p>These method selectors and event topics are de-facto ABI standards — the Uniswap V3
 * {@code NonfungiblePositionManager} and V4 {@code PositionManager} ABIs, reused verbatim by
 * PancakeSwap V3, SushiSwap V3, Angle, Aura and MasterChef forks — plus the ERC-721 transfer
 * topic. They are cross-protocol (not owned by any single {@code protocols/*.json} resource) and
 * are consumed by static classifier utilities, so they live in one shared config file rather than
 * duplicated per class.</p>
 *
 * <p>Previously {@link LpPositionLifecycleSupport} and {@link LpPositionCorrelationSupport} each
 * re-declared the same constants; a divergence would split an LP entry/exit correlation id and
 * corrupt AVCO. Centralising here removes that drift risk. The class is loaded eagerly and
 * fail-fast (missing/malformed config throws at first access) so it works identically under Spring
 * and in plain unit tests without any binding step.</p>
 */
public final class LpPositionManagerAbi {

    private static final String RESOURCE = "lp-position-manager-abi.json";

    private static final Definition DEFINITION = load();

    public static final String MINT_SELECTOR = selector("mint");
    public static final String STRUCT_MINT_SELECTOR = selector("structMint");
    public static final String INCREASE_LIQUIDITY_SELECTOR = selector("increaseLiquidity");
    public static final String MASTER_CHEF_INCREASE_LIQUIDITY_SELECTOR = selector("masterChefIncreaseLiquidity");
    public static final String DECREASE_LIQUIDITY_SELECTOR = selector("decreaseLiquidity");
    public static final String COLLECT_SELECTOR = selector("collect");
    public static final String BURN_SELECTOR = selector("burn");
    public static final String MULTICALL_SELECTOR = selector("multicall");
    public static final String MODIFY_LIQUIDITIES_SELECTOR = selector("modifyLiquidities");
    public static final String STAKE_DEPOSIT_SELECTOR = selector("stakeDeposit");
    public static final String STAKE_WITHDRAW_SELECTOR = selector("stakeWithdraw");
    public static final String AURA_WITHDRAW_AND_UNWRAP_SELECTOR = selector("auraWithdrawAndUnwrap");
    public static final String SAFE_TRANSFER_FROM_SELECTOR = selector("safeTransferFrom");
    public static final String SAFE_TRANSFER_FROM_WITH_DATA_SELECTOR = selector("safeTransferFromWithData");
    public static final String ROUTE_SINGLE_VAULT_SELECTOR = selector("routeSingleVault");

    public static final String ERC721_TRANSFER_TOPIC = topic("erc721Transfer");
    public static final String MODIFY_LIQUIDITY_TOPIC = topic("modifyLiquidity");

    private LpPositionManagerAbi() {
    }

    private static String selector(String key) {
        return require(DEFINITION.methodSelectors(), key, "methodSelectors");
    }

    private static String topic(String key) {
        return require(DEFINITION.eventTopics(), key, "eventTopics");
    }

    private static String require(Map<String, String> group, String key, String groupName) {
        String value = group == null ? null : group.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing '" + groupName + "." + key + "' in " + RESOURCE);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static Definition load() {
        try (InputStream inputStream =
                     LpPositionManagerAbi.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing classpath resource: " + RESOURCE);
            }
            Definition definition = new ObjectMapper().readValue(inputStream, Definition.class);
            if (definition == null
                    || definition.methodSelectors() == null
                    || definition.eventTopics() == null) {
                throw new IllegalStateException("Malformed " + RESOURCE);
            }
            return definition;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load " + RESOURCE, ex);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Definition(
            Map<String, String> methodSelectors,
            Map<String, String> eventTopics
    ) {
    }
}
