# Observability: request ids, logs and metrics

How to find out what the running system is doing and trace one user action end to end. Security-relevant detection
guidance is in [README.md](README.md).

## 1. Request ids

Every HTTP request gets an id by `RequestIdFilter` (the first filter in the chain, so even rejected requests have one).

| Where | What |
|---|---|
| Response header `X-Request-Id` | always present; a caller-supplied safe value (1-64 of `A-Z a-z 0-9 . _ -`) is echoed, otherwise a UUID is generated |
| Log context (MDC) `requestId` | on every log line written while handling the request |
| Log context (MDC) `userId` | the authenticated user's id, once the token is accepted |
| `audit_log.request_id` and `GET /api/audit` | the id of the call that made each recorded change |

Trace one problem: take the id from the frontend (or the response header) and search the logs for it; the audit trail shows
what, if anything, it changed.

## 2. Logs

**Access log.** One line per request from logger `kyle.poultry.access`, written when the request finishes:

```
2026-11-20T10:15:00.123+08:00  INFO 1 --- [ausan] [nio-8080-exec-3] [3f0c2a52-...] kyle.poultry.access : POST /api/deliveries -> 201 in 38 ms
```

It records the HTTP method, the **path only** (never the query string, because queries can hold customer names or dates)
and the status, never request or response bodies, headers or tokens.

**Formats.**

| Profile | Format |
|---|---|
| default (development) | text; the request id appears in square brackets after the thread name (`logging.pattern.correlation`) |
| `prod` | **JSON, one object per line** (`logging.structured.format.console: logstash`), with `requestId` and `userId` as fields of the object, ready for any log shipper |

Example `prod` line (abbreviated):

```json
{"@timestamp":"2026-11-20T02:15:00.123Z","level":"INFO","logger_name":"kyle.poultry.access","message":"POST /api/deliveries -> 201 in 38 ms","requestId":"3f0c2a52-6a71-4e2e-9bd2-0c3a6a1c9a10","userId":"3"}
```

Read them with `docker compose -f compose.prod.yaml logs app` and filter with `jq`, for example
`... logs app --no-log-prefix | jq 'select(.requestId=="3f0c2a52-...")'`.

Never logged: passwords, password hashes, tokens, request bodies, query strings.

## 3. Metrics

Micrometer meters, readable at `GET /actuator/metrics/{name}` (OWNER token) in every profile that exposes `metrics`
(development does; see below for production).

| Meter | Tags | Meaning |
|---|---|---|
| `ausan.auth.logins` | `outcome` = `success`, `failure`, `throttled` | login attempts |
| `ausan.auth.logouts` | | logouts |
| `ausan.audit.entries` | `entity` (`USER`, `PRODUCT`, `BRANCH`, `PACKAGE`), `action` | audit entries written, i.e. master-data changes |
| `http.server.requests` | `method`, `uri` (template, e.g. `/api/deliveries/{id}`), `status`, `outcome` | built-in: every HTTP call, with timing |
| `jvm.*`, `process.*`, `hikaricp.*`, `system.*` | | built-in JVM, CPU and database pool health |

**Business counts come from `http.server.requests`** so they cost no code and cannot drift from the real routes. For
example, deliveries created = `http.server.requests` with `uri=/api/deliveries`, `method=POST`, `status=201`:

```
GET /actuator/metrics/http.server.requests?tag=uri:/api/deliveries&tag=method:POST&tag=status:201
```

Failed attempts show up as `status=409` (conflict, for example insufficient stock), `422` (rule violation) or `400`.

### Exposure

| Profile | Exposed | Who may read |
|---|---|---|
| default (development) | `health`, `info`, `metrics`, `prometheus` | `health` anyone; the rest need an OWNER token |
| `prod` | `health`, `info` | `health` anyone; `info` needs an OWNER token |

Production deliberately exposes nothing else, because a metrics endpoint that needs a bearer token is awkward to scrape
and an unauthenticated one would leak internals. To scrape Prometheus in production, run the management endpoints on a
separate internal port that is not published (`management.server.port=8081` and
`management.endpoints.web.exposure.include=health,info,prometheus`) and let the scraper join the compose network.
Setting `management.server.port` moves **all** actuator endpoints, including `/actuator/health`, to that port, so change
the container healthcheck in the same step.

## 4. Suggested alerts

| Signal | Query idea | Why |
|---|---|---|
| Burst of failed logins | `rate(ausan_auth_logins_total{outcome="failure"}[5m])` above a handful per minute | password guessing |
| Throttling active | `ausan_auth_logins_total{outcome="throttled"}` increasing | someone is hitting the lockout |
| Server errors | `http_server_requests_seconds_count{status=~"5.."}` increasing | bugs or database trouble |
| Unexpected master-data changes | `ausan_audit_entries_total` increasing outside working hours | an owner account may be in use by someone else |
| Slow requests | p95 of `http_server_requests_seconds` per `uri` | regressions |
| Database pool exhausted | `hikaricp_connections_pending > 0` | overload |
| Health | `/actuator/health` not `UP` | the compose healthcheck already restarts the container; alert on repeated restarts |

## 5. Where this lives in the code

| Concern | Class |
|---|---|
| request id, access log | `kyle.poultry.shared.web.RequestIdFilter` |
| user id in the log context | `identity.RevocationAuthenticationConverter` |
| login and logout counters | `identity.AuthController` |
| audit counter and request id on entries | `audit.AuditService` |
| JSON logging, actuator exposure | `application.yaml`, `application-prod.yaml` |
