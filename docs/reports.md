# Period reports (slice 5)

All figures are PHP, scale 2, for an inclusive period `[from, to]` of Manila business dates (`occurred_on`).
Periods must satisfy `from <= to` and be at most 366 days, otherwise HTTP 422.

| Endpoint | Roles | Content |
|---|---|---|
| `GET /api/reports/summary?from=&to=` | OWNER, ASSISTANT | sales, collections, stock purchases, expenses, cash movement (+ by method) |
| `GET /api/reports/profit?from=&to=` | OWNER only (403 for assistants) | net sales, cost of goods, gross profit, expenses, profit |
| `GET /api/reports/inventory-value` | OWNER, ASSISTANT | per branch and product: quantity x weighted average cost, branch and grand totals (as of now) |

## Definitions

- Net sales = SALE - SALE_REVERSAL. Net collected = PAYMENT_IN - REFUND.
- Cash movement = PAYMENT_IN - REFUND - STOCK_PURCHASE - EXPENSE; `byMethod` is the same net per payment method.
- Profit = net sales - (COST_OF_GOODS - COGS_REVERSAL) - EXPENSE. Stock purchases are cash out only: their cost reaches profit
  when the stock is delivered (as COST_OF_GOODS), so they are never subtracted twice.
- **Retained payments on cancelled deliveries** (`collections.retainedOnCancelledDeliveries`): evaluated as of `to`. For every
  source with a SALE_REVERSAL dated on or before `to`, all PAYMENT_IN minus all REFUND dated on or before `to`. It is cumulative
  (not limited to `from`), is reported separately, and is never part of sales or profit.
- Missing entry types and unused payment methods report 0.00.
- Underlying entries for any figure: `GET /api/finance/entries?from=&to=&type=`.
