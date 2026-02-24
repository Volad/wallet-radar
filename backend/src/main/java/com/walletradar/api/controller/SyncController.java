package com.walletradar.api.controller;

import com.walletradar.api.dto.SyncRefreshRequest;
import com.walletradar.api.dto.SyncRefreshResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /sync/refresh (T-023). Incremental sync trigger — stub until T-010.
 */
@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    @PostMapping("/refresh")
    public ResponseEntity<SyncRefreshResponse> refresh(@RequestBody SyncRefreshRequest request) {
        return ResponseEntity.accepted().body(new SyncRefreshResponse("Incremental sync triggered"));
    }
}
