# Platform security (`platform.security`)

## Purpose

Spring Security wiring for the monolith: OAuth success handling, cookie bearer token conversion, session ownership authorization, and HTTP security filter chain.

## Key packages

| Package | Responsibility |
|---------|----------------|
| `platform.security` | `SecurityConfig`, `OAuthSuccessHandler`, `CookieBearerTokenConverter`, `SessionOwnershipAuthorizationManager` |

## Allowed dependencies

- Spring Security
- Session/domain identifiers for ownership checks

## Extension seams

Authorization managers and token converters — extend here when adding new API surfaces.
