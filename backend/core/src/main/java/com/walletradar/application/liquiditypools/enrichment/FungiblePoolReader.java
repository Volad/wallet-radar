package com.walletradar.application.liquiditypools.enrichment;

import com.walletradar.platform.networks.evm.abi.EvmAbiSupport;
import com.walletradar.application.liquiditypools.persistence.LpPositionSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class FungiblePoolReader implements LpPositionReader {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String GET_RESERVES_SELECTOR = "0x" + EvmAbiSupport.selector("getReserves()");
    private static final String TOTAL_SUPPLY_SELECTOR = "0x" + EvmAbiSupport.selector("totalSupply()");
    private static final String TOKEN0_SELECTOR = "0x" + EvmAbiSupport.selector("token0()");
    private static final String TOKEN1_SELECTOR = "0x" + EvmAbiSupport.selector("token1()");
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    /** Sentinel tokenId for fungible LP-token / vault positions (no NFT position id). */
    private static final String VAULT_TOKEN_ID = "vault";

    private final LpRpcSupport rpc;

    @Override
    public boolean supports(LpPositionContext context) {
        if (context == null || context.closed() || context.lpTokenContract() == null) {
            return false;
        }
        String family = context.family();
        boolean fungibleFamily = family != null
                && (family.contains("FUNGIBLE") || family.contains("CURVE") || family.contains("BALANCER"));
        // Velodrome/Aerodrome v2 gauge staked AMM LP token (or any vault-style fungible LP position):
        // routed here via the ":vault" tokenId so the two-sided pair can resolve on-chain.
        boolean vaultLpToken = VAULT_TOKEN_ID.equalsIgnoreCase(context.tokenId());
        return fungibleFamily || vaultLpToken;
    }

    @Override
    public Optional<LpPositionSnapshot> read(LpPositionContext context) {
        String network = context.networkId().name();
        String lpToken = context.lpTokenContract();

        // Resolve the AMM pair from the LP token's token0()/token1(). A Velodrome/Uniswap-V2-style
        // LP token (also Aerodrome, Solidly forks) exposes both; a plain ERC-20 (or a Curve/Balancer
        // explicit-sides context) does not, in which case we keep the legacy single-/explicit-side
        // behavior driven by nfpmContract/poolContract.
        Optional<String> pairToken0 = resolveAddress(network, lpToken, TOKEN0_SELECTOR);
        Optional<String> pairToken1 = resolveAddress(network, lpToken, TOKEN1_SELECTOR);
        boolean ammPair = pairToken0.isPresent() && pairToken1.isPresent();

        // Staked v2 gauge positions hold the LP token inside the gauge (carried as poolContract), so
        // the wallet's direct LP balance is 0 — read the staked balance from the gauge instead.
        String stakeContract = context.staked() ? context.poolContract() : null;
        BigInteger balanceRaw = BigInteger.ZERO;
        if (stakeContract != null && !stakeContract.isBlank()) {
            balanceRaw = rpc.erc20Balance(network, stakeContract, context.walletAddress()).orElse(BigInteger.ZERO);
        }
        if (balanceRaw.signum() <= 0) {
            balanceRaw = rpc.erc20Balance(network, lpToken, context.walletAddress()).orElse(BigInteger.ZERO);
        }

        BigInteger totalSupply = rpc.call(network, lpToken, TOTAL_SUPPLY_SELECTOR)
                .map(EvmAbiSupport::uintFromWord).orElse(BigInteger.ZERO);

        String token0 = ammPair ? pairToken0.get() : context.nfpmContract();
        String token1 = ammPair ? pairToken1.get() : context.poolContract();

        // For an AMM LP token the reserves live on the LP token itself; the legacy path reads them
        // from an explicit poolContract when supplied.
        String pool = ammPair ? lpToken
                : (context.poolContract() != null ? context.poolContract() : lpToken);
        String reservesHex = rpc.call(network, pool, GET_RESERVES_SELECTOR).orElse(null);

        // Legacy fungible LP path (no on-chain token0/token1): preserve the original contract that
        // required live holdings and reserves; without them there is nothing to report.
        if (!ammPair && (totalSupply.signum() <= 0 || balanceRaw.signum() <= 0 || reservesHex == null)) {
            return Optional.empty();
        }

        int decimals0 = token0 != null ? rpc.erc20Decimals(network, token0).orElse(18) : 18;
        int decimals1 = token1 != null ? rpc.erc20Decimals(network, token1).orElse(18) : 18;
        String sym0 = token0 != null ? rpc.erc20Symbol(network, token0).orElse("TOKEN0") : "TOKEN0";
        String sym1 = token1 != null ? rpc.erc20Symbol(network, token1).orElse("TOKEN1") : "TOKEN1";

        BigDecimal qty0 = BigDecimal.ZERO;
        BigDecimal qty1 = BigDecimal.ZERO;
        if (reservesHex != null && totalSupply.signum() > 0 && balanceRaw.signum() > 0) {
            BigInteger reserve0 = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(reservesHex, 0));
            BigInteger reserve1 = EvmAbiSupport.uintFromWord(EvmAbiSupport.wordAt(reservesHex, 1));
            qty0 = LpLiquidityAmountsSupport.toHuman(
                    reserve0.multiply(balanceRaw).divide(totalSupply), decimals0);
            qty1 = LpLiquidityAmountsSupport.toHuman(
                    reserve1.multiply(balanceRaw).divide(totalSupply), decimals1);
        }

        LpPositionSnapshot snapshot = GmxPositionReader.baseSnapshot(context);
        snapshot.setFamily(context.family() != null ? context.family() : "FUNGIBLE_LP");
        if (context.protocol() != null && !context.protocol().isBlank()) {
            snapshot.setProtocol(context.protocol());
        }
        snapshot.setStaked(context.staked());
        // Fungible AMM LP has no tick range — it is always "in range" while open.
        snapshot.setStatus("in_range");

        snapshot.setToken0(tokenSide(sym0, token0, qty0));
        if (ammPair || token1 != null) {
            snapshot.setToken1(tokenSide(sym1, token1, qty1));
        }
        return Optional.of(snapshot);
    }

    /** eth_call returning a non-zero address word, or empty on revert / zero / transport error. */
    private Optional<String> resolveAddress(String network, String to, String selector) {
        return rpc.call(network, to, selector)
                .map(EvmAbiSupport::addressFromWord)
                .filter(addr -> addr != null && !ZERO_ADDRESS.equals(addr));
    }

    private static LpPositionSnapshot.TokenSide tokenSide(String sym, String contract, BigDecimal qty) {
        LpPositionSnapshot.TokenSide side = new LpPositionSnapshot.TokenSide();
        side.setSym(sym);
        side.setContract(contract);
        side.setQty(qty);
        return side;
    }
}
