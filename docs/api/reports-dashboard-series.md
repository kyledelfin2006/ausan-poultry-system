# Reports: dashboard and time series

Additions to the report endpoints (the summary, profit and inventory-value reports are documented in
[reports.md](reports.md)). All figures use the same definitions as those reports, so a dashboard or chart can never
disagree with them. PHP, 2 decimals; dates are Manila business dates.

| Endpoint | Roles | Content |
|---|---|---|
| `GET /api/reports/dashboard` | OWNER, ASSISTANT (cost fields: owner) | today, month to date, low-stock count, retained payments; inventory value for owner |
| `GET /api/reports/series?from=&to=&granularity=` | OWNER, ASSISTANT (cost fields: owner) | sales and collection figures per day or month for assistants; full summary for owner |
| `GET /api/reports/profit-series?from=&to=&granularity=` | **OWNER only** | profit figures per day or month |

## `GET /api/reports/dashboard`

No parameters. "Today" and "this month" follow the business zone (Asia/Manila), not UTC: just after midnight in Manila it is
already the next day.

```json
{
  "asOf": "2026-11-20",
  "today":       { "from": "2026-11-20", "to": "2026-11-20", "netSales": 70000.00, "netCollected": 70000.00,
                   "stockPurchases": 0.00, "otherExpenses": 500.00, "cashMovement": 69500.00, "profit": 38250.00 },
  "monthToDate": { "from": "2026-11-01", "to": "2026-11-20", "netSales": 210000.00, "netCollected": 180000.00,
                   "stockPurchases": 15000.00, "otherExpenses": 2100.00, "cashMovement": 162900.00, "profit": 118000.00 },
  "inventoryValue": 245000.00,
  "lowStockProducts": 2,
  "retainedOnCancelledDeliveries": 0.00
}
```

- For owners, the figures per period are those of `GET /api/reports/summary` (net sales, net collected, stock purchases,
  other expenses, cash movement). `profit` is that of `GET /api/reports/profit`.
- Assistants receive `netSales` and `netCollected`; `stockPurchases`, `otherExpenses`, `cashMovement`, and `profit` are
  omitted. The top-level `inventoryValue` is also omitted. `lowStockProducts` and `retainedOnCancelledDeliveries` remain.
- `monthToDate` runs from the first of the month to today; on the 1st it is the single day.
- `inventoryValue` is the stock on hand now at weighted average cost (the grand total of `inventory-value`); it is
  owner-only.
- `lowStockProducts` is the number of products that are low (see [stock-overview.md](stock-overview.md)).
- `retainedOnCancelledDeliveries` is the money still held for cancelled deliveries as of today.

## Series

Query parameters: `from`, `to` (`yyyy-MM-dd`, inclusive, at most 366 days apart) and `granularity` = `DAY` (default) or
`MONTH`. Every day (or calendar month) of the period has a point, with `0.00` where nothing happened, so a chart has no gaps.
A month at an edge of the period is cut to the period: the first point starts on `from`, the last ends on `to`.

`GET /api/reports/series`:

```json
{
  "period": { "from": "2026-01-15", "to": "2026-03-20" }, "granularity": "MONTH",
  "points": [
    { "from": "2026-01-15", "to": "2026-01-31", "netSales": 0.00, "netCollected": 0.00,
      "stockPurchases": 0.00, "otherExpenses": 100.00, "cashMovement": -100.00 }
  ],
  "totals": { "from": "2026-01-15", "to": "2026-03-20", "netSales": 5000.00, "netCollected": 6500.00,
              "stockPurchases": 1000.00, "otherExpenses": 150.00, "cashMovement": 5350.00 }
}
```

Assistants receive `netSales` and `netCollected` on each point and in `totals`; `stockPurchases`, `otherExpenses`, and
`cashMovement` are omitted. Owners receive every field. The same rule applies to `series.csv` columns.

| Field | Definition |
|---|---|
| `netSales` | SALE - SALE_REVERSAL |
| `netCollected` | PAYMENT_IN - REFUND |
| `stockPurchases` | STOCK_PURCHASE (cash out, not a profit figure) |
| `otherExpenses` | EXPENSE |
| `cashMovement` | PAYMENT_IN - REFUND - STOCK_PURCHASE - EXPENSE |

`GET /api/reports/profit-series` (owner) has the same envelope with these point fields:

```json
{ "from": "2026-02-01", "to": "2026-02-28", "netSales": 5000.00, "costOfGoods": 240.00,
  "grossProfit": 4760.00, "otherExpenses": 50.00, "profit": 4710.00 }
```

`costOfGoods` is COST_OF_GOODS - COGS_REVERSAL. Stock purchases are never subtracted from profit (their cost reaches profit
when the stock is delivered), exactly as in the profit report.

**`totals` always equal the report for the same period** (`summary` / `profit`), and the points add up to `totals`.

Errors: `422` for a backwards period or one longer than 366 days; `400` for a missing/unparsable date or a `granularity` that is
not `DAY` or `MONTH`; `401` without a token; `403` on `profit-series` for an assistant.
