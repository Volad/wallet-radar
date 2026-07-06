package com.walletradar.integration.bybit;

/**
 * User-owned sub-account row from {@code /v5/user/query-sub-members}.
 */
public record BybitSubMember(
        String uid,
        String username,
        String memberType
) {
}
