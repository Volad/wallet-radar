package com.walletradar.application.backfill.job;

@FunctionalInterface
public interface BackfillProgressCallback {
    void reportProgress(int progressPct, long lastBlockSynced);
}
