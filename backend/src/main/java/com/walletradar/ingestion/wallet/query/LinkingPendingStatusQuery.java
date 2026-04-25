package com.walletradar.ingestion.wallet.query;

public interface LinkingPendingStatusQuery {

    boolean hasPendingLinking(String sessionId);
}
