# Architecture decision records

An ADR records one decision that is expensive to reverse: what was chosen, why, and what it costs. Read these before
changing something they cover; if the change contradicts an ADR, write a new ADR that supersedes it rather than editing history.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-modular-monolith.md) | One deployable application split into modules with enforced boundaries | Accepted |
| [0002](0002-append-only-ledgers.md) | Stock and money are append-only ledgers; mistakes are reversed, never edited | Accepted |
| [0003](0003-stateless-jwt-with-database-recheck.md) | Stateless JWT, but the user is re-read from the database on every request | Accepted |
| [0004](0004-weighted-average-in-sql.md) | Weighted average cost is computed in one atomic SQL statement | Accepted |
| [0005](0005-idempotent-delivery-creation.md) | Delivery creation is idempotent per user through a client-supplied key | Accepted |
| [0006](0006-real-postgresql-in-tests.md) | Tests that prove data rules run against a real PostgreSQL (Testcontainers) | Accepted |

## Template

Copy this for a new decision; number it with the next free number and add it to the table.

```markdown
# ADR-00NN: Title

- Status: Proposed | Accepted | Superseded by ADR-00MM
- Date: YYYY-MM-DD

## Context
What forces are at play? What is the problem, in business and technical terms?

## Decision
What we will do, in one or two sentences, then the details that matter.

## Consequences
What becomes easier, what becomes harder, what we accept as a cost.

## Alternatives considered
Each option and the reason it was not chosen.
```
