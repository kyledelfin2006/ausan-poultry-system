# Mutation checks

A test that cannot fail proves nothing. For the risky rules, the production code was deliberately broken in a scratch copy of the
repository (outside the project, never committed), the relevant tests were run, and the result was recorded. **Killed** means at
least one test failed, which is what we want. The "test that caught it" column names the class that failed; the exact method
may differ when several tests cover the rule.

| # | Deliberate break | Rule it violates | Result | Test that caught it |
|---|---|---|---|---|
| 1 | Deduct stock without sorting the products by id | ordered locking (no deadlocks, deterministic stamping) | killed | `StockLedgerUnitTests.deductProcessesLinesInProductOrderAndStampsTheAverageCost` |
| 2 | Round the cost of goods **down** instead of half-up | money rounding | killed | `DeliveryServiceUnitTests` (cost of goods 48.33 scenario) |
| 3 | Refuse a refund **equal to** the amount held (`>` changed to `>=` in the cap check) | refunds may use up the payment held, to the centavo | killed | `FinanceLedgerUnitTests` (cap boundary) |
| 4 | Use UTC instead of Manila for the ledger date | business date is the Manila date | killed | `DeliveryServiceUnitTests` (year-boundary scenario, `DLV-2027-0005`) |
| 5 | Accept a completion time earlier than the start | `markDone` time rule | killed | `DeliveryStateMachineTests` |
| 6 | Truncate the average instead of rounding half-up | costing | killed | `WeightedAverageCostTests` (tie case 0.0101, hand scenario) |
| 7 | Remove the row lock (`lockById`) from cancel | one cancellation wins; no double reversal | killed | `DeliveryConcurrencyTests.concurrentCancellations...`, `...doneAndCancelRace...` |
| 8 | Call a no-argument `now()` in production code | time comes from the injected `Clock` | killed | `CodingRulesTests` (no-arg `now()` rule) |
| 9 | Make a ledger implementation `public` | module encapsulation | killed | `CodingRulesTests` (`*Api` implementations non-public) |
| 10 | Make an entity `public` | module encapsulation | killed | `CodingRulesTests` (entities not public) |
| 11 | Add a `System.out.println` call to production code | logging goes through the logger | killed | `CodingRulesTests` (no standard streams) |

Notes:

- The first attempt at mutation 11 put the statement inside a `//` comment, which of course survived; the break must be real code.
  This is the reason the procedure insists on running the tests and reading the failure, not just on editing the file.
- Mutations 1-6 are logic breaks proven by the pure unit tests (about a second to run), which is the point of having them.
- Mutation 7 needs the PostgreSQL tests; without the lock the race produced a double reversal or two winners.

## Repeating a check

1. Copy the repository to a scratch folder outside the project (`robocopy` or `cp -r`, without `target`).
2. Apply the break to `src/main` there.
3. `./mvnw -Dtest=<TestClass> test` in the scratch copy; expect a failure that names the broken rule.
4. Delete the copy. Never apply a break in the real working tree.
