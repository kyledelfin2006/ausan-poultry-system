# Design decisions

The decisions that are expensive to reverse: what was chosen, why, and what it costs. Read the relevant entry before changing what it covers. To change one, replace its entry and say why in the commit.

## 1. Modular monolith

**Decision.** One Spring Boot application, one PostgreSQL database, one deployable, divided into modules whose boundaries ArchUnit enforces on every build.

- A module is used only through its `api` package. Dependencies point one way, with no cycles ([overview](overview.md)).
- A module owns its tables. Others never join to or write them; a ledger row points at its cause with a loose `source_type` + `source_id`, not a foreign key.
- Cross-module work runs in the caller's single transaction.

**Why / cost.** A delivery commits or rolls back as a whole with plain `@Transactional`: no sagas, no outbox, no eventual consistency. A module can be split out later because its boundary is already an interface and its data is already separate. The price is discipline, so the rules are executable. All modules scale together and share one connection pool (10 in production).

## 2. Append-only ledgers

**Decision.** `stock_movement` and `financial_entry` rows are inserted, never updated or deleted. A correction is a new row: a **reversal** (`reverses_id`, unique, so a row is undone once) or an owner-only **adjustment** with a reason. Every row records who, when and the Manila business date. `stock_balance` is a read model written by the same statement in the same transaction.

**Why / cost.** A complete audit trail, and any past report can be reproduced. A cancelled delivery leaves four visible facts (sale, cost, two reversals) that reports net to zero. Ledgers only grow, which is irrelevant at a few thousand rows a year. Fixing a mistaken reversal takes more steps than editing, by design. [Details](ledger-and-concurrency.md).

## 3. Stateless JWT with a database re-check

**Decision.** Login returns an HS256 JWT (8 hours, only HS256 accepted, secret of 32+ bytes). The app is a stateless resource server: no session, no cookie, CSRF off because no ambient credentials exist. **Every request** loads the user by id: missing or inactive is `401`, and the role comes from the database, not the token. Logout and password change raise `app_user.token_version`, so every older token fails. Login is throttled to 5 failures per 15 minutes per username and address.

**Why / cost.** A pure JWT could not be revoked, so a demoted or departed assistant would keep access for hours. The re-check makes deactivation, role changes, logout and password changes immediate, with no blacklist, for one primary-key read per request. There is no refresh token, the throttle is in memory (one instance), and rotating the secret signs everyone out, which is also the emergency revocation. Rejected: server sessions (shared storage, poor mobile fit), trusting claims, a blacklist table, opaque tokens. [Controls](../security.md).

## 4. Weighted average in SQL

**Decision.** `StockBalanceRepository.applyDelta` is one native `UPDATE`: it adds the delta only if `quantity + delta >= 0`, and for a positive delta with a cost sets `avg = round((qty x avg + delta x cost) / (qty + delta), 4)`. The statement takes the row lock, so concurrent changes queue and each sees the previous result. A zero-quantity row is created first so there is always a row to lock.

**Why / cost.** No lost updates and no oversell without optimistic retries or application locks. The formula in SQL is harder to unit test, so it is tested against PostgreSQL with a hand-calculated scenario and a seeded random model checked against a `BigDecimal` oracle. The statement is PostgreSQL-specific, which is acceptable for a fixed database. Rounding at every step can drift a fraction of a centavo; cost of goods is rounded per movement, which hides it. Callers lock products in ascending id.

## 5. Idempotent delivery creation

**Decision.** `POST /api/deliveries` takes an optional `Idempotency-Key`, scoped per user. A transaction-scoped advisory lock on `user:key` serialises same-key requests; `delivery_idempotency (key, user_id)` is the primary key and backstop. An identical retry returns the original delivery; a different body is `422`; the key row commits with the delivery, so a failed creation does not consume it.

**Why / cost.** A client that lost the response can retry until it gets an answer, and unrelated deliveries never wait on each other because the lock is on the key. "Identical" is judged on business content, and an omitted price override matches any price. Keys are kept indefinitely (one small row each). Cancel and refund are already safe to repeat (second cancel is `409`, refunds are capped).

## 6. Real PostgreSQL in tests

**Decision.** Pure unit tests (JUnit, Mockito, no Spring) for rounding, state transitions, validation order and orchestration. Integration and concurrency tests against **Testcontainers PostgreSQL 17**, the production engine, for constraints, locking, atomic SQL, the HTTP contract, security and rollback.

- Never substitute H2.
- A concurrency claim needs a test that races real transactions and fails when the mechanism is removed.
- Time comes from an injected `Clock`; no test sleeps.
- Architecture rules are tests too.

**Why / cost.** The tests that matter run on the engine that runs in production. The build needs Docker; the full suite takes about a minute while pure units finish in a second. See [testing](../testing.md).

## 7. `api` and `internal` packages

**Decision.** Every module except `shared` has exactly two top-level packages. `api/` holds what other modules may use: `<Module>Api`, `model/` (views, commands, enums), `exception/`, `security/`. `internal/` holds `web/`, `service/` (including the `*Api` implementation), `model/`, `persistence/`, plus `security`, `config`, `export` where needed. `delivery` and `report` have no `api`. Layers inside `internal` point one way: `web` -> `service` -> `persistence`.

**Why / cost.** Flat packages with package-private access gave compiler protection but crammed 22 files into `identity` and hid each module's contract inside nested records. Sub-packages need `public` classes, so ArchUnit now guards the boundary instead of the compiler. Keep the architecture tests in the required checks, since disabling them removes the protection. Rejected: layer packages for the whole app, Spring Modulith (a dependency for rules ArchUnit already checks), and a multi-module Maven build (more complexity than needed yet).
