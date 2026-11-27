# Findings

Defects, risks and observations found while building the quality gates. Each has an id, an owner (who should act), a severity and, where
a defect is reproducible, an executable test that is `@Disabled` until the fix lands (remove the annotation in the fixing change).

Severity: **defect** = behaviour contradicts the documented or obviously intended rule; **risk** = works today, can hurt later;
**observation** = a design fact worth knowing, no action required.

Owners follow the module split of the parallel work: *delivery/stock/finance/report*, *identity/audit*, *shared*, and
*catalog (unowned)*, which needs somebody to adopt it.

## Open defects

| Id | Owner | Summary | Reproducing test |
|---|---|---|---|
| Q-001 | catalog (unowned) | `PATCH` of a product can change its name to blank or spaces only; the request returns 200 instead of 400. Creating a product with a blank name is refused, so the update path is missing the same validation. | `CatalogApiTests.aPatchCannotBlankOutAProductName` (`@Disabled`) |
| Q-002 | catalog (unowned) | Names that differ only by trailing spaces are different to the database, so `"Layer feed "` is created next to `"Layer feed"` (201) instead of being refused as a duplicate (409). The name should be trimmed before it is saved and compared. | `CatalogApiTests.namesThatDifferOnlyByTrailingSpacesAreTheSameProduct` (`@Disabled`) |

Suggested fix for both: trim in the request mapping (or the entity setter) and validate non-blank in one place used by create and update.

## Risks

| Id | Owner | Summary | Why it matters | Suggested action |
|---|---|---|---|---|
| Q-003 | delivery (Claude 2) | There is no upper bound on the quantity of a package line. A huge value (`2^62 + 1`, `Long.MAX_VALUE`) fails cleanly, but through a database overflow guard, so the client sees the generic `409 "The change conflicts with existing data"` and not a validation message. Nothing is written (`DeliveryEdgeCaseTests` proves it). | Poor error message; the safety depends on a database column size rather than on a rule. | Validate a sensible maximum (for example 10,000 packages) at the request and answer 400. |
| Q-005 | all modules (unowned) | Javadoc lint runs with `-Xdoclint:all,-missing`. Under strict `-Xdoclint:all` the existing code produces at least 100 warnings (javadoc stops counting at 100): about 80 "no comment" on private fields, 13 "no @param", 4 default constructors without a comment, 3 "no @return". | The project's documentation standard asks for Javadoc everywhere; the gate currently enforces *correctness* of what is written, not *completeness*. | Document the backlog module by module, then remove `-missing` from the `<doclint>` setting in `pom.xml` so the gate also enforces completeness. |
| Q-006 | identity (Claude 3) | `LoginThrottle` keeps its counters in memory (5 failures per 15 minutes per username and address, at most 10,000 keys). | A restart resets it and several instances would each count separately. Fine for one instance. | Move to a shared store before running more than one instance. Already listed under known limits in [../architecture/overview.md](../architecture/overview.md). |
| Q-012 | delivery | A burst of identical creates with one stuck `Idempotency-Key` still ties up the whole connection pool for about 5 s. Nothing fails (every request is answered and unrelated requests succeed), but an unrelated request sent during the burst waited up to 4.9 s. See the measurements below. | The lock wait is bounded (2.5 s) but the waiters run in two waves of 10 (pool size), so the worst case is about two lock waits. Only reachable by a client that repeats one key many times at once, for example a UI with a retry bug. | Not needed for launch. If it matters: allow only a few waiters per key (answer the rest 409 at once), or lower the lock wait. The UI must retry only with the delay in `Retry-After`, never in a tight loop (see `docs/api/errors.md`). |

## Observations

| Id | Area | Observation |
|---|---|---|
| Q-007 | delivery | The idempotency comparison treats an *omitted* price override as matching any price. A retry that adds an override to an otherwise identical request is therefore answered with the original delivery, not refused. Probably what a client wants (the original is what happened), but it is a design choice: confirm it with the owner. |
| Q-008 | delivery | Delivery code numbers are not gapless: the sequence value of a failed creation is lost (see [consistency-and-concurrency.md](../architecture/consistency-and-concurrency.md)). If paper records require gapless numbers, a different numbering scheme is needed. |
| Q-009 | stock | The stored average cost can differ in the fifth decimal depending on the order in which concurrent purchases are applied (rounding at every step). Quantities are always exact and the cost of goods is rounded to centavos per movement. `DeliveryConcurrencyTests` bounds the drift at 0.0005. Not a defect. |
| Q-010 | catalog | Before this work the catalog module had no tests (about 45 % line and 8 % branch coverage). `CatalogApiTests` now covers its endpoints, which is how Q-001 and Q-002 were found. Further catalog rules (package contents immutable, deactivation) would add confidence. |
| Q-011 | build | Mockito's inline mock maker attaches an agent at runtime; JDK 25 warns about this unless `-XX:+EnableDynamicAgentLoading` is set. It is set in the Surefire `argLine` together with the JaCoCo agent. Remove it only when Mockito is configured with an explicit agent. |

## Measurements

Re-checks made before UI work (2026-11-27). Machine: Windows 11, 12 logical CPUs, 32 GB RAM, JDK 25, PostgreSQL in Testcontainers on the same machine as the application and the load generator. All three harnesses are off unless run with `-Devidence=true`. Treat the numbers as one machine's evidence, not a capacity claim; what matters is the shape (no failures, bounded waits) and that it repeats.

| Id | Scenario | Result (3 runs unless noted) |
|---|---|---|
| CON-3 | 20 identical delivery creates released together, pool of 10 (production values), first request holds the key's lock for 0 s / 3 s / 12 s | No create ever failed or timed out. 0 s: 19x200 + 1x201, p50 about 0.23 s. 3 s: 9x200, 1x201, 10x409 (retryable), p50 3.0 s. **12 s (the case not re-measured before): all 20 answered 409 with `Retry-After` between 2.6 s and 5.4 s** instead of Hikari's 10 s connection timeout. Unrelated `GET /api/auth/me` sent every 100 ms: 0 failures in every run; p50 11 ms, but **p99/max 4.9 s in the 12 s case** (pool full, 16 threads waiting for a connection), 2.9 s in the 3 s case, 0.1 s in the fast case. Residual risk recorded as Q-012. |
| PERF-3 | Owner and assistant dashboard on a ledger of 1,000,000 rows (300,000 deliveries), 30 calls each, 5 separate runs | Statements per owner call: 11 (was 14). Owner p50 134-140 ms, p95 147-160 ms, max 154-186 ms. Assistant p50 132-136 ms, p95 144-173 ms. Run-to-run spread is about 5 %. The month-to-date ledger query uses `ix_entry_date` (about 6 ms). |
| Export cap | 12 clients start `GET /api/finance/entries.csv` (150,000 rows, 23.8 MB) and stop reading; then disconnect | 3 x 200 and 9 x 429 with `Retry-After: 5` in all 3 runs. While the three streams were stalled, 40 unrelated requests: 0 failures, p50 13 ms, max 27 ms; pool 3 active (4 at most) of 10. After the clients disconnected without finishing, all 3 slots came back (100 ms in two runs, 3.0 s in one, while the server noticed the closed sockets) and a new export of the full file succeeded. |

Run them with `./mvnw test -Dtest=AdvisoryLockPoolEvidenceTests -Devidence=true`, `-Dtest=DashboardEvidenceTests` and `-Dtest=ExportBurstEvidenceTests`.

## How to add a finding

1. Add a row here with the next id, the owner, and a one-line summary a non-developer can understand.
2. If it is a defect that can be reproduced, add a test that fails today, annotate it `@Disabled("Q-00N: ...")`, and name it in the table.
3. Tell the owner (the module's session or maintainer). The fixing change removes `@Disabled` and moves the row to
   "Resolved" below with the pull request.

## Resolved

| Id | Resolution |
|---|---|
| Q-004 | The `audit` module was registered in `ModuleBoundaryTests` (audit depends on `shared`; `identity` and `catalog` may depend on `audit`) in the change that introduced it. `CodingRulesTests` covers its classes without changes. |
