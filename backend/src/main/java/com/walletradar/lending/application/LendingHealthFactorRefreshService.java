package com.walletradar.lending.application;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.lending.persistence.LendingHealthFactorSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LendingHealthFactorRefreshService {

    private static final String AAVE_PROTOCOL_KEY = "Aave";

    private final UserSessionRepository userSessionRepository;
    private final SessionLendingQueryService lendingQueryService;
    private final LendingAaveV3HealthCollector healthCollector;
    private final LendingHealthFactorSnapshotService snapshotService;

    public RefreshResult refreshActiveBorrowGroups() {
        List<LendingAaveV3HealthCollector.ActiveBorrowGroup> groups = discoverActiveBorrowGroups();
        int saved = 0;
        int skipped = 0;
        for (LendingAaveV3HealthCollector.ActiveBorrowGroup group : groups) {
            LendingHealthFactorSnapshot snapshot = healthCollector.collect(group).orElse(null);
            if (snapshot == null) {
                skipped++;
                continue;
            }
            snapshotService.save(snapshot);
            saved++;
        }
        log.info("Lending health factor refresh complete groups={} saved={} skipped={}",
                groups.size(), saved, skipped);
        return new RefreshResult(groups.size(), saved, skipped);
    }

    private List<LendingAaveV3HealthCollector.ActiveBorrowGroup> discoverActiveBorrowGroups() {
        Map<String, LendingAaveV3HealthCollector.ActiveBorrowGroup> groups = new LinkedHashMap<>();
        for (UserSession session : userSessionRepository.findAll()) {
            lendingQueryService.findSessionLending(session.getId()).ifPresent(view -> {
                for (SessionLendingQueryService.LendingGroupView group : view.groups()) {
                    if (!"OPEN".equals(group.status())) {
                        continue;
                    }
                    if (!AAVE_PROTOCOL_KEY.equalsIgnoreCase(group.protocol())) {
                        continue;
                    }
                    if (group.borrowUsd() == null || group.borrowUsd().signum() <= 0) {
                        continue;
                    }
                    String networkId = group.networkId() == null ? "" : group.networkId().trim().toUpperCase(Locale.ROOT);
                    if (!isHealthFetchNetwork(networkId)) {
                        continue;
                    }
                    String key = String.join(":",
                            view.sessionId(),
                            AAVE_PROTOCOL_KEY,
                            networkId,
                            group.walletAddress()
                    ).toLowerCase(Locale.ROOT);
                    groups.putIfAbsent(key, new LendingAaveV3HealthCollector.ActiveBorrowGroup(
                            view.sessionId(),
                            AAVE_PROTOCOL_KEY,
                            networkId,
                            group.walletAddress()
                    ));
                }
            });
        }
        return groups.values().stream().toList();
    }

    private boolean isHealthFetchNetwork(String networkId) {
        return "BASE".equals(networkId) || "MANTLE".equals(networkId);
    }

    public record RefreshResult(int activeGroups, int saved, int skipped) {
    }
}
