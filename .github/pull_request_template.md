## What and why

<!-- One or two sentences: the behaviour that changes and the reason. -->

## Checklist

- [ ] `./mvnw verify` passes locally (Docker running), including the architecture and coverage gates.
- [ ] New or changed behaviour has a test that would fail without the change. Money, stock and concurrency rules are tested against real PostgreSQL.
- [ ] Every new class and method has Javadoc, and comments explain *why* (see "Code documentation standard" in the README).
- [ ] Applied Flyway migrations are untouched; schema changes are a new migration.
- [ ] API changes are reflected in `docs/api/` (and the OpenAPI snapshot, if the contract changed).
- [ ] A decision that future readers will question is recorded in `docs/architecture/decisions.md`.
- [ ] Modules still talk only through their `*Api` interfaces.
