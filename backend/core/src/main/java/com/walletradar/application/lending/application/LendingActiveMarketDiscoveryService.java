package com.walletradar.application.lending.application;

import com.walletradar.application.costbasis.domain.OnChainBalance;
import com.walletradar.application.costbasis.domain.OnChainBalanceRepository;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LendingActiveMarketDiscoveryService {

    private final UserSessionRepository userSessionRepository;
    private final OnChainBalanceRepository onChainBalanceRepository;
    private final LendingReceiptIdentityService receiptIdentityService;
    private final LendingReceiptLessActiveMarketSource receiptLessActiveMarketSource;

    public List<ActiveMarket> discover() {
        List<UserSession> sessions = userSessionRepository.findAll();
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<String> sessionIds = sessions.stream()
                .map(UserSession::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (sessionIds.isEmpty()) {
            return List.of();
        }

        Map<String, List<OnChainBalance>> balancesBySession = onChainBalanceRepository.findBySessionIdIn(sessionIds)
                .stream()
                .filter(balance -> balance.getSessionId() != null && !balance.getSessionId().isBlank())
                .collect(Collectors.groupingBy(OnChainBalance::getSessionId, LinkedHashMap::new, Collectors.toList()));

        Map<String, ActiveMarket> markets = new LinkedHashMap<>();
        for (UserSession session : sessions) {
            if (session.getId() == null || session.getId().isBlank()) {
                continue;
            }
            for (OnChainBalance balance : balancesBySession.getOrDefault(session.getId(), List.of())) {
                collectActiveMarket(markets, session.getId(), balance);
            }
        }

        // Second source: receipt-less lending positions (Solana/TON) that never surface a receipt/debt
        // token in on_chain_balances, keyed identically so they dedup against the receipt-token scan.
        for (ActiveMarket market : receiptLessActiveMarketSource.discover(sessionIds)) {
            markets.putIfAbsent(marketDedupKey(market), market);
        }
        return markets.values().stream().toList();
    }

    private static String marketDedupKey(ActiveMarket market) {
        return String.join(":",
                nullToEmpty(market.sessionId()),
                nullToEmpty(market.protocol()),
                nullToEmpty(market.networkId()),
                nullToEmpty(market.marketKey()),
                nullToEmpty(market.underlyingSymbol()),
                nullToEmpty(market.side()),
                nullToEmpty(market.assetContract())
        ).toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void collectActiveMarket(Map<String, ActiveMarket> markets, String sessionId, OnChainBalance balance) {
        String symbol = balance.getAssetSymbol();
        String contract = balance.getAssetContract();
        BigDecimal quantity = balance.getQuantity();
        if (!receiptIdentityService.isLendingPositionSymbol(balance.getNetworkId(), contract, symbol)) {
            return;
        }
        String protocol = receiptIdentityService.protocolHint(balance.getNetworkId(), contract, symbol)
                .or(() -> LendingProtocolNameSupport.protocolFromAssetSymbol(symbol))
                .orElse(null);
        if (protocol == null) {
            return;
        }
        if (quantity == null || quantity.signum() <= 0) {
            return;
        }
        if (contract == null || contract.startsWith("NATIVE:")) {
            return;
        }
        String networkId = balance.getNetworkId() == null ? null : balance.getNetworkId().name();
        if (networkId == null) {
            return;
        }

        String side = LendingAssetSymbolSupport.isBorrowSymbol(symbol) ? "BORROW" : "SUPPLY";
        String underlying = receiptIdentityService.underlyingSymbol(balance.getNetworkId(), contract, symbol);
        String marketKey = protocol + ":" + networkId + ":ACCOUNT-POOL";
        String key = String.join(":",
                sessionId,
                protocol,
                networkId,
                marketKey,
                underlying,
                side,
                contract
        ).toLowerCase(Locale.ROOT);
        markets.putIfAbsent(key, new ActiveMarket(
                sessionId,
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
