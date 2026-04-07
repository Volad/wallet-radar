package com.walletradar.ingestion.job.backfill;

@FunctionalInterface
public interface BackfillProgressCallback {
    void reportProgress(int progressPct, long lastBlockSynced);
}
