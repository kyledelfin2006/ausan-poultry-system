# Ledgers and costing

The two things the business cannot afford to get wrong are **how many units it has** and **how much money it made**. Both are
kept as append-only ledgers; everything else (balances, reports) is derived from them.

## 1. Principles

1. **Append-only.** A stock movement or financial entry is never updated or deleted. A mistake is corrected by a new row: a
   *reversal* of the original, or an *adjustment*. History is therefore complete and auditable
   ([ADR-0002](decisions/0002-append-only-ledgers.md)).
2. **The ledger is the truth, the balance is a cache.** `stock_balance` is kept in step by the same statement that is
   written alongside each movement; the sum of a product's movements at a branch always equals its balance
   (`WeightedAverageCostTests` checks this after every one of 300 random steps).
3. **The type gives the direction, never the sign.** Amounts and costs are positive; stock movements carry a signed quantity whose
   legal sign is fixed by the type.
4. **Who and when on every row.** `created_by` and `created_at` are mandatory; `occurred_on` is the business date in Manila.

## 2. Stock movement types

| Type | Sign | Entered by | Reason required | Cost recorded | Cash |
|---|---|---|---|---|---|
| `OPENING` | + | **owner only**, manual | no | 0 unless given | none |
| `PURCHASE` | + | anyone, manual | no | the unit cost (must be > 0) | `STOCK_PURCHASE` (cash out) |
| `DONATION` | + | anyone, manual | no | 0 | none |
| `USAGE` | - | anyone, manual | yes | the average cost at that moment | none |
| `ADJUSTMENT` | + or - | **owner only**, manual | yes | the average cost at that moment | none |
| `DELIVERY_OUT` | - | delivery flow only | n/a | the average cost at that moment | none (cost of goods is recorded by the delivery) |
| `DELIVERY_REVERSAL` | + | delivery flow only (cancel) | n/a | the cost of the movement it undoes | none |

A zero quantity is never legal. A purchase of free stock must be recorded as a `DONATION`, otherwise it would be a zero-peso
payment that silently lowers the average.

## 3. Weighted average cost

The business values stock at the **weighted average cost per product per branch**. It is maintained by one SQL statement
(`StockBalanceRepository.applyDelta`) so that the quantity check and the average update are a single atomic step:

```
new average = round( (old quantity x old average + added quantity x unit cost) / (old quantity + added quantity), 4 )
```

- It applies to incoming stock **with a cost**: purchases, donations and openings (cost 0), and reversals (the cost they left with).
- Stock going out, and incoming stock **without** a cost (a positive adjustment), leave the average unchanged. Outgoing movements
  are *stamped* with the current average, which is the cost of goods.
- An empty shelf keeps its last average, but it carries no weight: the next purchase onto zero stock sets the average to that
  purchase's cost exactly.
- Four decimals are stored (`numeric(14,4)`), half-up. Each step rounds, so the stored value can differ from the exact mean by a
  rounding error of 0.00005 per step; the value that matters (cost of goods) is rounded to centavos per movement.

### Worked example

The scenario that `WeightedAverageCostTests.theAverageFollowsTheBusinessRulesStepByStep` proves against PostgreSQL:

| Step | Event | Quantity | Average cost | Calculation |
|---|---|---|---|---|
| 1 | Purchase 10 at 100.00 | 10 | 100.0000 | first stock: the cost itself |
| 2 | Purchase 5 at 130.00 | 15 | 110.0000 | (10 x 100 + 5 x 130) / 15 = 1650 / 15 |
| 3 | Donation of 15 | 30 | 55.0000 | (15 x 110 + 15 x 0) / 30: free stock dilutes the average |
| 4 | Usage of 10 | 20 | 55.0000 | going out never changes the average |
| 5 | Purchase 3 at 99.99 | 23 | 60.8683 | (20 x 55 + 3 x 99.99) / 23 = 1399.97 / 23 = 60.86826... |
| 6 | Usage of 23 | 0 | 60.8683 | an empty shelf remembers the last average |
| 7 | Purchase 4 at 10.00 | 4 | 10.0000 | (0 x 60.8683 + 4 x 10) / 4: the old average has no weight |
| 8 | Adjustment of +6 | 10 | 10.0000 | an adjustment has no cost of its own |

Rounding tie: 199 units at 0.01 then 1 at 0.02 give exactly 0.01005, stored as **0.0101** (half-up, not half-even).

### Cost of goods of a delivery

Each product leaving stock is a `DELIVERY_OUT` movement stamped with the average at that moment; its cost is
`|quantity| x average`, rounded half-up to centavos *per movement*, and the delivery's cost of goods is the sum of those
rounded values. A cancellation puts the units back at the cost they left with.

## 4. Financial entry types

| Type | Meaning | Cash | Counts in |
|---|---|---|---|
| `SALE` | Revenue recognised (delivery total) | none | sales |
| `SALE_REVERSAL` | Revenue cancelled | none | sales (subtracted) |
| `COST_OF_GOODS` | Cost of the stock delivered | none | profit |
| `COGS_REVERSAL` | That cost cancelled | none | profit (subtracted) |
| `PAYMENT_IN` | Money received from a customer | in | collections, cash movement |
| `REFUND` | Money handed back | out | collections (subtracted), cash movement |
| `STOCK_PURCHASE` | Cash paid to a supplier | out | cash movement (not profit, see below) |
| `EXPENSE` | Operating cost (fuel, wages, repairs) | out | profit, cash movement |

Rules: amounts are positive with at most two decimals; a cash entry names its payment method and a non-cash entry has none;
sales, cost, payments and refunds always belong to a source record; an expense has none, so it must carry a description;
cash dates cannot be in the future; refunds are owner only and capped by the payment still held
(`payments received - refunds paid` for that source); a zero cost of goods (donated stock) writes nothing.

## 5. What each business event writes

Example from `DeliveryTests`: a package of 24 chickens and 2 cages (price 70,000.00); chickens were bought at 10.00 and cages at
50.00. The customer buys **2 packages** and pays by GCash.

| Event | Stock ledger | Financial ledger |
|---|---|---|
| **Create** | `DELIVERY_OUT` chicken -48 at 10.0000; `DELIVERY_OUT` cage -4 at 50.0000 | `SALE` 140,000.00; `COST_OF_GOODS` 680.00 (48 x 10 + 4 x 50); `PAYMENT_IN` 140,000.00 |
| **Mark done** | nothing | nothing |
| **Cancel** (owner) | `DELIVERY_REVERSAL` chicken +48 at 10.0000; cage +4 at 50.0000 | `SALE_REVERSAL` 140,000.00; `COGS_REVERSAL` 680.00; the payment stays: **140,000.00 held** |
| **Refund 40,000.00** (owner) | nothing | `REFUND` 40,000.00: 100,000.00 still held |
| **Refund 100,000.01** | nothing | refused (more than held) |

After the cancellation the sale and its cost net to zero in every report, while the customer's money is still shown as
*retained on cancelled deliveries* until it is refunded.

## 6. From ledger to reports

Reports read the ledger through `FinanceApi` aggregates (computed in the database) and the balances through `StockApi`; they
never write. The exact definitions are in [../reports.md](../reports.md). In short, for an inclusive period of Manila business dates:

```
net sales    = SALE - SALE_REVERSAL
profit       = net sales - (COST_OF_GOODS - COGS_REVERSAL) - EXPENSE
cash movement = PAYMENT_IN - REFUND - STOCK_PURCHASE - EXPENSE
```

`STOCK_PURCHASE` is deliberately **not** in profit: buying stock is cash out, but its cost reaches profit when the stock is
delivered (as `COST_OF_GOODS`). Subtracting both would charge the same stock twice.

## 7. Invariants and what proves them

| Invariant | Enforced by | Proven by |
|---|---|---|
| Quantity never negative | one atomic `UPDATE ... where quantity + delta >= 0`; `check (quantity >= 0)` | `StockLedgerUnitTests` (refusal path), `DeliveryConcurrencyTests` (races) |
| Balance = sum of movements | same statement writes both inside one transaction | `WeightedAverageCostTests.randomSequences...` |
| Average cost follows the formula | `applyDelta` SQL | `WeightedAverageCostTests` (hand-calculated and model-based) |
| A movement or entry is reversed at most once | `existsByReversesId` filter; `unique (reverses_id)` | `StockLedgerTests`, `DeliveryConcurrencyTests.concurrentCancellations...` |
| Money direction matches type | `FinancialEntry.of`; `ck_entry_direction` | `FinanceLedgerUnitTests`, `FinanceAndCostTests` |
| Refund never exceeds payment held | `FinanceLedger.recordRefund` under a row lock | `FinanceLedgerUnitTests`, `DeliveryConcurrencyTests.concurrentRefunds...` |
| Failed delivery leaves nothing | one transaction | `DeliveryTests`, `DeliveryEdgeCaseTests` |
