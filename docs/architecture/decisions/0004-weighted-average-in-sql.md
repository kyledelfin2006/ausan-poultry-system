# ADR-0004: Weighted average cost computed in one SQL statement

- Status: Accepted
- Date: 2026-11-20 (records the decision taken during the stock slice)

## Context

Stock is valued at the weighted average cost per product per branch. Two requests can change the same balance at the same time
(two deliveries, a purchase during a delivery). The classic approach (read the balance, compute in Java, write back) loses updates
unless every access is locked, and the "can I take this quantity?" check races with the write.

## Decision

`StockBalanceRepository.applyDelta` is a single native `UPDATE`:

- it adds the delta to the quantity **only if** `quantity + delta >= 0` (zero rows updated means "not enough stock");
- when the delta is positive and a cost is given, it sets
  `avg = round((quantity * avg + delta * cost) / (quantity + delta), 4)`; otherwise the average is left alone;
- the statement takes the row lock, so concurrent changes queue up and each sees the previous result;
- a row is created with quantity 0 beforehand (`ensureRow`), so there is always a row to lock.

The Java side only decides *what* to apply; the arithmetic and the guard are the database's.

## Consequences

- No lost updates and no oversell, without optimistic-lock retries or application-level locks
  ([../consistency-and-concurrency.md](../consistency-and-concurrency.md)).
- The formula lives in SQL, which is harder to unit test than Java. It is tested against PostgreSQL with a hand-calculated
  scenario and a seeded random model compared with a `BigDecimal` oracle (`WeightedAverageCostTests`).
- The statement is PostgreSQL-specific syntax; this is acceptable, since the database is a fixed choice ([ADR-0006](0006-real-postgresql-in-tests.md)).
- The average is rounded at every step (four decimals), so it can drift by a fraction of a centavo against the exact mean. The cost
  of goods is rounded to centavos per movement, which hides this at the money level.
- Callers must process several products in ascending id so that the row locks are taken in a consistent order.

## Alternatives considered

- **Read, compute, save with `@Version` optimistic locking.** Correct but needs retry loops, and a hot product would fail
  requests under contention.
- **`SELECT ... FOR UPDATE` then compute in Java.** Correct and easier to unit test, but two round trips and more lock time.
  The single statement is shorter and holds the lock for less time.
- **Recompute the average from the ledger on every read.** Always consistent but slow, and a change of costing rule would
  silently change history.
