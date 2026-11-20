# Reports

Read-only figures computed from the [finance ledger](finance.md) and [stock](stock.md). All money is PHP with 2
decimals. A period is **inclusive** (`from` and `to` both count) and uses Manila business dates (`occurredOn`).

| Endpoint | Roles | Content |
|---|---|---|
| `GET /api/reports/summary?from=&to=` | OWNER, ASSISTANT | sales, collections, stock purchases, expenses, cash movement (+ by method) |
| `GET /api/reports/profit?from=&to=` | OWNER only (`403` for assistants) | net sales, cost of goods, gross profit, expenses, profit |
| `GET /api/reports/inventory-value` | OWNER, ASSISTANT | per branch and product: quantity x weighted average cost, branch and grand totals (as of now) |

`from` and `to` are required (`yyyy-MM-dd`; `400` if missing or malformed). Periods must satisfy `from <= to` and be at
most 366 days, otherwise `422` (`RULE_VIOLATION`).

## `GET /api/reports/summary?from=2026-11-01&to=2026-11-30`

```json
{ "period": { "from": "2026-11-01", "to": "2026-11-30" },
  "sales": { "gross": 70000.00, "cancelled": 0.00, "net": 70000.00 },
  "collections": { "received": 70000.00, "refunds": 0.00, "netCollected": 70000.00,
                   "retainedOnCancelledDeliveries": 0.00 },
  "stockPurchases": 12000.00,
  "otherExpenses": 1500.00,
  "cashMovement": { "total": 56500.00,
                    "byMethod": { "CASH": -13500.00, "GCASH": 70000.00, "BANK_TRANSFER": 0.00, "OTHER": 0.00 } } }
```

## `GET /api/reports/profit?from=2026-11-01&to=2026-11-30` (OWNER)

```json
{ "period": { "from": "2026-11-01", "to": "2026-11-30" },
  "netSales": 70000.00,
  "costOfGoods": { "delivered": 41000.00, "reversed": 0.00, "net": 41000.00 },
  "grossProfit": 29000.00,
  "otherExpenses": 1500.00,
  "profit": 27500.00 }
```

## `GET /api/reports/inventory-value`

```json
{ "branches": [ { "branchId": 1, "total": 38000.00,
                  "lines": [ { "productId": 2, "productName": "Chicken", "unit": "head", "quantity": 100,
                               "averageCost": 380.0000, "value": 38000.00 } ] } ],
  "grandTotal": 38000.00 }
```

`value` is `quantity x averageCost` rounded to centavos. The figure is *as of now*; there is no historical valuation.

## Definitions

- **Net sales** = `SALE` - `SALE_REVERSAL`. **Net collected** = `PAYMENT_IN` - `REFUND`.
- **Cash movement** = `PAYMENT_IN` - `REFUND` - `STOCK_PURCHASE` - `EXPENSE`; `byMethod` is the same net per payment
  method (every method is always present).
- **Profit** = net sales - (`COST_OF_GOODS` - `COGS_REVERSAL`) - `EXPENSE`. Stock purchases are cash out only: their cost
  reaches profit when the stock is delivered (as `COST_OF_GOODS`), so they are never subtracted twice.
- **Retained payments on cancelled deliveries** (`collections.retainedOnCancelledDeliveries`) are evaluated as of `to`:
  for every source with a `SALE_REVERSAL` dated on or before `to`, all `PAYMENT_IN` minus all `REFUND` dated on or
  before `to`. It is cumulative (not limited to `from`), is reported separately, and is never part of sales or profit.
- Missing entry types and unused payment methods report `0.00`.
- The underlying entries for any figure: `GET /api/finance/entries?from=&to=&type=`.
