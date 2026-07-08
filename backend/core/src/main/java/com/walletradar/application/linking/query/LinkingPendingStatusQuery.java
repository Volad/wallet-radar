package com.walletradar.application.linking.query;

public interface LinkingPendingStatusQuery {

    boolean hasPendingLinking(String sessionId);
}
