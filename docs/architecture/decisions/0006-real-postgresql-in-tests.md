# ADR-0006: Real PostgreSQL in tests, pure units for logic

- Status: Accepted
- Date: 2026-11-20

## Context

The most important promises of this system live in the database: row locks, one-statement updates, check constraints, unique
constraints, sequences, advisory locks. An in-memory database (H2) or a mock cannot prove them: they behave differently or not at
all. At the same time, arithmetic and state rules (rounding, the delivery state machine) are quicker to check, and easier to
point at precisely, without any Spring context or container.

## Decision

Two kinds of tests, chosen by what must be proven:

| Kind | Used for | Tools | Speed |
|---|---|---|---|
| **Pure unit tests** | Rounding, cost calculations, state transitions, validation order, call orchestration (who is called, in which order) | JUnit 5, Mockito, AssertJ, no Spring | milliseconds |
| **Integration and concurrency tests** | Constraints, locking, atomic SQL, HTTP contract and security, rollback | Spring Boot test, **Testcontainers PostgreSQL 17** (the production engine), real transactions, `ConcurrentRunner` | seconds; the container starts once and is shared |

Rules:

1. Never replace PostgreSQL with H2 or another substitute.
2. A concurrency claim is only accepted with a test that races real transactions and fails when the mechanism is removed.
3. Time comes from an injected `Clock`; tests use `TestSupport.clockAt(...)`. No test sleeps to wait for a result.
4. Architecture rules are tests too (`ModuleBoundaryTests`, `CodingRulesTests`).
5. Quality gates in the build: all tests, JaCoCo coverage floor, ArchUnit, Javadoc lint
   ([../../quality/README.md](../../quality/README.md)).

## Consequences

- The tests that matter run against the engine that runs in production; the build needs Docker.
- The full suite takes about a minute; the pure units run in a second, so a developer gets quick feedback and the CI gets the full proof.
- Mutation checks (deliberately breaking the code and watching the tests fail) are used to confirm that the tests can fail; the log is in
  [../../quality/mutation-checks.md](../../quality/mutation-checks.md).

## Alternatives considered

- **H2 in PostgreSQL mode.** Rejected: no `pg_advisory_xact_lock`, different locking, different numeric semantics.
- **Mocks for repositories everywhere.** Rejected for data rules: a mock repeats the author's assumption instead of testing it.
  Kept for orchestration units where the collaborator's answers define the scenario.
- **Embedded PostgreSQL binaries.** Rejected: less faithful and less maintained than Testcontainers.
