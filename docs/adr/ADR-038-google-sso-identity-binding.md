# ADR-038 — Google SSO: Identity Binding via OAuth2 Authorization Code + JWT Cookie

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-06-27 |
| Supersedes | A-50 (no-auth) in `architecture-decisions.md` |
| Updates | NG-07 in `01-product-context.md` |

## Context

WalletRadar operated without any user authentication (ADR-A-50, NG-07): sessions were identified by a client-generated UUID stored in `localStorage`. This design precluded multi-device access, session recovery, and secure data isolation.

The need arose to:
1. Allow users to authenticate via Google so a session can be restored on a different device or browser.
2. Enforce that only the authenticated owner can access or modify their session data.

## Decisions

### 1. OAuth2 Authorization Code Flow via Spring Security

Full server-side `Authorization Code` flow using `spring-boot-starter-oauth2-client` (reactive). The frontend redirects to `/oauth2/authorization/google`; the callback `/login/oauth2/code/google` is handled entirely by the backend. No Google Identity Services (GIS) or PKCE required for first iteration.

Rejected: GIS `credential` token approach (requires separate verifier library, more frontend complexity).

### 2. JWT in HttpOnly Cookie (`wr_auth`)

After a successful OAuth2 login, `OAuthSuccessHandler` mints an HS256 JWT (Nimbus) containing:
- `sub` — Google's stable user identifier (`idpId`)
- `sessionId` — canonical `UserSession._id`
- `provider`, `email`, `name`, `picture`

The JWT is stored in an `HttpOnly; Secure; SameSite=Lax` cookie named `wr_auth`. Subsequent API calls carry it automatically via `withCredentials`.

Rejected: `localStorage` tokens (XSS-accessible), `Authorization` header (requires explicit header injection for every request).

### 3. Full Ownership Enforcement

All `/api/v1/sessions/{sessionId}/**` endpoints require an authenticated JWT whose `sessionId` claim matches the path parameter. This is enforced by `SessionOwnershipAuthorizationManager` (stateless — no DB lookup on each request).

`POST /api/v1/sessions` (top-level) requires authentication but not ownership-check at the security layer.

`GET /api/v1/auth/me` is public (returns `{authenticated:false}` when no valid cookie).

### 4. `walletradar.auth.enabled` Feature Flag

The entire enforcement is behind `walletradar.auth.enabled` (default `false`). When `false`, a permit-all `SecurityWebFilterChain` is installed — backward-compatible for local development without Google credentials.

### 5. Identity Binding Model

`UserSession` gains an embedded `IdentityBinding` object:

```
{
  provider: "GOOGLE",
  subject: "<google-sub>",
  email, emailVerified, displayName, pictureUrl, linkedAt
}
```

A **unique sparse** index `{identity.provider, identity.subject}` ensures one canonical session per Google identity. Sessions without `identity` (anonymous) are excluded from the index.

### 6. New Session on First Login; Manual Rebind

When the Google identity is not yet linked to any session, a new **empty** `UserSession` (no wallets, no `accountingUniverseId`) is created. The frontend detects the empty state and shows the settings wizard.

Migration of an existing anonymous session to an authenticated one is a manual MongoDB operation (not automated), performed on explicit operator request.

## Consequences

- **Breaking**: all `/api/v1/sessions/**` endpoints now require authentication when `enabled=true`. Existing anonymous sessions remain in the DB but are inaccessible from the SPA without going through Google login and manual rebind.
- **Frontend**: `AuthService.checkAuth()` is called at app startup. If not authenticated, `SessionStorageService` is cleared (stale UUID ignored). On authenticated response, `sessionId` from `/auth/me` becomes the source of truth.
- **Infrastructure**: nginx for `wr.allatone.xyz` proxies `/oauth2/` and `/login/oauth2/` to the backend container in addition to `/api/`.
- **Extensible**: adding a second provider (GitHub, etc.) means adding a new `IdentityProvider` enum value, a new Spring OAuth2 client registration, and extending `OAuthSuccessHandler`.

## Related

- `backend/src/main/java/com/walletradar/security/SecurityConfig.java`
- `backend/src/main/java/com/walletradar/security/OAuthSuccessHandler.java`
- `backend/src/main/java/com/walletradar/security/SessionOwnershipAuthorizationManager.java`
- `backend/src/main/java/com/walletradar/auth/AuthTokenService.java`
- `backend/src/main/java/com/walletradar/api/controller/AuthController.java`
- `frontend/src/app/core/services/auth.service.ts`
- `frontend/src/app/features/settings/sections/account-settings-section.component.ts`
