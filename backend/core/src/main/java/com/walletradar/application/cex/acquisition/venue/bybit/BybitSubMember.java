package com.walletradar.application.cex.acquisition.venue.bybit;

/**
 * User-owned sub-account row from {@code /v5/user/query-sub-members}.
 */
public record BybitSubMember(
        String uid,
        String username,
        String memberType
) {
}
