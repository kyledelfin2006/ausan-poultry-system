# Consistency and concurrency

Two staff members can work at the same time, a phone can retry a request, and a customer can phone to cancel while the driver
marks the delivery done. This document lists every mechanism that keeps the books right when requests overlap, and the test
that proves each one against a real PostgreSQL.

The general approach: **let the database arbitrate.** The code does not use Java locks or `synchronized`; every guarantee is a
row lock, an advisory lock, an atomic statement or a constraint, so it holds across threads, requests and (later) instances.

## 1. Mechanisms

| # | Promise | Mechanism | Proven by |
|---|---|---|---|
| 1 | Two deliveries can never both take the last stock; stock never goes below zero | `StockBalanceRepository.applyDelta` is **one `UPDATE`** that adds the delta only `where quantity + delta >= 0`. The update takes a row lock held until commit, so a competing update waits, then re-evaluates against the first one's result. `check (quantity >= 0)` is the backstop. | `StockLedgerTests.twoConcurrentDeliveriesCannotBothTakeTheLastStock`; `DeliveryConcurrencyTests.twoDeliveriesCompetingForTheLastPackageProduceExactlyOne` (5 rounds); `...twelveDeliveriesForFiveSlotsCreateFiveAndNeverOversell` |
| 2 | Overlapping deliveries cannot deadlock | Products are always processed in **ascending product id**: `DeliveryService` collects them in a `TreeMap`, `StockLedger.deduct` sorts again, `reverseAllFor` sorts too. Two transactions therefore always queue on the same rows in the same order. | `DeliveryConcurrencyTests.overlappingPackagesInOppositeOrdersNeverDeadlock` (packages that list the same products in opposite orders); `StockLedgerUnitTests.deductProcessesLinesInProductOrder...` |
| 3 | No lost update on quantity or average cost, however many purchases overlap | The same single statement computes the new quantity *and* the new average from the row's current values. | `DeliveryConcurrencyTests.concurrentPurchasesAtOneCostLoseNothing`, `...AtDifferentCosts...` |
| 4 | A delivery is finished **or** cancelled, never both; it is cancelled once | `DeliveryRepository.lockById` is a `PESSIMISTIC_WRITE` read (`select ... for update`) taken first by mark-done, cancel and refund; the status check runs after the lock, so the loser sees the final status and gets 409. | `DeliveryConcurrencyTests.concurrentCancellations...`, `...doneAndCancelRaceHasOneWinnerAndConsistentBooks` |
| 5 | A stock-out or ledger row is reversed at most once | The reversal skips originals that already have one (`existsByReversesId`), and `reverses_id` is `unique` in both ledgers, so even a bypass of the lock cannot write two. | `StockLedgerTests.reversalRestoresStockOnceEvenIfRequestedTwice`; `DeliveryConcurrencyTests.concurrentCancellations...` (exactly 2 stock reversals, 1 sale reversal, 1 cost reversal) |
| 6 | Refunds never exceed the payment held | Refunds lock the delivery row (mechanism 4), so they run one after another; each computes `payments - refunds` after the previous one committed. `recordRefund` also reads the payment rows `for update`-style to queue concurrent refunds that bypass delivery. | `DeliveryConcurrencyTests.concurrentRefundsNeverExceedThePayment` (6 refunds of 20,000.00 against 70,000.00: exactly 3 succeed) |
| 7 | One delivery per (user, Idempotency-Key), also when the retries arrive at the same moment | A transaction-scoped advisory lock `pg_advisory_xact_lock(hashtextextended('<user>:<key>', 0))` serialises same-key requests; the second finds the first's committed row. The primary key `(idempotency_key, user_id)` is the backstop. A key is scoped per user. | `DeliveryIdempotencyTests` (HTTP, 8 concurrent requests); `DeliveryConcurrencyTests.sameKeyAndSameBody...`, `...sameKeyWithDifferentBodies...`, `...theSameKeyFromTwoUsersIsNotShared` |
| 8 | A failed delivery leaves nothing behind | Everything runs in one transaction; the exception that leaves the service rolls back the delivery row, every movement and every ledger row, and does not consume the idempotency key. | `DeliveryTests.notEnoughStockRejectsTheWholeDeliveryAndLeavesNoTrace`, `DeliveryEdgeCaseTests` |
| 9 | Stock never changes without its payment record (purchase) | Stock and finance are written in the caller's one transaction. | `FinanceAndCostTests.purchaseNeedsPaymentMethodAndCost_andWritesNothingOtherwise` |

## 2. What is *not* guaranteed (and why that is acceptable)

- **Delivery codes can have gaps.** The code number comes from a database sequence, and a sequence value is not returned when the
  transaction rolls back. Codes are unique and increasing, not gapless. If the business needs gapless numbers, assign them
  after commit or in a separate counter table; this was not requested.
- **The stored average is order-dependent at the fifth decimal.** Each purchase rounds the average to four decimals, so the same set
  of purchases applied in a different order can differ by a few ten-thousandths of a peso. The value that matters (cost of goods,
  rounded to centavos per movement) is stable to the centavo for realistic quantities, and the quantity is always exact.
  `DeliveryConcurrencyTests.concurrentPurchasesAtDifferentCostsKeepTheQuantityExactAndTheAverageWithinRounding` documents it.
- **Waiting costs a connection.** A request waiting for a row lock holds a pool connection (10 in production). With two branches and a
  few staff, contention is rare and short; if the business grows, the pool and the lock scope are the first things to review.

## 3. Rules for new code

1. A change to a balance goes through `StockApi`; never write `stock_balance` any other way.
2. A multi-product operation processes products in ascending id.
3. A state transition on a record takes `lockById` (or an equivalent row lock) **before** it reads the status.
4. A check-then-act on money (a cap, a limit) happens under a lock on the record that owns it.
5. A new uniqueness promise gets a database constraint, not only a Java check.
6. A new concurrency promise gets a test built on `ConcurrentRunner`
   ([../quality/testing-guide.md](../quality/testing-guide.md)) that fails when the mechanism is removed.
