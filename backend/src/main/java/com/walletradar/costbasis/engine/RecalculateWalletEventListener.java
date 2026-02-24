package com.walletradar.costbasis.engine;

import com.walletradar.domain.RecalculateWalletRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for RecalculateWalletRequestEvent (e.g. from backfill); runs AvcoEngine.recalculateForWallet.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecalculateWalletEventListener {

    private final AvcoEngine avcoEngine;

    @EventListener
    public void onRecalculateWalletRequest(RecalculateWalletRequestEvent event) {
        avcoEngine.recalculateForWallet(event.walletAddress());
    }
}
