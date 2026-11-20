# ADR-0001: Modular monolith

- Status: Accepted
- Date: 2026-11-20 (records the decision taken at project start)

## Context

Ausan is a small trading business with two branches and a handful of staff. It needs transactional correctness (stock and money
must never disagree) far more than it needs independent scaling. One small team builds and runs it, and the operating budget
is one server and one database.

Splitting into services would turn every delivery (stock out, three ledger rows, a payment) into a distributed transaction.
A single, unstructured application would let any class reach into any table and would decay quickly.

## Decision

One Spring Boot application, one PostgreSQL database, one deployable. Inside it, **modules** (`shared`, `identity`, `catalog`,
`stock`, `finance`, `delivery`, `report`) with these rules, checked by ArchUnit on every build
(`ModuleBoundaryTests`, `CodingRulesTests`):

1. A module is used only through its `*Api` interface and the immutable views/commands next to it.
2. Entities, repositories and implementations are package-private.
3. Dependencies point one way (see the table in [../overview.md](../overview.md)); there are no cycles.
4. A module owns its tables. Other modules never join to or write them; a ledger row points to its cause with a loose
   `source_type` + `source_id` instead of a foreign key.
5. Cross-module work happens in the caller's single transaction.

## Consequences

- A delivery commits or rolls back as a whole with plain `@Transactional`; no sagas, no outbox, no eventual consistency.
- Refactoring across modules is a compile-time activity, not a deployment one.
- Splitting a module out later is possible because the boundary is already an interface and the data is already separated. The
  loose source references mean no foreign keys have to be cut.
- The price is discipline: nothing but the tests stops someone from making a class public. That is why the rules are executable.
- All modules scale together and share one connection pool (10 connections in production).

## Alternatives considered

- **Microservices.** Rejected: distributed transactions for every sale, much more operations work, no scaling need.
- **Layered monolith (controller/service/repository packages for the whole app).** Rejected: no ownership of tables, so any
  service could write the ledgers; the dependency graph tends to become a ball.
- **Multi-module Maven build.** Possible later; package boundaries plus ArchUnit give the same protection now without the build overhead.
