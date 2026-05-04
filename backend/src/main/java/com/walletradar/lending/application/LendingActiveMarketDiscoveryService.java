package com.walletradar.lending.application;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LendingActiveMarketDiscoveryService {

    private final UserSessionRepository userSessionRepository;
    private final SessionLendingQueryService lendingQueryService;

    public List<ActiveMarket> discover() {
        Map<String, ActiveMarket> markets = new LinkedHashMap<>();
        for (UserSession session : userSessionRepository.findAll()) {
            lendingQueryService.findSessionLending(session.getId()).ifPresent(view -> {
                for (SessionLendingQueryService.LendingGroupView group : view.groups()) {
                    if (!"OPEN".equals(group.status())) {
                        continue;
                    }
                    for (SessionLendingQueryService.LendingPositionView position : group.positions()) {
                        if (position.quantity() == null || position.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                            continue;
                        }
                        String key = String.join(":",
                                view.sessionId(),
                                group.protocol(),
                                nullToEmpty(group.networkId()),
                                position.marketKey(),
                                position.underlyingSymbol(),
                                position.side(),
                                nullToEmpty(position.assetContract())
                        ).toLowerCase(Locale.ROOT);
                        markets.putIfAbsent(key, new ActiveMarket(
                                view.sessionId(),
                                group.protocol(),
                                group.networkId(),
                                group.walletAddress(),
                                position.marketKey(),
                                position.side(),
                                position.assetSymbol(),
                                position.underlyingSymbol(),
                                position.assetContract()
                        ));
                    }
                }
            });
        }
        return markets.values().stream()
                .filter(market -> market.protocol() != null && market.protocol().equalsIgnoreCase("Aave"))
                .filter(market -> market.networkId() != null && market.assetContract() != null)
                .filter(Objects::nonNull)
                .toList();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
