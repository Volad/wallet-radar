package com.walletradar.api.controller;

import com.walletradar.api.costbasis.AssetLedgerBffMapper;
import com.walletradar.api.dto.SessionAssetLedgerResponse;
import com.walletradar.application.costbasis.application.port.AssetLedgerReadPort;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session-scoped asset ledger history and debug API.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class AssetLedgerController {

    private final AssetLedgerReadPort assetLedgerReadPort;
    private final AssetLedgerBffMapper assetLedgerBffMapper;

    @GetMapping("/{sessionId}/asset-ledger")
    public SessionAssetLedgerResponse getSessionAssetLedger(
            @PathVariable String sessionId,
            @RequestParam String familyIdentity
    ) {
        if (familyIdentity == null || familyIdentity.isBlank()) {
            throw new ApiBadRequestException("INVALID_REQUEST", "familyIdentity is required");
        }
        return assetLedgerReadPort
                .findSessionFamilyLedger(normalizedSessionIdOrThrow(sessionId), familyIdentity.trim())
                .map(assetLedgerBffMapper::toResponse)
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
    }

    private static String normalizedSessionIdOrThrow(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ApiBadRequestException("INVALID_SESSION_ID", "sessionId is required");
        }
        return sessionId.trim();
    }
}
