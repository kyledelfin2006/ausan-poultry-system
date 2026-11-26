# Pre-UI Audit: To-Do Register (Ausan Poultry System)

Machine-readable work register produced from a static audit of this repository. Every finding has an ID, a checkbox row in the master table, and a full detail table. Resolved findings remain here as audit history with their final status and evidence.

## 0. Agent instructions

1. Work from the master table (section 3). Status column values: `TODO`, `IN_PROGRESS`, `PARTIAL`, `DONE`, `BLOCKED`, `NEEDS_DECISION`, `NEEDS_VERIFICATION`.
2. `Gate` values: `BLOCKING` = must be fixed or decided before UI design and implementation; `BLOCKING-LITE` = contract-shaping, settle before UI forms and lists are built; `NON-BLOCKING` = improvement that may follow UI work; `DECISION` = needs a business or owner decision first.
3. Do not implement a finding whose `Verification` column says `NEEDS_VERIFICATION` until the named evidence has been gathered. Gather it first, then update the status and record the evidence.
4. For every fix: add or update a test that would have failed before the fix, then update `SecurityMatrixTests` and `docs/` (see section 8) where a role rule or contract changes.
5. Rules of evidence used in this audit: `CONFIRMED_FROM_CODE` means the defect is visible in source. `NEEDS_VERIFICATION` means plausible from code but not reproduced. No tests or application runs were executed during the original audit. The follow-up hardening round records its own regression evidence; SEC-8 and PERF-4 integration checks passed against PostgreSQL 17 on 2026-11-23.
6. Re-verify file and line references before editing; lines were read at audit time (2026-11-22) and may drift.

## 1. Environment (confirmed)

| Item | Value |
|---|---|
| Spring Boot | 4.1.2 (parent in pom.xml) |
| Spring Security | 7.1.x (spring-security-crypto 7.1.2 in local repository) |
| Spring Framework | 7.0.x (spring-web 7.0.10) |
| Java | 25 (enforcer requires [25,); local Temurin 25.0.2) |
| Build tool | Maven via wrapper (mvnw), min Maven 3.9.0 |
| Database | PostgreSQL 17, Flyway migrations V1 to V22 |
| API docs | springdoc 3.2.0 (api-only starter) |
| Packages | kyle.poultry: audit, catalog, delivery, finance, identity, report, shared, stock |
| Deployment | Dockerfile, compose.yaml (dev), compose.prod.yaml (prod); CI in .github/workflows/ci.yml |

## 2. Reviewed and found sound (no action)

| Area | Why it is acceptable |
|---|---|
| JWT validation | HS256 pinned, secret at least 32 bytes enforced at startup, per-request DB check of user exists, active, token version, role from DB |
| BCrypt long passwords | Spring Security 7.1.2 BCryptPasswordEncoder has no 72-byte rejection; no 500 on long passwords |
| Stock and ledger locking | Ascending product-id lock order, INSERT ON CONFLICT row, single conditional UPDATE; no deadlock cycle among create, cancel, refund |
| Idempotent delivery creation | Advisory lock plus PK backstop; comparison logic reviewed |
| CSV injection | Csv.cell neutralises leading = + - @ tab CR in text cells |
| Persistence settings | open-in-view false, ddl-auto validate |
| Audit log | Append-only triggers in V20 |
| Per-request user lookup | Accepted by ADR-0003; not logged as a finding |

## 3. Master table

| Done | ID | Skill | Severity | Gate | Verification | Title | Primary location | Status |
|---|---|---|---|---|---|---|---|---|
| [x] | SEC-1 | spring-security | High | BLOCKING | TEST_ADDED_INTEGRATION_DOCKER_UNAVAILABLE | Assistants can reconstruct profit and cost via ledger, stock and inventory endpoints | FinanceController.java; StockController.java; ReportController.java; SecurityMatrixTests.java | DONE |
| [x] | DES-2 | java-design | Medium | BLOCKING | PASSING_UNIT_REGRESSION_TESTS | No lower bound or period-close policy for ledger business dates (backdating) | BusinessDatePolicy.java; DeliveryService.java; StockLedger.java; FinanceLedger.java | DONE |
| [x] | SEC-3 | spring-security | Medium | BLOCKING | TEST_ADDED_INTEGRATION_DOCKER_UNAVAILABLE | Login throttle keyed on getRemoteAddr: lock-out DoS behind proxy or spoofable with forwarded headers | AuthController.java; compose.prod.yaml; AuthSessionTests.java; docs/security/README.md | DONE |
| [x] | PERF-1 | java-performance | Medium | BLOCKING-LITE | TEST_ADDED_INTEGRATION_DOCKER_UNAVAILABLE | N+1 on delivery list; no page-size cap (default 2000) | Delivery.java; DeliveryService.java; application.yaml; DeliveryQueryTests.java | DONE |
| [x] | DBG-2 | java-debugging | Low | BLOCKING-LITE | PASSING_UNIT_REGRESSION_TESTS | Generic DATA_CONFLICT masks duplicate-username race; error-code inconsistency | UserService.java; UserServiceUnitTests.java; ApiExceptionHandler.java | DONE |
| [x] | SEC-2 | spring-security | Medium | NON-BLOCKING | PASSING_TESTS | Unbounded login username and password retained as throttle keys (pre-auth memory pressure) | AuthController.java:51,73; LoginThrottle.java:26,54-88 | DONE |
| [ ] | DES-1 | java-design | Medium | NON-BLOCKING | CONFIRMED_FROM_CODE | Authorization policy split across four places; refund rule duplicated | SecurityConfig.java:66-70; DeliveryService.java:279,304; FinanceLedger.java:98; StockLedger.java:71; StockOverviewService.java:157-159 | TODO |
| [ ] | DES-3 | java-design | Low | DECISION | NEEDS_VERIFICATION | DONE and CANCELLED are terminal; no return or credit path for delivered goods | Delivery.java:100-134; DeliveryService.java:306-307; docs/api/deliveries.md:6 | NEEDS_DECISION |
| [x] | CON-3 | java-concurrency | Low-Medium | NON-BLOCKING | NEEDS_VERIFICATION | Advisory-lock waiters hold pooled connections; same-key retry storm can starve Hikari | DeliveryService.java:126-131; application-prod.yaml:9-13; RevocationAuthenticationConverter.java:48 | NEEDS_VERIFICATION |
| [ ] | PERF-3 | java-performance | Low-Medium | NON-BLOCKING | NEEDS_VERIFICATION | Dashboard fan-out (about 15-20 statements) and unbounded history scan in retainedOnCancelled | DashboardService.java:68-88; FinancialEntryRepository.java:135-143; ReportService.java:179 | NEEDS_VERIFICATION |
| [x] | CON-1 | java-concurrency | Low-Medium | NON-BLOCKING | PASSING_TESTS | Throttle check-then-act race allows many parallel guesses | AuthController.java:74,92; LoginThrottle.java:64-77 | DONE |
| [x] | SEC-9 | spring-security | Low-Medium | NON-BLOCKING | PASSING_TESTS | compose.prod publishes plain-HTTP port on all interfaces | compose.prod.yaml (ports) | DONE |
| [x] | SEC-5 | spring-security | Low | NON-BLOCKING | PASSING_TESTS | Owner-only enforced only at method level: 400 before 403; no URL-level rules | SecurityConfig.java:66-70 | DONE |
| [x] | SEC-6 | spring-security | Low | NON-BLOCKING | PASSING_TESTS | Usernames not normalised (case, whitespace); inconsistent with throttle key | UserController.java:39-42; UserService.java:53-55; AuthService.java:52; V1__identity.sql | DONE |
| [x] | SEC-4 | spring-security | Low | NON-BLOCKING | PASSING_TESTS | Deactivation does not revoke tokens; reactivation within TTL revives them | UserService.java:107-109 | DONE |
| [x] | SEC-7 | spring-security | Low | NON-BLOCKING | PASSING_TESTS | Change-own-password is an unthrottled current-password oracle for a stolen token | AuthController.java:136-140; UserService.java:146-149 | DONE |
| [x] | SEC-8 | spring-security | Low | NON-BLOCKING | PASSING_TESTS | Secret-bearing record toString; first-owner password lifecycle; low-diversity secret check | SecurityProperties.java; AppUser.java; OwnerBootstrap.java; SecurityConfig.java | DONE |
| [x] | CON-2 | java-concurrency | Low | NON-BLOCKING | PASSING_TESTS | FIFO eviction drops oldest (blocked) keys first; flood clears blocks | LoginThrottle.java:84-88 | DONE |
| [x] | PERF-2 | java-performance | Low | NON-BLOCKING | PASSING_INTEGRATION_TEST | Whole-table catalog loads per line inside write transaction | DeliveryService.java:213; CatalogService.java:67-72,227-229; DeliveryReceiptService.java:69-72; StockLedger.java:161 | DONE |
| [x] | PERF-4 | java-performance | Low | NON-BLOCKING | PASSING_INTEGRATION_TEST | Confirm delivery-list indexes are selected at representative scale | V21__audit_hardening.sql; DeliveryService.java:348-349; DeliveryIndexPlanTests.java | DONE |
| [x] | PERF-5 | java-performance | Low | NON-BLOCKING | PASSING_INTEGRATION_TEST | Finance CSV export materialises all entities and several copies in memory | FinanceController.java:105-114 | DONE |
| [x] | DBG-1 | java-debugging | Low | NON-BLOCKING | PASSING_TESTS | Unchecked long multiplication when expanding package contents (related to Q-003) | DeliveryService.java:224; CatalogController.java:85 | DONE |
| [ ] | DBG-3 | java-debugging | Low | NON-BLOCKING | NEEDS_VERIFICATION | clearAutomatically detaches entities mid-transaction (latent trap) | StockBalanceRepository.java:39,68; DeliveryService.java:278-288 | NEEDS_VERIFICATION |

## 4. Finding detail tables

### SEC-1 Assistants can reconstruct profit and cost

**Resolution (DONE):** Finance ledger reads filter assistant-visible entry types at the service and repository query; stock DTOs omit average cost and hide movement costs except on the assistant's own PURCHASE rows; inventory valuation and its CSV are owner-only; assistant dashboard, summary, and series responses omit cost, expense, valuation, and profit fields. Jackson serialization tests pass for the redacted stock and report views. `SecurityMatrixTests` now covers the full HTTP authorization and returned fields, but its database-backed execution requires Docker and could not run here. Contract details are in `docs/api/finance.md`, `docs/api/stock.md`, and `docs/api/reports.md`.

| Field | Value |
|---|---|
| Skill | spring-security |
| Severity | High: stated access rule contradicted by endpoints; data exposure once staff accounts exist |
| Gate | BLOCKING |
| Location | FinanceController.entries (81-87) and entriesCsv (100-115); FinanceLedger.entries (183-189); StockController balances/history/summary (90-120); ReportController inventory-value (80) and inventory-value.csv (158); SecurityMatrixTests rows at 90-100, 114, 121 assert ANY_ROLE |
| Issue | docs/security/README.md I1 says everything showing cost or profit is owner-only, and the profit report, delivery receipt and profit CSVs are @PreAuthorize OWNER. But any ASSISTANT can read GET /api/finance/entries (types COST_OF_GOODS, SALE, with sourceId), stock movement history (unitCost = average cost at sale on DELIVERY_OUT), stock balances and summary (avgUnitCost) and inventory-value. The docs Actors row also says assistants read the ledger, so the docs conflict with themselves |
| Why a problem | Authorization is per endpoint, not per datum; same figure blocked on one route, open on four |
| Impact | Assistant computes per-delivery margin as SALE minus COST_OF_GOODS joined on sourceId, and total profit as SALE minus COST_OF_GOODS minus EXPENSE |
| Fix | Decide policy first. If assistants must not see cost: filter entry types by role inside the ledger service (exclude COST_OF_GOODS and COGS_REVERSAL), return role-specific stock DTOs without unitCost/avgUnitCost, make inventory-value owner-only. Example: add a visible-types Set parameter to FinanceApi.entries and pass EntryType.ASSISTANT_VISIBLE when not owner. If assistants may see it: correct I1 and the controller Javadoc. Update SecurityMatrixTests with field-level assertions in either case |
| UI relevance | Role-based navigation, table columns and DTO shapes change under either decision; blocks UI design |
| Acceptance | Matrix test asserts an assistant cannot obtain cost via any listed route (or docs are corrected); DTO shape documented in docs/api and openapi snapshot |

### DES-2 Backdating and no period policy

**Resolution (DONE):** `BusinessDatePolicy` now applies one rule to finance entries, manual and delivery stock movements, reversals/refunds, and delivery start dates: future dates are rejected; assistants may backdate through `today.minusDays(7)` inclusive; owners may use any past date. There is no period-close feature. `BusinessDatePolicyTests`, `FinanceLedgerUnitTests`, `StockLedgerUnitTests`, and `DeliveryServiceUnitTests` passed (86 tests across the selected suite); API documentation covers the chosen rule.

| Field | Value |
|---|---|
| Skill | java-design |
| Severity | Medium (High if month-end reporting is relied upon) |
| Gate | BLOCKING (needs business decision) |
| Location | DeliveryService.createNew 203-205 (startedAt only checked against future) and 233 (ledger date derived from startedAt); StockLedger.record 74; FinanceLedger.requireNotFuture 256-258 |
| Issue | Only not-in-the-future is enforced. startedAt and occurredOn have no lower bound; no period close |
| Why a problem | The date rule is repeated in three modules and says nothing about the past |
| Impact | Any assistant can post sale, payment, purchase or expense entries into closed months, silently changing reported or exported figures; only created_at records the truth |
| Fix | Shared period policy: assistants may post at most N days back, owner any date; optional period_close table that rejects closed months; flag backdated entries |
| UI relevance | Date pickers need min and max; forms cannot be designed without the rule |
| Acceptance | Policy documented; tests for assistant vs owner backdating; docs/api updated |

### SEC-3 Login throttle and getRemoteAddr

**Resolution (DONE):** Production explicitly sets `SERVER_FORWARD_HEADERS_STRATEGY=none`; the deployment contract requires direct access and the throttle uses the socket peer. `AuthSessionTests.forwardedForCannotChangeThrottleAddress` was added to exercise spoofed forwarded addresses, but its database-backed execution requires Docker and could not run here. Proxy deployment must wait until a trusted-proxy boundary is configured.

| Field | Value |
|---|---|
| Skill | spring-security |
| Severity | Medium |
| Gate | BLOCKING for deployment design |
| Location | AuthController.login 73; compose.prod.yaml SERVER_FORWARD_HEADERS_STRATEGY default none; docs/security/README.md 106 |
| Issue | With none behind a reverse proxy, all clients share the proxy address so the key username plus address is effectively per-username global. With framework, ForwardedHeaderFilter overrides getRemoteAddr from forwarded headers (spring-web 7.0.10 ForwardedHeaderFilter.java:394) |
| Why a problem | Five bad attempts against a known username (.env.example uses owner) lock the owner out for 15 minutes, repeatably. Under framework with a proxy that appends X-Forwarded-For, a client can choose its own key and bypass the throttle |
| Impact | Owner locked out of the UI by anonymous attacker, or brute-force protection void |
| Fix | Proxy must overwrite X-Forwarded-For. Prefer server.forward-headers-strategy native with server.tomcat.remoteip.internal-proxies set to the proxy. Add a per-username soft limit (delay or backoff) separate from the per-IP hard limit |
| Verification needed | Actual proxy XFF behaviour; which XFF entry ForwardedHeaderFilter selects |
| UI relevance | UI will sit behind a proxy or share an origin; settle topology first |

### PERF-1 Delivery list N+1 and page-size cap

**Resolution (DONE):** Delivery lines are loaded eagerly (deliberately, see DBG-3) with Hibernate batch fetching (`default_batch_fetch_size` 50); cancelled-delivery retained payments are fetched in one grouped query for the page; pageable requests are capped at 100. `DeliveryQueryTests` now checks the cap and statement counts across a cancelled-delivery page, but the database-backed execution requires Docker and could not run here. All test sources compile.

| Field | Value |
|---|---|
| Skill | java-performance |
| Severity | Medium |
| Gate | BLOCKING-LITE (page-size contract) |
| Location | Delivery.java 51-53 (EAGER ElementCollection); DeliveryService.list 345-351 and view 386-392 (retainedPayment per CANCELLED row, two sums); application.yaml has no spring.data.web.pageable.*; only AuditController caps size at 100 |
| Issue | Lines loaded by a follow-up select per delivery; retained payment queried per cancelled row; Spring Data max page size default 2000 |
| Impact | size=2000 can trigger thousands of statements; authenticated pool-saturation vector; list screens slow as page size grows |
| Fix | spring.data.web.pageable.max-page-size: 100; spring.jpa.properties.hibernate.default_batch_fetch_size: 50; replace per-row retainedPayment with one grouped query over the page ids |
| Verification needed | Hibernate statistics or SQL log counts for GET /api/deliveries?size=50 |
| UI relevance | Paging component and page-size choices code against this contract |

### DBG-2 Error-code masking

**Resolution (DONE):** `UserService` now flushes the insert and maps only the named username unique-constraint violation to `ConflictException("Username already taken")`; unrelated integrity failures continue to the existing generic handler. `UserServiceUnitTests` passed for both outcomes, and `docs/api/errors.md` documents the stable `CONFLICT` response.

| Field | Value |
|---|---|
| Skill | java-debugging |
| Severity | Low |
| Gate | BLOCKING-LITE (error-code table) |
| Location | UserService.create 53-55; ApiExceptionHandler.integrity 95-98 |
| Issue | Two concurrent creates both pass findByUsername; the loser gets DATA_CONFLICT instead of CONFLICT Username already taken. Same masking hides overflow guards (Q-003) |
| Impact | UI keyed on code shows the wrong message intermittently |
| Fix | Drop the pre-check; catch DataIntegrityViolationException on save; map by constraint name to ConflictException |
| UI relevance | Defines the error-code table the UI switches on |

### SEC-2 Unbounded login input

| Field | Value |
|---|---|
| Skill | spring-security (also java-performance) |
| Severity | Medium |
| Gate | NON-BLOCKING (fix before public exposure) |
| Location | AuthController.LoginRequest 51 (NotBlank only, no Size); key at 73; LoginThrottle 26, 54-56, 77-88 |
| Issue | Full lower-cased username kept for up to 10,000 keys for 15 minutes; raw username also sent to findByUsername; no request-size limit configured; Jackson string limit is very large |
| Impact | A few oversized failed logins can pin hundreds of MB; GC thrash or OOM under MaxRAMPercentage=75 |
| Fix | Size(max=50) on username and Size(max=128) on password; hash the throttle key (sha256 of normalised username plus address); cap body size at proxy; tighten Jackson StreamReadConstraints (verify the Jackson 3 default for this build) |
| Verification needed | Send N x 1 MB usernames to a running container; observe heap and GC |
| UI relevance | Login form maxlength should match final limits |

### DES-1 Authorization policy scattered

| Field | Value |
|---|---|
| Skill | java-design |
| Severity | Medium (maintainability) |
| Gate | NON-BLOCKING (capabilities on /me recommended before UI) |
| Location | SecurityConfig 66-70; @PreAuthorize on six controllers; service checks DeliveryService 279 and 304, FinanceLedger 98 (duplicates refund check), StockLedger 71, StockOverviewService 157-159 |
| Issue | Data-dependent permissions (OPENING, ADJUSTMENT, cancel, refund) live in services; others on controllers; refund enforced twice |
| Impact | Easy to add an endpoint that bypasses a rule; UI has no machine-readable way to know which actions to show; SEC-1 is a symptom |
| Fix | One Capability enum and policy (actor.require(Capability.CANCEL_DELIVERY)); return capability list from GET /api/auth/me |
| UI relevance | capabilities on /me is the cleanest contract for role-driven UI |

### DES-3 Terminal delivery states

| Field | Value |
|---|---|
| Skill | java-design |
| Severity | Low (documented business rule) |
| Gate | DECISION |
| Location | Delivery.java 100-134; DeliveryService.refund 306-307; docs/api/deliveries.md line 6 |
| Issue | DONE cannot be cancelled; refund requires CANCELLED. No path for post-delivery returns, rejections or recording errors |
| Impact | Workarounds via ADJUSTMENT or EXPENSE could distort the ledger |
| Fix | Confirm with business whether returns are in scope; if so add RETURNED or credit-note transition |
| Verification needed | Owner confirmation of intended process |
| UI relevance | Defines action buttons on delivery detail screen |

### CON-3 Advisory lock and pool starvation

| Field | Value |
|---|---|
| Skill | java-concurrency |
| Severity | Low-Medium |
| Gate | NON-BLOCKING (informs UI retry behaviour) |
| Location | DeliveryService.create 126-131; application-prod.yaml 9-13 (max pool 10, connection-timeout 10000); RevocationAuthenticationConverter 48 |
| Issue | pg_advisory_xact_lock blocks while holding a connection; every authenticated request also needs a connection for the converter lookup |
| Impact | About 10 concurrent identical submits fill the pool; all users see 10-second connection timeouts |
| Fix | pg_try_advisory_xact_lock and answer 409 with Retry-After; set lock_timeout; or cap per-user in-flight deliveries |
| Verification needed | Load test: 20 parallel identical Idempotency-Key requests at pool size 10 |
| Evidence (2026-11-24) | `AdvisoryLockPoolEvidenceTests` (run with `-Devidence=true`; pool 10, wait 10 s as in prod; 20 parallel same-key creates plus an unrelated `GET /api/auth/me` every 100 ms; single run per scenario). Mechanism CONFIRMED: waiters hold pooled connections while blocked (PostgreSQL showed 10 sessions waiting on the advisory lock, the thread dump showed 10 threads blocked in `DeliveryService.create` and 11 waiting for a pool connection). Impact depends on how long the first request holds the lock: normal create (fast): all 20 answered in 61-166 ms, 1 created and 19 repeats, unrelated calls p99 73 ms, 0 failures; slow first request (3 s): all wait about 3 s, one unrelated call waited 2.97 s, 0 failures; first request stuck 12 s (longer than the 10 s pool wait): 10 waiters failed with HTTP 500 at about 10.1 s and one unrelated call failed after 10 s. Before PERF-2 the fast case took 206-324 ms (after: 61-166 ms) with the same shape. Limits: server and client share one machine, single runs, a slow holder was simulated with a separate connection |
| Assessment | A real but narrow risk: it only bites when the first request holds the lock for many seconds (a stalled stock-row lock or database), not under normal retries. Severity stays Low-Medium |
| Resolution (2026-11-26) | DONE: `DeliveryService.lockKey` sets `lock_timeout` to 2.5 s for the advisory-lock statement only (cleared straight after, so stock-row locks are unchanged) and maps SQLSTATE 55P03 to `RequestInProgressException`, answered 409 `CONFLICT` with `Retry-After: 1`. `IdempotencyLockWaitTests` holds the lock on another connection: the repeat is answered 409 in about 2.5 s, and the same request succeeds once the lock is free. Frontend: on 409 with `Retry-After`, repeat the identical request with the same Idempotency-Key |
| Candidate fix (implemented) | `set local lock_timeout` of about 2-3 s before the advisory lock, mapped to a 409/503 with Retry-After, so waiters give back their connection sooner and unrelated users wait at most that long instead of failing after 10 s. Target: in the 12 s scenario, zero failed unrelated calls and no HTTP 500 for waiters |

### PERF-3 Dashboard fan-out and history scan

| Field | Value |
|---|---|
| Skill | java-performance |
| Severity | Low-Medium |
| Gate | NON-BLOCKING |
| Location | DashboardService.dashboard 68-77 and figures 88; FinancialEntryRepository.retainedOnCancelled 135-143; ReportService.summary 179 |
| Issue | Owner dashboard runs totalsByType four times for two periods; retainedOnCancelled has no lower date bound and uses a correlated exists over all history; inventoryValue and stock.summary reload balances and catalog |
| Impact | Home-screen latency degrades as the ledger grows |
| Fix | One totalsByType per period shared by summary and profit; rewrite retained query around the set of reversed sources with a partial index on SALE_REVERSAL; optional short TTL cache |
| Verification needed | EXPLAIN (ANALYZE, BUFFERS) on synthetic ledger of about 1M rows |
| Evidence (2026-11-24) | `DashboardEvidenceTests` (run with `-Devidence=true [-Devidence.sources=N]`): seeds the ledger (N deliveries x 3 entries, 10% cancelled, 40k expenses), runs ANALYZE, then counts statements, times 30 calls and runs EXPLAIN (ANALYZE, BUFFERS). **Fan-out:** one owner call runs 14 statements (13 queries plus the user lookup), an assistant call 10. The owner's 13: `totalsByType` x4, `retainedOnCancelled` x2, `cashByMethod` x2, catalog products x2, stock balances x2, reorder levels x1. **The two `retainedOnCancelled` calls have identical parameters** (both summaries use `to = today`) and only the month result is shown. **Scaling** (owner p50 / assistant p50 / one `retainedOnCancelled` / `totalsByType` for the month): 360k rows 122 / 104 / 37 / 2.2 ms; 1.0M rows 272 / 222 / 65 / 6.1 ms; 2.9M rows 680 / 657 / 258 / 19 ms. `retainedOnCancelled` plans as a parallel sequential scan of the whole ledger (about 100k of 333k rows kept per worker, hash semi join with SALE_REVERSAL rows), so its cost follows total history; two calls are about half of the owner response at 1M rows and about three quarters at 2.9M. `totalsByType` uses `ix_entry_date` (bitmap scan) and follows the month's volume only; its growth here is partly an artefact of the seed spreading all rows over 1,100 days. Limits: synthetic uniform data, one machine, server and client in one JVM, EXPLAIN uses literal dates rather than bound parameters, and Hibernate's per-query timing printed 0 ms so per-query shares come from EXPLAIN, not from the endpoint |
| Assessment | Real and growing, but mild at likely data sizes (about 120 ms at 360k rows). The dominant, history-sized cost is `retainedOnCancelled`; the fan-out itself costs little |
| Candidate fixes (not implemented, in order of value for effort) | 1. Run `retainedOnCancelled` once per dashboard (the today copy is unused and identical), no schema change, halves the dominant cost. 2. Run `totalsByType` once per period and share it between summary and profit (4 queries to 2; saves about 7 ms at 1M rows). 3. Rewrite `retainedOnCancelled` to start from the SALE_REVERSAL sources and read their payments through `ix_entry_source`, with a partial index on SALE_REVERSAL, so cost follows the number of cancelled deliveries, not history. Target: owner p95 under 200 ms at 1M rows and under 400 ms at 3M rows, with identical figures (compare against the current query on the seeded data) |

### CON-1 Throttle check-then-act race

| Field | Value |
|---|---|
| Skill | java-concurrency |
| Severity | Low-Medium |
| Gate | NON-BLOCKING |
| Location | AuthController 74 (blockedFor) vs 92 (recordFailure); LoginThrottle 64-77 |
| Issue | Each method is synchronized but the sequence is not atomic |
| Impact | One window can yield roughly the number of worker threads in guesses (Tomcat default 200) instead of 5 |
| Fix | Atomic tryAcquire that reserves the attempt under one lock before verifying; release on success |

### SEC-9 Plain HTTP port published

| Field | Value |
|---|---|
| Skill | spring-security |
| Severity | Low-Medium (deployment) |
| Gate | NON-BLOCKING (decide together with SEC-3) |
| Location | compose.prod.yaml ports "${APP_PORT:-8080}:8080" |
| Issue | App published directly on all interfaces while docs require TLS termination in front |
| Impact | Passwords and bearer tokens in clear text if used as shipped |
| Fix | Bind to 127.0.0.1 and add a TLS proxy service, or document as LAN-only |

### SEC-5 Method-level only authorization

| Field | Value |
|---|---|
| Skill | spring-security |
| Severity | Low |
| Gate | NON-BLOCKING |
| Location | SecurityConfig 66-70 (only authenticated and /actuator rules); documented as residual risk E1 |
| Issue | @PreAuthorize runs after @Valid @RequestBody resolution |
| Impact | Assistant posting invalid body to owner-only route gets 400 not 403; UI may mis-handle |
| Fix | Add requestMatchers(...).hasRole(OWNER) for fixed owner-only routes (/api/users/**, /api/audit/**, catalog writes, profit routes); keep @PreAuthorize as defence in depth |

### SEC-6 Username normalisation

| Field | Value |
|---|---|
| Skill | spring-security |
| Severity | Low |
| Gate | NON-BLOCKING (fix before UI user-management form hard-codes validation) |
| Location | UserController.CreateUserRequest 39-42; UserService.create 53-55; AuthService.login 52; V1__identity.sql plain unique; LoginThrottle.key 55 |
| Issue | Owner, owner and a space-prefixed owner can coexist; throttle lower-cases and strips but lookup is exact |
| Impact | Look-alike accounts; ambiguous audit actor_username |
| Fix | Strip and lower-case at create and login; create unique index on lower(username) after checking existing data |

### SEC-4 Deactivation without revocation

| Field | Value |
|---|---|
| Skill | spring-security |
| Severity | Low |
| Gate | NON-BLOCKING |
| Location | UserService.update 107-109 |
| Issue | setActive(false) does not call revokeTokens |
| Impact | Reactivation within the 8-hour TTL revives a token held by a former or compromised user |
| Fix | if (u.active() && !newActive) u.revokeTokens(); consider on demotion too |

### SEC-7 Unthrottled password-change oracle

| Field | Value |
|---|---|
| Skill | spring-security |
| Severity | Low |
| Gate | NON-BLOCKING |
| Location | AuthController.changePassword 136-140; UserService.changeOwnPassword 146-149 |
| Issue | Wrong current password returns 422 with no counter |
| Impact | Token holder can guess the password to reuse elsewhere |
| Fix | Reuse the throttle keyed by userId |

### SEC-8 Secret hygiene

| Field | Value |
|---|---|
| Skill | spring-security |
| Severity | Low |
| Gate | NON-BLOCKING |
| Location | SecurityProperties; AppUser; OwnerBootstrap; RevocationAuthenticationConverter; SecurityConfig; `.env.example` |
| Issue | A logged settings record could expose `jwtSecret`; the bootstrap owner password remained usable; the signing key check accepted low-diversity values |
| Impact | Stray logging leaks the signing key (token forgery); docker inspect shows owner password |
| Fix | Redact `toString`; enforce a bootstrap/reset password change before normal API use; require 16 distinct key bytes in addition to the 32-byte minimum and document CSPRNG generation |

### CON-2 Eviction clears blocks

| Field | Value |
|---|---|
| Skill | java-concurrency |
| Severity | Low |
| Gate | NON-BLOCKING |
| Location | LoginThrottle 84-88 |
| Issue | MAX_KEYS cap evicts oldest windows first, which are the blocked ones |
| Impact | About 10,000 distinct failed logins flush every block |
| Fix | Evict non-blocked entries first; add a global failure budget; or shared store (Caffeine or Redis) |
| Verification needed | Test: 10,001 failed logins then retry a previously blocked key |

### PERF-2 Catalog whole-table loads

| Field | Value |
|---|---|
| Skill | java-performance |
| Severity | Low (tiny tables today) |
| Gate | NON-BLOCKING |
| Location | DeliveryService 213 -> CatalogService.requireActivePackage 67-72 and productNames 227-229; DeliveryReceiptService 69-72; StockLedger.balances 161 |
| Issue | Per-line rebuild of a map of all product names (`products.findAll()`) inside the delivery-create transaction. Re-verified 2026-11-24: the loop runs BEFORE `stock.deduct`, so stock_balance locks are not yet held; what is held is the pooled connection and, when an Idempotency-Key is sent, the advisory lock. Receipt and `StockLedger.balances` load the catalog once per request (deliberately, to avoid per-row lookups), so they are not O(lines x products) and are out of scope |
| Impact | Longer advisory-lock and connection hold; O(lines x products) rows read on the write path |
| Fix | In `requireActivePackage`, load only the products named by the package's items (`findAllById`) |
| Resolution (DONE 2026-11-24) | `CatalogService.namesFor(package)` reads only the products the package contains; list endpoints still load the catalog once. Evidence: `DeliveryQueryTests.creatingADeliveryDoesNotLoadTheWholeProductCatalog` counts Product rows loaded while creating one delivery with 40 filler products in the catalog: 49 before, 3 after |

### PERF-4 Delivery indexes

| Field | Value |
|---|---|
| Skill | java-performance |
| Severity | Low |
| Gate | NON-BLOCKING |
| Location | V5 `ix_delivery_status_started`; V21 delivery indexes; `DeliveryService.list` |
| Issue | The original status-first index cannot serve the default newest-first order or branch filter. V21 adds candidate indexes; confirm PostgreSQL selects them at scale. |
| Fix | V21 adds `(started_at desc, id desc)` and `(from_branch_id, started_at desc)`; add `pg_trgm` only if customer search is slow |
| Resolution (DONE) | `DeliveryIndexPlanTests` inserts 100,000 deliveries with a 1% branch distribution, runs `ANALYZE`, and confirms both list queries use the intended indexes with `EXPLAIN (ANALYZE, BUFFERS)`. |

### PERF-5 CSV materialisation

| Field | Value |
|---|---|
| Skill | java-performance |
| Severity | Low |
| Gate | NON-BLOCKING |
| Location | FinanceController.entriesCsv 105-114 |
| Issue | Pageable.unpaged loads up to 366 days of entities, then views, StringBuilder, String and byte[] |
| Fix | StreamingResponseBody with a Stream of entities or a projection; download contract unchanged |
| Resolution (DONE 2026-11-24) | `entries.csv` now streams: `EntryExporter` (own transaction on the async thread) reads one cursor query, detaches entities every 500 rows, and `Csv.writeRow` writes each row as it is read. Period checks run before the first byte; `spring.mvc.async.request-timeout` is 5m. Measured with a throwaway probe (same drained HTTP client, 600,000 rows, 57 MB file): peak heap over baseline 1,163-1,428 MB before, 126-131 MB after; GC time 268-319 ms before, 12-31 ms after; wall time 5.4-5.9 s before, 6.2-7.7 s after (streaming is slightly slower). Peak figures include uncollected garbage, so they are upper bounds on live memory. `EntriesExportSnapshotTests` now asserts one exporter read and a backwards period refused before streaming |

### DBG-1 Unchecked long multiplication

| Field | Value |
|---|---|
| Skill | java-debugging |
| Severity | Low |
| Gate | NON-BLOCKING (the cap becomes a form constraint) |
| Location | DeliveryService 224 (i.quantity() * l.quantity()); CatalogController.ItemRequest 85 (Positive long, no maximum); bigint columns in V5 |
| Issue | Overflow can wrap, e.g. 4 x (2^62 + 1) wraps to 4 stock units while the line total is guarded only by numeric(14,2) |
| Impact | Stock deducted could mismatch the line sold; hard to reach (needs an owner-created package with a huge item quantity) |
| Fix | Math.multiplyExact mapped to RuleViolation; cap quantities with Max at request and database (see Q-003) |
| Verification needed | Reproducing test with a crafted package |

### DBG-3 Latent persistence-context trap

| Field | Value |
|---|---|
| Skill | java-debugging |
| Severity | Low (no current defect) |
| Gate | NON-BLOCKING |
| Location | StockBalanceRepository ensureRow 39 and applyDelta 68 (flush then clear); DeliveryService.cancel 278-288 |
| Issue | The whole persistence context is cleared after each stock call, detaching d |
| Impact | Mutations to d after a stock call would be silently lost; today the mutation precedes the clear |
| Fix | Comment plus regression test, or re-load the entity after stock calls |
| Verification needed | Test that changes an entity after reverseAllFor |
| Evidence (2026-11-24) | `DeliveryPersistenceContextTests` (3 tests, real PostgreSQL, each reads the row back with SQL). **Trap CONFIRMED:** after `stock.reverseAllFor` the delivery is detached (`EntityManager.contains` is false); a status change made afterwards changes only the in-memory copy, raises no error and never reaches the database (row stays ACTIVE). **Current order is safe:** a change made BEFORE the stock call is flushed first and persisted (row becomes DONE), which is how `cancel` is written. **Lines are safe:** they are loaded EAGERLY on purpose (see `Delivery.java`), so the view built after the clear still has them (`PersistenceUnitUtil.isLoaded` is true). **Call sites:** only two production callers pass an entity through a stock write: `DeliveryService.create` (after `stock.deduct` it only reads the delivery and writes new finance rows) and `DeliveryService.cancel` (mutates first). `StockLedger.apply` creates the movement as a new entity and saves it last, so nothing it holds is detached early |
| Assessment | No defect today; a latent trap with a pinned behaviour. The tests fail if someone makes the entity lazy, reorders `cancel`, or removes the flush, and document the rule for new code (including a future return flow, DES-3): change the delivery, then call stock, then only read |
| Candidate fix (not needed now) | Keep as is. Optional hardening if more stock callers appear: have stock calls not clear the whole context (flush only), or re-read the delivery after them |

## 4a. Hardening round (second pass): what changed

| ID | Outcome | What was done | Evidence |
|---|---|---|---|
| SEC-2 | DONE | `username` max 50, `password` max 128 on login; throttle key is a SHA-256 digest (fixed size) | `LoginThrottleTests.keyHasAFixedSizeWhateverTheUsernameLength`, `AuditHardeningTests.oversizedLoginFieldsAreRejected` |
| CON-1 | DONE | `LoginThrottle.acquire` checks and counts in one locked step; success resets | `acquireLetsThroughExactlyTheAllowedAttemptsUnderParallelLoad` (64 threads, exactly 5 pass) |
| CON-2 | DONE | Over the key cap, the oldest unblocked key is evicted first | `floodOfNewKeysDoesNotEvictABlockedKey` |
| SEC-4 | DONE | Deactivation calls `revokeTokens`; reactivation cannot revive old tokens | `AuditHardeningTests`, `UserAdministrationTests` (assertion reversed) |
| SEC-5 | DONE for `/api/users/**` and `/api/audit/**` | URL-level `hasRole(OWNER)` rule; method security kept. Other owner-only routes (catalog writes, profit, reorder levels) still answer 400 before 403 for invalid bodies | `assistantIsRefusedBeforeTheBodyIsValidated` |
| SEC-6 | DONE | Usernames stored trimmed and lower-case; login and duplicate check ignore case; V21 adds a unique index on `lower(username)` unless old data already has look-alikes (then it warns and skips) | `usernamesAreNormalised` |
| SEC-7 | DONE | Password change uses the same throttle (key per user id); 429 with `Retry-After` | `passwordChangeIsThrottledAfterRepeatedWrongCurrentPasswords` |
| SEC-8 | DONE | Redacted the signing key, reject keys with fewer than 16 distinct byte values, and require the bootstrap owner and reset users to change temporary passwords before normal API use; documented the profile/password flow | `SecurityPropertiesTests`, `OwnerBootstrapTests`, `AuthSessionTests.aResetPasswordMustBeChangedBeforeNormalApiAccess` |
| SEC-9 | DONE | `APP_BIND` selects the published interface (default unchanged: all interfaces); documented in `docs/security/README.md` and `.env.example` | n/a (deployment config) |
| DBG-1 | DONE | `Math.multiplyExact` / `addExact` mapped to a 422 "The quantity is too large"; package item quantity capped at 1,000,000 | `DeliveryServiceUnitTests.refusesALineWhoseExpandedQuantityOverflows` |
| PERF-4 | DONE | `DeliveryIndexPlanTests` inserts 100,000 deliveries with a selective branch distribution; both the default and branch-filtered `EXPLAIN (ANALYZE, BUFFERS)` plans use their V21 indexes | `DeliveryIndexPlanTests` passed against PostgreSQL 17 |

Left open on purpose: DES-1 (capability policy refactor; larger design change), DES-3 (needs an owner business decision), CON-3 and PERF-3 (need load test and query-plan evidence), PERF-2 and PERF-5 (low value at current data size, measure first), DBG-3 (latent only; no current defect, no change made).

## 4b. Open-item plan (2026-11-24)

Rules: DES-3 gets no code until the owner decides. CON-3, PERF-3 and DBG-3 get evidence only. PERF-2 and PERF-5 may be implemented, with the current cost and expected change shown.

| ID | Action now | Evidence or decision needed | "Done" is |
|---|---|---|---|
| DES-3 | Decision memo only (recommendation: keep v1 as is, document the process, decide returns after UI v1) | Owner answers: how often goods come back; full or partial; resellable or spoiled; money back or credit; who approves | Written decision with acceptance criteria, then a separate change |
| CON-3 | Evidence plan; run the baseline on `main` BEFORE PERF-2 merges, then again after | 20 parallel same-key creates at pool size 10: Hikari pending/timeouts, `pg_locks` ungranted advisory locks, thread dump | Fix only if unrelated requests time out; target zero 10 s connection timeouts and p99 of `/api/auth/me` under 1 s during the burst |
| PERF-3 | Evidence plan | Statement count per `GET /api/dashboard`; `EXPLAIN (ANALYZE, BUFFERS)` of `totalsByType` and `retainedOnCancelled` on about 1M ledger rows | Targets set after the baseline (proposal: p95 under 200 ms at 1M rows) |
| PERF-2 | DONE | Product rows loaded per create: 49 before, 3 after | Same behaviour; rows read fall from "all products" to "products in the package" |
| PERF-5 | DONE | 600,000-row export: peak heap 1.2-1.4 GB before, about 130 MB after | Peak heap no longer grows with the client-side buffer; assistant type filter kept (`EntriesExportTests`) |
| DBG-3 | Evidence plan; finish before any return or cancel-style code (DES-3) | Test that mutates a delivery after `reverseAllFor` and checks `PersistenceUnitUtil.isLoaded` and the database row | A passing test that pins the behaviour, or a reordered call if it loses a write |

## 5. Cross-cutting clusters

| ID | Cluster | Findings | Why it spans skills | Combined action |
|---|---|---|---|---|
| X-1 | Role visibility of cost data | SEC-1, DES-1, SEC-5 | Authorization gap caused by policy scattered across layers | Fix the policy once, centrally, then align endpoints and tests |
| X-2 | Login protection | SEC-2, SEC-3, CON-1, CON-2 | Pre-auth hardening, key choice and an atomicity race; fixing one leaves the throttle bypassable | Fix together: input bounds, atomic acquire, key design, eviction |
| X-3 | List endpoints and paging | PERF-1, PERF-4 | Performance and an API-contract decision are one change | Page cap, batch fetching and delivery indexes are implemented; the 100k-row delivery plans pass |
| X-4 | Backdating | DES-2, SEC-1 | Policy (design) plus who may post and see ledger data (security) | Define period policy together with role visibility |
| X-5 | Error contract | DBG-2, SEC-5, empty 401/403 bodies | UI must switch on status plus code; 401/403 have no body, owner-only routes return 400 before 403 for bad bodies, same conflict yields two codes | Publish one error-code table and make behaviour consistent |

## 6. Suggested execution order

| Step | Items | Reason |
|---|---|---|
| 1 | SEC-1, DES-1 policy decision, DES-2 policy decision, DES-3 confirmation | Business and policy decisions shape the API contract |
| 2 | SEC-3 with SEC-9 | Deployment topology the UI depends on |
| 3 | PERF-1 (page-size cap first), DBG-2, SEC-5 | Contract-shaping quick wins |
| 4 | X-2 login hardening: SEC-2, CON-1, CON-2 | Fix as one change |
| 5 | SEC-4, SEC-6, SEC-7 | Low-cost security hygiene; SEC-6 needs a migration check |
| 6 | PERF-2, PERF-3, PERF-5, CON-3, DBG-3 | After measurement; may follow UI work |

## 7. Evidence still required before acting

| ID | Evidence needed | Method |
|---|---|---|
| SEC-2 | Heap behaviour under large usernames | Send N x 1 MB usernames to a running container; watch heap and GC |
| SEC-3 | Real proxy X-Forwarded-For behaviour and ForwardedHeaderFilter entry choice | Test through the intended proxy |
| CON-2 | Eviction reset | 10,001 failed logins then retry a blocked key |
| CON-3 | Pool starvation | 20 parallel identical Idempotency-Key requests at pool size 10 |
| PERF-1 | Statement counts | Hibernate statistics or SQL log for GET /api/deliveries?size=50 |
| PERF-3 | Query plans | EXPLAIN (ANALYZE, BUFFERS) on a synthetic ledger with about 1M rows |
| DBG-1, DBG-3 | Reproductions | Crafted package test; entity change after reverseAllFor |
| DES-3 | Business process | Owner confirmation |

## 8. Files to update when fixes land

| If you change | Update |
|---|---|
| A role rule or add an endpoint | SecurityMatrixTests.MATRIX; docs/security/README.md section 4 |
| Token contents or lifetime | docs/security/README.md S3-S5 and section 5; docs/api/auth-session.md |
| Response shapes or paging | docs/api/*.md; docs/api/openapi.json (OpenApiSnapshotTests) |
| Headers, CORS or forwarding | SecurityHeadersAndCorsTests; docs/security/README.md I4, I5 |
| Known gaps or findings | docs/quality/findings.md (add Q-ids) |
