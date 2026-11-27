# ADR-0003: Stateless JWT with a database re-check

- Status: Accepted
- Date: 2026-11-20 (records the decision taken at project start)

## Context

Clients (a future web UI and perhaps a phone) call the API with a bearer token. Server-side sessions need shared session storage or
sticky routing. A pure JWT cannot be revoked: if the owner deactivates an assistant who left, or demotes a role, the old token
would keep working until it expires.

## Decision

- Authentication: `POST /api/auth/login` checks the BCrypt password hash and returns an **HS256 JWT** (subject = user id, role claim, 8 hour
  expiry). Only HS256 is accepted; the signing secret comes from configuration and must be at least 32 bytes with 16 or more distinct byte values.
- The application is a stateless resource server (no session, no cookie, CSRF disabled, because no ambient credentials are used).
- **Every request** passes through `RevocationAuthenticationConverter`, which loads the user by id: a missing or inactive user is
  rejected with 401, and the role used for `@PreAuthorize` is the **database role**, not the one in the token.
- **Sign-out and password change** raise `app_user.token_version`; the token carries it (claim `ver`) and a mismatch is a 401, so
  they end every session of that user on every device. Tokens issued before the claim existed count as version 0.
- The bootstrap owner and users with owner-reset passwords must change the temporary password before using other API routes. This state is read from the database on each request, alongside the role and token version.
- Brute-force protection: `LoginThrottle`, 5 failures per 15 minutes per (username, address), then 429.
- Authorization is done with method security (`@RequiresCapability`, backed by `@PreAuthorize`) and in the services that carry an `Actor` (owner-only rules are
  checked again there, so they hold when a service is called from somewhere other than a controller).

## Consequences

- Deactivation, role changes, sign-out and password changes take effect on the next request. No token blacklist is needed.
- One primary-key read per request. For this load that is negligible; a short-lived cache could be added if it ever matters
  (it would bring back a small revocation delay).
- There is no refresh token: a token lasts up to 8 hours unless the user signs out (`POST /api/auth/logout`, all devices), changes the
  password or is deactivated.
- `LoginThrottle` is in memory: it resets on restart and is per instance. Acceptable for one instance; a shared store is needed before
  running several (listed in [../overview.md](../overview.md), known limits).
- Rotating the signing secret signs everybody out, which is also the emergency revocation.

## Alternatives considered

- **Server-side sessions.** Rejected: needs shared storage if the app is ever scaled, and does not suit a possible mobile client.
- **JWT only, trusting the claims.** Rejected: a removed user or changed role would stay valid for hours.
- **Token blacklist table.** Rejected: also one read per request, plus a table to maintain, and less direct than re-reading the user.
- **Opaque tokens with introspection.** Rejected: the same read cost with a custom protocol.
