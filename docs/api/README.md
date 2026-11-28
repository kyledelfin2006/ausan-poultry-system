# API contract

The contract between backend and frontend. `openapi.json` states the **shape** (fields, types, statuses, `x-roles` per operation); the pages here state the **meaning**. When they disagree the spec is right and the page is a bug.

| Document | Holds |
|---|---|
| [integration-guide.md](integration-guide.md) | Building a client: session lifecycle, money and dates, retries, role-based UI, worked example. **Start here.** |
| [rules.md](rules.md) | Behaviour the schema cannot express: stock, deliveries, finance, reports, exports, audit |
| [errors.md](errors.md) | Every stable error `code` and what the UI should do |
| [openapi.json](openapi.json) | Generated OpenAPI 3 contract; also served at `GET /v3/api-docs` (needs a token) |

`openapi.json` is generated from the code and committed. `OpenApiSnapshotTests` fails when the running application differs from it. After an intended API change:

```powershell
./mvnw test "-Dtest=OpenApiSnapshotTests" "-Dopenapi.update=true"
```

## Conventions

Base URL `http://localhost:8080`. Every endpoint except `POST /api/auth/login` and `/actuator/health` needs `Authorization: Bearer <token>`. JSON in, JSON out.

| Topic | Rule |
|---|---|
| Money | PHP, JSON numbers, at most 2 decimals. Average and unit cost: up to 4 decimals in responses. |
| Dates | `yyyy-MM-dd`, business zone `Asia/Manila`. Never in the future. Assistants may backdate 7 calendar days; owners any past date. |
| Timestamps | ISO-8601 UTC instants |
| Ids | JSON integers |
| Errors | RFC 9457 problem details with a stable `code` ([errors.md](errors.md)) |
| `PATCH` | Send only the fields to change |
| Inactive records | Lists include them (`active: false`); records are deactivated, never deleted |
| Request id | Every response has `X-Request-Id`; send your own (1-64 chars of `A-Za-z0-9._-`) or one is generated. It appears on every log line and audit entry for that call. |

**Paging.** `?page=0&size=20&sort=prop,desc` (zero-based, default size 20, max 100) returns `{ "content": [...], "page": { "size", "number", "totalElements", "totalPages" } }`. Paged: `stock/movements`, `finance/entries`, `deliveries`, `audit`. Every other list is a plain array.

## Endpoints and roles

| Area | Endpoints | OWNER | ASSISTANT |
|---|---|---|---|
| Session | `POST /api/auth/login`, `GET /api/auth/me`, `POST /api/auth/logout`, `POST /api/auth/password` | yes | yes |
| Users | `/api/users` (create, list, patch, reset password) | yes | 403 |
| Audit | `GET /api/audit` | yes | 403 |
| Catalog | `/api/products`, `/api/branches`, `/api/packages` | read and write | read only |
| Stock | `POST /api/stock/movements`, `GET balances`, `GET movements`, `GET summary` | all types, with costs | PURCHASE, DONATION, USAGE; no OPENING or ADJUSTMENT; costs only on own purchases |
| Reorder levels | `GET/PUT/DELETE /api/stock/reorder-levels` | yes | read only |
| Finance | `POST /api/finance/expenses`, `GET /api/finance/entries` | all types | expenses; reads sales and collections only |
| Deliveries | `POST`, `GET`, `GET summary`, `POST {id}/done` | yes | yes |
| Deliveries | `POST {id}/cancel`, `{id}/return`, `{id}/refund`, `GET {id}/receipt` | yes | 403 |
| Reports | `summary`, `dashboard`, `series` (+ `.csv`) | all fields | sales and collections only |
| Reports | `profit`, `profit-series`, `inventory-value` (+ `.csv`) | yes | 403 |

## Session, passwords, CORS

- **Login** returns `{ token, expiresAt, role, displayName }`. Tokens are HS256 JWTs valid 8 hours; there is no refresh. The server re-reads the user on **every** request, so deactivation, role changes, logout and password changes take effect at once.
- **`GET /api/auth/me`** returns `id, username, displayName, role, capabilities, mustChangePassword, tokenExpiresAt` from the database. Use it, not the login response, to decide what to show; drive buttons from `capabilities`, not from comparing `role`.
- **`mustChangePassword`** is `true` for the bootstrap owner and after an owner reset. Until changed, business routes answer `403`; only `me`, `logout` and `POST /api/auth/password` work.
- **Logout** (`204`) ends every session of that user on every device (a per-user `token_version` counter, not a token list). So do own password change (`204`, log in again), an owner reset (target's sessions only), and deactivation.
- **Throttling.** Five failed logins in 15 minutes per username and address, or five wrong current passwords on `POST /api/auth/password`, answer `429` with `Retry-After`. Unknown user, wrong password and inactive user give the same `401`, so usernames cannot be probed. A wrong current password is `422`, not `401`, so clients do not treat it as an expired session.
- **Password rules** (`422` with a `detail` to show under the field, never echoing the password): not blank; 10 characters minimum; 72 bytes maximum in UTF-8; not one repeated character; not the username; not a very common password; on own change, different from the current one. Usernames are trimmed, lower-cased and unique ignoring case; login ignores case.
- **CORS.** Only origins in `APP_CORS_ALLOWED_ORIGINS` (exact `scheme://host[:port]`, no wildcard) may call from a browser. Development defaults to `localhost:5173` and `localhost:3000`; production defaults to none. Methods `GET POST PATCH PUT DELETE OPTIONS`; request headers `Authorization`, `Content-Type`, `Idempotency-Key`, `X-Request-Id`; readable response headers `X-Request-Id`, `Retry-After`, `Content-Disposition`, `Location`. Credentials mode is off. A disallowed origin gets `403` with no CORS headers, which browsers report as a CORS error.
