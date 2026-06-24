package com.walletradar.lending.application;

import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.costbasis.domain.OnChainBalanceRepository;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LendingActiveMarketDiscoveryService {

    private final UserSessionRepository userSessionRepository;
    private final OnChainBalanceRepository onChainBalanceRepository;
    private final LendingReceiptIdentityService receiptIdentityService;

    public List<ActiveMarket> discover() {
        Map<String, ActiveMarket> markets = new LinkedHashMap<>();
        for (UserSession session : userSessionRepository.findAll()) {
            for (OnChainBalance balance : onChainBalanceRepository.findBySessionId(session.getId())) {
                String symbol = balance.getAssetSymbol();
                String contract = balance.getAssetContract();
                BigDecimal quantity = balance.getQuantity();
                if (!receiptIdentityService.isLendingPositionSymbol(balance.getNetworkId(), contract, symbol)) {
                    continue;
                }
                String protocol = receiptIdentityService.protocolHint(balance.getNetworkId(), contract, symbol)
                        .or(() -> LendingProtocolNameSupport.protocolFromAssetSymbol(symbol))
                        .orElse(null);
                if (protocol == null) {
                    continue;
                }
                if (quantity == null || quantity.signum() <= 0) {
                    continue;
                }
                if (contract == null || contract.startsWith("NATIVE:")) {
                    continue;
                }
                String networkId = balance.getNetworkId() == null ? null : balance.getNetworkId().name();
                if (networkId == null) {
                    continue;
                }

                String side = LendingAssetSymbolSupport.isBorrowSymbol(symbol) ? "BORROW" : "SUPPLY";
                String underlying = receiptIdentityService.underlyingSymbol(balance.getNetworkId(), contract, symbol);
                String marketKey = protocol + ":" + networkId + ":ACCOUNT-POOL";
                String key = String.join(":",
                        session.getId(),
                        protocol,
                        networkId,
                        marketKey,
                        underlying,
                        side,
                        contract
                ).toLowerCase(Locale.ROOT);
                markets.putIfAbsent(key, new ActiveMarket(
                        session.getId(),
                        protocol,
                        networkId,
                        balance.getWalletAddress(),
                        marketKey,
                        side,
                        symbol,
                        underlying,
                        contract
                ));
            }
        }
        return markets.values().stream().toList();
    }

    public record ActiveMarket(
            String sessionId,
            String protocol,
            String networkId,
            String walletAddress,
            String marketKey,
            String side,
            String assetSymbol,
            String underlyingSymbol,
            String assetContract
    ) {
    }
}
