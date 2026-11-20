# Auth

Authentication is a username and password exchanged for a signed JWT (HS256). The token is sent on every other request
as `Authorization: Bearer <token>`.

## `POST /api/auth/login` (public)

```json
{ "username": "owner", "password": "your-password" }
```

Both fields are required and non-blank.

`200`:

```json
{ "token": "eyJhbGciOiJIUzI1NiJ9...", "expiresAt": "2026-11-20T10:15:00Z", "role": "OWNER", "displayName": "Owner" }
```

- Tokens last 8 hours. There is no refresh endpoint: when `expiresAt` passes (or any call answers 401), log in again.
- An unknown user, a wrong password and an inactive user all answer the same `401` (`UNAUTHENTICATED`,
  `Invalid username or password`), so usernames cannot be probed.
- **Throttling:** after 5 failed attempts within 15 minutes for the same username (case-insensitive) from the same
  address, login answers `429` (`TOO_MANY_REQUESTS`) with a `Retry-After` header (seconds) until the window ends. A
  successful login resets the counter.

Errors: `400` (blank fields), `401`, `429`.

### What the server re-checks on every request

The token is not trusted blindly: on each call the server re-reads the user.

- A user who is **deactivated or deleted** gets `401` immediately, even with an unexpired token.
- A **role change** takes effect on the next request; the role claim inside an old token is ignored. The UI should read
  the role again by logging in after an administrator changes it.
- Changing a password does **not** invalidate tokens that were already issued.

## `POST /api/auth/password` (any logged-in user)

Changes your own password.

| Field | Type | Required | Limits |
|---|---|---|---|
| currentPassword | string | yes | non-blank; must match the stored password |
| newPassword | string | yes | 10-72 characters |

`204` with no body on success. Errors: `400`, `401`, `422` (`Current password is incorrect`).

A wrong current password is a `422`, not `401`, on purpose: the token is valid, and clients treat `401` as "session
expired" and log the user out.
