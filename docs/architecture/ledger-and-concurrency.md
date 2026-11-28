# Ledgers, costing and concurrency

The business cannot afford to get two things wrong: **how many units it has** and **how much money it made**. Both are append-only ledgers; balances and reports are derived. The database arbitrates overlapping requests: there are no Java locks, only row locks, advisory locks, atomic statements and constraints.

## 1. Principles

1. **Append-only** ([decision 2](decisions.md#2-append-only-ledgers)). A movement or entry is never updated or deleted; a mistake is a reversal or an adjustment.
2. **The ledger is the truth, the balance is a cache.** `stock_balance` changes in the same statement that writes the movement, so a product's movements always sum to its balance (`WeightedAverageCostTests` checks this over 300 random steps).
3. **The type gives the direction, never the sign.** Amounts are positive; stock quantity is signed with the legal sign fixed by the type.
4. **Who and when on every row.** `created_by`, `created_at`, and `occurred_on` (the Manila business date).

## 2. Stock movements and weighted average cost

| Type | Sign | Entered by | Cost recorded | Cash |
|---|---|---|---|---|
| `OPENING` | + | owner | 0 unless given | none |
| `PURCHASE` | + | anyone | the unit cost (> 0) | `STOCK_PURCHASE` out |
| `DONATION` | + | anyone | 0 | none |
| `USAGE` | - | anyone, reason required | current average | none |
| `ADJUSTMENT` | + or - | owner, reason required | current average | none |
| `DELIVERY_OUT` / `DELIVERY_REVERSAL` | - / + | delivery flow only | current average / the cost it undoes | none |

Stock is valued at the **weighted average cost per product per branch**, maintained by one SQL statement (`StockBalanceRepository.applyDelta`) so the quantity check and the average update are one atomic step ([decision 4](decisions.md#4-weighted-average-in-sql)):

```
new average = round( (old qty x old avg + added qty x unit cost) / (old qty + added qty), 4 )
```

- Incoming stock with a cost (purchase, donation at 0, opening, reversal) moves the average. Outgoing stock and incoming stock without a cost (a positive adjustment) leave it unchanged; outgoing movements are stamped with the current average, which is the cost of goods.
- An empty shelf keeps its last average but with no weight: the next purchase sets it to that cost exactly.
- Four decimals, half-up. Each step rounds, so the stored value can differ from the exact mean by 0.00005 per step; cost of goods is rounded to centavos per movement.

| Step | Event | Qty | Average | Calculation |
|---|---|---|---|---|
| 1 | Purchase 10 at 100.00 | 10 | 100.0000 | first stock |
| 2 | Purchase 5 at 130.00 | 15 | 110.0000 | (1000 + 650) / 15 |
| 3 | Donation of 15 | 30 | 55.0000 | free stock dilutes the average |
| 4 | Usage of 10 | 20 | 55.0000 | going out never changes it |
| 5 | Purchase 3 at 99.99 | 23 | 60.8683 | (1100 + 299.97) / 23 |
| 6-7 | Usage of 23, then purchase 4 at 10.00 | 4 | 10.0000 | the old average has no weight |

## 3. Money

Entry types and the reports built from them are in [API rules](../api/rules.md#finance). Amounts have at most two decimals; a cash entry names its method and a non-cash entry has none; sales, costs, payments and refunds always carry a source record; refunds are owner-only and capped by the payment still held; a zero cost of goods (donated stock) writes nothing.

What a delivery writes, for 2 packages (24 chickens bought at 10.00 and 2 cages at 50.00 each) paid by GCash:

| Event | Stock ledger | Financial ledger |
|---|---|---|
| **Create** | chicken -48 at 10.0000; cage -4 at 50.0000 | `SALE` 140,000.00; `COST_OF_GOODS` 680.00; `PAYMENT_IN` 140,000.00 |
| **Done** | nothing | nothing |
| **Cancel** (owner) | chicken +48, cage +4 at their original cost | `SALE_REVERSAL`; `COGS_REVERSAL`; the payment stays: 140,000.00 held |
| **Refund 40,000.00** | nothing | `REFUND` 40,000.00; 100,000.00 still held |
| **Refund 100,000.01** | nothing | refused (more than held) |

After cancellation sale and cost net to zero in every report, and the customer's money shows as *retained on cancelled deliveries* until refunded. `STOCK_PURCHASE` is not in profit: its cost reaches profit as `COST_OF_GOODS` when the stock is delivered, and subtracting both would charge it twice.

## 4. Concurrency mechanisms

Each is proven against real PostgreSQL ([testing](../testing.md)).

| Promise | Mechanism | Test |
|---|---|---|
| Two deliveries never both take the last stock | `applyDelta` is **one `UPDATE`** adding the delta only `where quantity + delta >= 0`; the row lock makes a competitor wait and re-evaluate. `check (quantity >= 0)` backs it | `StockLedgerTests.twoConcurrentDeliveriesCannotBothTakeTheLastStock` |
| Overlapping deliveries cannot deadlock | Products are always processed in **ascending id** (`TreeMap`, sorted deduct and reverse) | `DeliveryConcurrencyTests.overlappingPackagesInOppositeOrdersNeverDeadlock` |
| No lost update on quantity or average, however many purchases overlap | The same statement computes both from the row's current values | `concurrentPurchasesAtOneCostLoseNothing` |
| Done **or** cancelled, never both; cancelled once | `DeliveryRepository.lockById` (`select ... for update`) is taken first by done, cancel and refund; the status check runs after the lock, so the loser gets `409` | `doneAndCancelRaceHasOneWinnerAndConsistentBooks` |
| A row is reversed at most once | Reversal skips originals that already have one; `reverses_id` is `unique` in both ledgers | `reversalRestoresStockOnceEvenIfRequestedTwice` |
| Refunds never exceed the payment held | Refunds lock the delivery row, so they run in sequence, each recomputing `payments - refunds` | `concurrentRefundsNeverExceedThePayment` (6 x 20,000 against 70,000: exactly 3 succeed) |
| One delivery per (user, `Idempotency-Key`), even for simultaneous retries | `pg_advisory_xact_lock(hashtextextended('<user>:<key>', 0))` serialises same-key requests; the second finds the first's row; the primary key is the backstop ([decision 5](decisions.md#5-idempotent-delivery-creation)) | `DeliveryIdempotencyTests` (8 concurrent requests) |
| A failed delivery leaves nothing | One transaction; the exception rolls back every row and does not consume the key | `DeliveryTests.notEnoughStockRejectsTheWholeDeliveryAndLeavesNoTrace` |

### Not guaranteed, and why that is acceptable

- **Delivery codes can have gaps.** A sequence value is lost when a transaction rolls back; codes are unique and increasing, not gapless.
- **The stored average is order-dependent at the fifth decimal**, from per-step rounding. Quantities are exact and cost of goods is stable to the centavo.
- **Waiting costs a connection.** A request waiting for a row lock holds a pool connection (10 in production). Contention is rare at this size; the pool and lock scope are the first things to review if it grows.

### Rules for new code

1. Balance changes go through `StockApi`; never write `stock_balance` any other way.
2. Multi-product operations process products in ascending id.
3. A state transition takes `lockById` (or an equivalent row lock) **before** reading the status.
4. A check-then-act on money happens under a lock on the record that owns it.
5. A new uniqueness promise gets a database constraint, not only a Java check.
6. A new concurrency promise gets a `ConcurrentRunner` test that fails when the mechanism is removed.
