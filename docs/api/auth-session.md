# Sessions, passwords and browser access

Contract for the signed-in session: who am I, log out, password rules, and what a browser frontend needs to know
(CORS, request ids). Login itself and the general conventions (errors, roles, dates) are in the main API contract; this
page adds to them.

## What changed for the frontend

| Before | Now |
|---|---|
| No way to ask "who is this token?" | `GET /api/auth/me` |
| No logout | `POST /api/auth/logout` ends the session on the server |
| Changing a password left old tokens valid | Changing or resetting a password **ends the affected user's sessions** (see below) |
| Passwords: only 10-72 characters | Extra rules, answered with **422** (see [Password rules](#password-rules)) |
| Browser calls from another origin were blocked | Allowed for configured origins (see [CORS](#cors-browser-frontends)) |

---

## `GET /api/auth/me` (any role)

Returns the caller as the **database** sees them now, so a role change or password-change requirement shows up
immediately even though the token is older. A deactivated account instead answers `401`.

```json
{ "id": 3, "username": "maria", "displayName": "Maria Santos", "role": "ASSISTANT",
  "mustChangePassword": false, "tokenExpiresAt": "2026-11-20T10:15:00Z" }
```

| Field | Notes |
|---|---|
| `role` | `OWNER` or `ASSISTANT`. Use this, not the login response, to decide what to show: it is always current. |
| `mustChangePassword` | `true` for a bootstrap or owner-reset password. While true, authenticated business routes are limited to this profile, logout and password change; login and the health check remain public. |
| `tokenExpiresAt` | When the token used for this call stops working (ISO-8601 instant). Warn the user a few minutes before, or send them to login when it passes. |

Errors: `401` no/invalid/expired/revoked token.

Typical use: call it once on app start (and after a `401`) to restore the signed-in state and decide which screens and
buttons to show.

For a bootstrap owner or a user whose password was reset, the client should send the user directly to the password
change form when this flag is `true`. A successful `POST /api/auth/password` invalidates that token; log in again with
the new password before opening the rest of the application.

## `POST /api/auth/logout` (any role)

No body. Answers `204`. **All tokens issued to this user stop working immediately** (every device, every tab), including the
one used for this call. A second call with the same token answers `401`.

On `204`: discard the token and go to the login screen. Do not wait for the token to expire on its own.

> Why "everywhere" and not just this device: tokens are stateless, so the server remembers one counter per user
> (`token_version`) instead of a list of tokens. Raising it kills every older token. For a two-role business tool this is
> the safer trade-off; per-device logout would need a token list.

## Password changes end sessions

| Action | Whose tokens stop working |
|---|---|
| `POST /api/auth/password` (own password) | the caller's, **including the token just used**. Answers `204`; send the user to login. |
| `POST /api/users/{id}/password` (owner reset) | the target user's. The owner's own session is unaffected. |
| `POST /api/auth/logout` | the caller's |
| `PATCH /api/users/{id}` with `"active": false` | the target's (already true before this change) |

`POST /api/auth/password` is rate-limited like login: after 5 wrong current passwords it answers `429` with a `Retry-After` header (a correct password is also refused until the wait ends).

Deactivating a user (`PATCH /api/users/{id}` with `active: false`) also ends all of that user's sessions; reactivating does not bring old tokens back.

Login limits: `username` up to 50 and `password` up to 128 characters (`400` beyond that). Usernames are stored trimmed and in lower case, are unique ignoring case, and log in ignoring case; a duplicate answers `409` `CONFLICT`.

After `POST /api/auth/password` returns `204`, the next call with the old token is a `401`. Log in again with the new
password.

The initial owner is required to change the bootstrap password, and an owner reset also marks the target account for a
password change. Until the user changes it, other authenticated business API routes answer `403`; `GET /api/auth/me`,
`POST /api/auth/password` and `POST /api/auth/logout` remain available. Login and `/actuator/health` remain public. The
first-login restriction is enforced by the API, so scripts and future frontends receive the same rule.

## Password rules

Applied wherever a password is chosen: creating a user, owner reset, own change, and the first owner at startup. The
request-body check (`400`, 10-72 characters) still comes first; these rules then answer **`422`** with a `detail` the
user can act on. The detail never repeats the password.

| Rule | `detail` starts with |
|---|---|
| Not blank | `Password cannot be blank` |
| At least 10 characters | `Password must be at least 10 characters` |
| At most **72 bytes** in UTF-8 (BCrypt ignores more). Accented letters count as 2 bytes, emoji as 4. | `Password is too long` |
| Not one repeated character (`aaaaaaaaaa`) | `Password cannot be one repeated character` |
| Not the same as the username (any case) | `Password cannot be the same as the username` |
| Not a very common password (`password123`, `1234567890`, `qwertyuiop`, ...; case, spaces, `-` and `_` ignored) | `That password is too common` |
| Own change only: not equal to the current password | `The new password must differ from the current one` |

Suggested UI: show the `detail` text under the password field; do not duplicate these rules client-side beyond the
10-character minimum hint.

## Request ids

Every response (including `401`, `403`, `429` and `5xx`) has an **`X-Request-Id`** header. You may send your own
`X-Request-Id` (1-64 characters from `A-Z a-z 0-9 . _ -`); anything else is replaced by a generated id. The same id is on
every server log line for that call and in the audit trail, so quote it when reporting a problem.

Suggested UI: generate one per user action, send it, and show it in "something went wrong" dialogs.

## CORS (browser frontends)

The API answers browser cross-origin calls only for **configured origins**:

| Setting | Dev default | Production default |
|---|---|---|
| `APP_CORS_ALLOWED_ORIGINS` (comma-separated, exact `scheme://host[:port]`, no wildcard, no trailing slash) | `http://localhost:5173,http://localhost:3000` | empty: no cross-origin access |

Allowed methods: `GET POST PATCH PUT DELETE OPTIONS`. Allowed request headers: `Authorization`, `Content-Type`,
`Idempotency-Key`, `X-Request-Id`. Readable response headers: `X-Request-Id`, `Retry-After`, `Content-Disposition`,
`Location`. Credentials mode is **off**: send the token in the `Authorization` header, never rely on cookies.

A call from an origin that is not allowed answers `403` with no CORS headers; the browser then reports a CORS error
rather than the real response. If the frontend is served from the same origin as the API (for example one reverse proxy
serving both), no CORS settings are needed at all.

Vite dev server note: either call the API directly (origin `http://localhost:5173` is allowed by default) or use Vite's
`server.proxy` to forward `/api` to `http://localhost:8080`, which makes the browser see one origin.
