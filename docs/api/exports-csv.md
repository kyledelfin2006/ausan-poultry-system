# CSV exports

Downloads of ledger entries and reports for spreadsheets. They carry exactly the figures of the JSON endpoints they mirror.

| Endpoint | Roles | Mirrors |
|---|---|---|
| `GET /api/finance/entries.csv?from=&to=&type=` | OWNER, ASSISTANT | `GET /api/finance/entries` (without paging) |
| `GET /api/reports/summary.csv?from=&to=` | OWNER, ASSISTANT | `GET /api/reports/summary` |
| `GET /api/reports/profit.csv?from=&to=` | **OWNER only** | `GET /api/reports/profit` |
| `GET /api/reports/inventory-value.csv` | **OWNER only** | `GET /api/reports/inventory-value` |
| `GET /api/reports/series.csv?from=&to=&granularity=` | OWNER, ASSISTANT | `GET /api/reports/series` |
| `GET /api/reports/profit-series.csv?from=&to=&granularity=` | **OWNER only** | `GET /api/reports/profit-series` |

Parameters, roles and errors are the same as for the JSON endpoint. Periods are at most 366 days (`422` otherwise, also for a
backwards period). Each file is produced from a single database read, so it is a consistent snapshot: an entry recorded while
the file is being written is either in it or not, and no row is ever repeated or skipped.

## Downloading from the browser

The endpoints need the `Authorization: Bearer` header, so a plain `<a href>` cannot be used. Fetch with the token, then save
the response as a file:

```ts
const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
const blob = await res.blob();                       // text/csv
// the suggested file name is in the Content-Disposition header: attachment; filename="..."
```

## File format

- `Content-Type: text/csv; charset=UTF-8` and `Content-Disposition: attachment; filename="..."`
  (for example `ledger-entries-2026-11-01-to-2026-11-30.csv`, `summary-2026-11-01-to-2026-11-30.csv`).
- UTF-8 **with a byte-order mark**, so Excel shows names such as "Dela Peña" correctly. A CSV parser should ignore the BOM.
- Lines end with CRLF. Cells with a comma, quote or line break are quoted, and inner quotes are doubled (RFC 4180).
- Numbers are plain (`-1000.00`, never quoted, no thousands separator). Dates are `yyyy-MM-dd`, timestamps ISO-8601 UTC.
- **Spreadsheet-formula protection:** a text cell that starts with `=`, `+`, `-`, `@`, a tab or a carriage return is written with
  a leading apostrophe (`'=SUM(A1)`), so a description typed by a user cannot run as a formula when opened in Excel.
  Numeric cells are never changed.
- A column with no value is empty.

## Layouts

**`entries.csv`**: header `id,occurredOn,type,cashDirection,amount,paymentMethod,description,sourceType,sourceId,reversesId,createdBy,createdAt`,
then one row per entry, newest date first and, within a day, newest id first (the order of the JSON list). With no entries
the file is just the header.

**`summary.csv`, `profit.csv`**: header `metric,value`, then rows such as `from`, `to`, `sales.gross`, `sales.net`,
`collections.netCollected`, `stockPurchases`, `cashMovement.total` and one `cashMovement.<METHOD>` row per payment method
(summary); `netSales`, `costOfGoods.net`, `grossProfit`, `profit` (profit). Assistants receive only sales and collection rows
from `summary.csv`; cost and cash-movement rows are omitted. `profit.csv` remains owner-only. Metric names are the JSON field paths.

**`inventory-value.csv` (owner only)**: header `branchId,productId,productName,unit,quantity,averageCost,value`, one row per product with
stock in each branch, then a closing row `,,TOTAL,,,,<grand total>`.

**`series.csv`, `profit-series.csv`**: header `row,from,to,<figures>`, one `POINT` row per day or month, then one `TOTAL` row for
the whole period (equal to the matching report). `profit-series.csv` is owner-only. `series.csv` omits cost and cash-movement
columns for assistants.
