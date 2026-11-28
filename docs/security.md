# Security

What is protected, who the adversaries are, which controls exist and the test that proves each, and what is knowingly **not** covered. Update it in the same change as the code.

## 1. Assets and actors

Assets: the financial and stock ledgers and deliveries (must be accurate and tamper-evident); BCrypt hashes and the JWT signing secret (`APP_SECURITY_JWT_SECRET`, whoever holds it can mint tokens); customer names and destinations; the append-only audit trail; availability.

| Actor | Trust | Can |
|---|---|---|
| Anonymous client | none | `POST /api/auth/login` and `/actuator/health` only |
| ASSISTANT | low | record purchases, usage, expenses, deliveries (create, done); read catalog, stock, sales and collections (no cost or profit) |
| OWNER | full | everything: users, prices, cancellations, refunds, profit, audit trail |
| Operator of the host | full, outside the app | owns the database, `.env` and backups; not defended against here |
| Malicious website visited by a signed-in user | none | can make the browser send requests; see browser access |

## 2. Where decisions are made

```mermaid
flowchart LR
    B[Client] -- HTTP(S) --> F[RequestIdFilter<br/>X-Request-Id, access log]
    F --> S[Spring Security<br/>CORS, headers, JWT signature + expiry]
    S --> C[RevocationAuthenticationConverter<br/>user exists, active, token version, role from DB]
    C --> M[Controller<br/>@RequiresCapability]
    M --> V[Service<br/>data-dependent rules]
    V --> D[(PostgreSQL)]
    V --> A[AuditApi<br/>same transaction]
    A --> D
```

Identity is trusted only at the converter: signature and expiry, **then** a database read on every request. The role inside a token is never trusted; the converter overwrites it with the database value, so a demotion or deactivation applies at once. Permission is checked twice: a URL rule in `SecurityConfig` and `@RequiresCapability` on each controller. Both name a `Capability`, the single place that says which role may do what. Some rules live in services because they depend on data (stock `OPENING` and `ADJUSTMENT` are owner-only).

## 3. Threats and mitigations

| Threat | Mitigation | Proven by |
|---|---|---|
| Password guessing | BCrypt; 5 attempts per username and direct peer address in 15 minutes, then `429`, counted atomically so parallel guesses cannot exceed it; length-limited input; fixed-size keys; a full throttle table evicts unblocked keys first; own-password change is throttled the same way | `AuthSessionTests` |
| Weak passwords | `PasswordPolicy`: 10+ characters, 72-byte cap, blocklist, not the username, not repeated | `PasswordPolicyTests` |
| Stolen token used after logout | 8-hour lifetime; logout and password change or reset bump `token_version`, killing all of that user's tokens; deactivation checked per request | `AuthSessionTests` |
| Forged token | HS256 pinned; the secret must be 32+ bytes with 16+ distinct byte values or startup fails | `SecurityPropertiesTests`, `ApiSecurityTests` |
| Editing history | Ledgers are append-only; corrections are reversals; `audit_log` rejects `UPDATE`, `DELETE`, `TRUNCATE` in the database | `AuditTrailTests`, ledger tests |
| Double submit | `Idempotency-Key` on delivery creation | `DeliveryIdempotencyTests` |
| Repudiation | Audit trail: actor, action, changed fields and request id, in the change's transaction | `AuditTrailTests` |
| Data exposed to the wrong role | Method-level checks; assistants never receive cost fields; valuation, profit, receipts, audit and actuator are owner-only | `SecurityMatrixTests` (every endpoint x anonymous, assistant, owner) |
| Leaky errors and logs | Fixed messages for database errors; `prod` hides stack traces; identical login failures; access log holds method, path and status only | `ApiSecurityTests` |
| Cross-site calls | Bearer tokens only, no cookies, so no CSRF surface; CORS deny-by-default; wildcard origins refused at startup | `SecurityHeadersAndCorsTests` |
| Clickjacking, sniffing | CSP `default-src 'none'; frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`, HSTS on HTTPS | `SecurityHeadersAndCorsTests` |
| Assistant doing owner actions | `@RequiresCapability` everywhere; the matrix test fails if an endpoint has no declared access | `SecurityMatrixTests`, `CapabilityTests` |
| Removing the last owner | Services refuse to deactivate or demote the last active owner, or the caller themselves | `UserAdministrationTests` |
| Exposed operational endpoints | `/actuator/**` is owner-only except health; production exposes only `health` and `info` | `SecurityHeadersAndCorsTests` |

## 4. Controls

- **Authentication.** JWT HS256 with claims `sub`, `username`, `role` (informational), `ver`, `iat`, `exp`. A bootstrap or owner-reset password sets `must_change_password`; until changed, business routes allow only profile, logout and password change.
- **Authorisation.** Two roles. Giving an assistant a capability is one edit in `Capability.grantedTo`; the matrix test, the OpenAPI snapshot (`x-roles`) and `CapabilityTests` fail until it is made on purpose.
- **Browser access.** No cookies, so CSRF is off on purpose. Keep the token in memory (or `sessionStorage`), never `localStorage` or a URL, and give the UI its own CSP. Set `APP_CORS_ALLOWED_ORIGINS` to the UI's exact origin before the first production deploy; the `prod` default is none. If a cookie is ever introduced, revisit CSRF and credentials first.
- **Transport.** Production Compose is direct access with `server.forward-headers-strategy=none`, so throttling uses the socket peer and ignores `X-Forwarded-*`. The app's plain-HTTP port is published on all interfaces by default; set `APP_BIND=127.0.0.1` behind a same-host TLS proxy, otherwise treat it as LAN-only. Terminate TLS before internet exposure. Before adding a proxy, configure and verify a trusted address chain and block direct access to the app port.
- **Data.** Passwords are BCrypt hashes, never returned, logged or audited. The database port is not published in production. Backups hold everything: protect and encrypt them.

## 5. Observability

- **Request ids.** `RequestIdFilter` runs first, so even rejected requests get one. A safe caller-supplied `X-Request-Id` is echoed, otherwise a UUID is generated. It is in the response header, every log line (`requestId`, plus `userId` once authenticated) and `audit_log.request_id`. To trace a problem, take the id from the client and search the logs.
- **Logs.** One access line per request from `kyle.poultry.access` (method, path, status, duration; never query strings, bodies, headers or tokens). Development logs text; `prod` logs one JSON object per line. Read them with `docker compose -f compose.prod.yaml logs app --no-log-prefix | jq 'select(.requestId=="...")'`.
- **Metrics.** `ausan.auth.logins{outcome=success|failure|throttled}`, `ausan.auth.logouts`, `ausan.audit.entries{entity,action}`, plus the built-in `http.server.requests`, `jvm.*`, `hikaricp.*`. Business counts come from `http.server.requests` (for example `uri=/api/deliveries`, `method=POST`, `status=201`). Development exposes `health`, `info`, `metrics`, `prometheus` (all but health need an OWNER token); `prod` exposes only `health` and `info`. To scrape in production, move management to an unpublished port (`management.server.port=8081`) and update the container healthcheck, since that moves `/actuator/health` too.
- **Alerts worth setting.** Bursts of failed or throttled logins; rising 5xx; audit entries outside working hours; `hikaricp_connections_pending > 0`; repeated container restarts.

## 6. Known gaps (decide before wider exposure)

1. No multi-factor authentication and no lockout beyond the per-address throttle.
2. No global rate limit and no request-size limit beyond the server default; use a reverse proxy when public.
3. The login throttle is in memory and per instance; use a shared store before scaling out.
4. One shared signing secret; rotating it logs everyone out (the documented procedure).
5. No refresh tokens and no per-device logout.
6. Coarse roles: no per-branch restrictions; assistants read all branches' stock.
7. Reads are not audited, only changes.
8. Ledger rows are as trustworthy as database access; there is no cryptographic chaining.
9. Owner-only endpoints other than `/api/users/**` and `/api/audit/**` run method security after body validation, so an assistant sending an invalid body sees `400` instead of `403` (no data leaks).

## 7. Keeping this true

| If you change | Update |
|---|---|
| a role rule or endpoint | `SecurityMatrixTests.MATRIX` (the build fails otherwise) and the threat table |
| token contents or lifetime | sections 3 and 4, `docs/api/README.md` |
| headers, CORS or token storage | section 4 and `SecurityHeadersAndCorsTests` |
| the audit scope | `docs/api/rules.md`, `AuditTrailTests` |
