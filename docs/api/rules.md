# Business rules the schema cannot express

Field shapes, limits and statuses are in `openapi.json`. This page holds the behaviour behind them.

## Catalog

Reading is open to both roles; writing is OWNER only. Records are deactivated, never deleted. A **package** is a fixed set of products sold together at a fixed price (seed: Small 70,000, Medium 160,000, Commercial 700,000 PHP). Customers buy packages, not single products. There are no transfers between branches: each branch's stock is separate.

## Stock

An **append-only ledger of movements**; balances are derived from it. A mistake is fixed by a correcting `ADJUSTMENT` (owner only), never an edit.

- Types: `OPENING` and `ADJUSTMENT` (owner only), `PURCHASE`, `DONATION`, `USAGE`. `DELIVERY_OUT` and `DELIVERY_REVERSAL` are produced only by deliveries (`422` if sent here).
- `quantityDelta` is signed: positive for opening, purchase and donation; negative for usage; any non-zero for adjustment.
- A `PURCHASE` requires `unitCost` and `paymentMethod`, and in the same transaction appends a `STOCK_PURCHASE` ledger row and updates the **weighted average cost**. Opening stock and donations cost 0.
- A movement that would take stock below zero is `409` and saves nothing.
- Assistants get quantities but no `averageCost`; they see `unitCost` only on `PURCHASE` rows they recorded.
- **Low stock.** A reorder level is per product and applied per branch: a branch is low when it holds **at most** the level; a product is low when any branch is low, or when it has a level and no branch has ever held it; a product without a level is never low. `GET /api/stock/summary?lowOnly=` lists products with per-branch quantities and flags.

## Finance

An append-only ledger. A mistake is corrected by a reversal or opposite entry. There is no free-form "add entry" call: purchases, expenses and deliveries create entries. Amounts are positive; the type fixes the direction.

| Type | Meaning | Cash |
|---|---|---|
| `SALE` / `SALE_REVERSAL` | revenue recognised / cancelled | none |
| `COST_OF_GOODS` / `COGS_REVERSAL` | cost of stock delivered / cancelled | none |
| `PAYMENT_IN` | customer payment received | in |
| `REFUND` | money handed back | out |
| `STOCK_PURCHASE` | paid for inventory | out |
| `EXPENSE` | operating cost | out |

Owners read every type; assistants read only sales, reversals, payments and refunds (a request for a cost type returns an empty page).

## Deliveries

A delivery sells packages from one branch and is **paid in full when created**. Status: `ACTIVE -> DONE`, `ACTIVE -> CANCELLED`, `DONE -> RETURNED`; `CANCELLED` and `RETURNED` are final. A Done delivery cannot be cancelled (return it). Deliveries are never edited or deleted; line names and prices are frozen at sale time.

- **Create** (any role), in **one transaction**: deduct every package's contents from the branch (all-or-nothing), then write `SALE`, `COST_OF_GOODS` (at the branch's average cost at that moment) and `PAYMENT_IN`. `unitPrice` per line optionally overrides the package price for this delivery only.
- **Done** (any role) has no stock or money effect.
- **Cancel** (ACTIVE) and **return** (DONE, whole delivery only): stock goes back at its original cost and `SALE_REVERSAL` and `COGS_REVERSAL` are written. The payment is **kept** as `retainedPayment`; money leaves only through a refund.
- **Refund** (CANCELLED or RETURNED): partial refunds allowed until `retainedPayment` reaches 0; above it is `422`.
- **Receipt** (owner): cost, gross profit and margin per line. The ordinary `GET {id}` never contains cost or profit.
- **List filters** (all optional, ANDed): `status`, `branchId`, `customer` (case-insensitive contains), `from`/`to`. Dates filter the day the trip *started*, read in Manila time. Always newest first; `sort` is ignored. `GET /api/deliveries/summary` takes the same filters except `status` and returns counts per status.

### Idempotency-Key

Send an optional `Idempotency-Key` header (1-100 characters, a UUID is fine) and reuse it on every retry of the **same** submission.

| Situation | Answer |
|---|---|
| First request | `201` and the new delivery |
| Same key, user and body | `200` and the **original** delivery; nothing new happens |
| Same key, different body | `422 RULE_VIOLATION` |
| Same key while the first is still running (over about 2.5 s) | `409 REQUEST_IN_PROGRESS` with `Retry-After` |
| First attempt failed (400, 404, 409, 422) | Key is **not** used up; retry with a corrected request |
| Another user, same key | Independent: keys are per user |

Two simultaneous requests with one key create exactly one delivery.

## Reports

Read-only, from the finance ledger and stock. Periods are inclusive Manila business dates, `from <= to`, at most 366 days (`422` otherwise).

- **Net sales** = `SALE` - `SALE_REVERSAL`. **Net collected** = `PAYMENT_IN` - `REFUND`.
- **Cash movement** = net collected - `STOCK_PURCHASE` - `EXPENSE`, also split per payment method.
- **Profit** = net sales - (`COST_OF_GOODS` - `COGS_REVERSAL`) - `EXPENSE`. Purchases are cash out only; their cost reaches profit when the stock is delivered, so it is never subtracted twice.
- **Inventory value** = quantity x average cost, as of now (no history). Owner only.
- **Retained on cancelled deliveries** is the money still held for cancelled or returned deliveries, as of `to`.
- **Dashboard** (`today`, `monthToDate`, low-stock count, retained payments, owner-only inventory value) and **series** (`granularity=DAY|MONTH`, a point for every day or month, zeros where nothing happened, edge months cut to the period, plus totals equal to the matching report) use these same definitions.
- Assistants get sales and collection figures only; costs, cash movement, profit and valuation are omitted, in JSON and CSV alike.

## CSV exports

`GET /api/finance/entries.csv` and `/api/reports/{summary,profit,inventory-value,series,profit-series}.csv` mirror the JSON endpoint of the same name with the same roles and errors. Fetch with the bearer header and save the blob; the file name is in `Content-Disposition`.

- UTF-8 **with a BOM** (Excel shows "Peña" correctly), CRLF line ends, RFC 4180 quoting, plain numbers, ISO dates.
- **Formula protection:** a text cell starting with `=`, `+`, `-`, `@`, tab or CR gets a leading apostrophe. Numeric cells are untouched.
- Each file comes from one database read, so it is a consistent snapshot. The ledger export is streamed with no `Content-Length`: treat a connection error as an incomplete file. Only three exports run at once; a fourth gets `429` with `Retry-After`.
- Report CSVs have a `metric,value` layout (summary, profit), a per-product table with a closing `TOTAL` row (inventory value), or one `POINT` row per day or month plus a `TOTAL` row (series).

## Audit trail (OWNER)

An append-only record of who changed master data: users, products, branches, packages. It is written in the same transaction as the change, and the database refuses `UPDATE`, `DELETE` and `TRUNCATE` on it. Actions: `CREATED`, `UPDATED` (lists only changed fields as `field: old -> new`), `PASSWORD_RESET`, `PASSWORD_CHANGED`, `SIGNED_OUT`. A no-op update is not recorded; summaries never contain secrets. Filters: `entityType`, `entityId`, `actorId`, `from`, `to`. Money and stock have their own ledgers, each row carrying its author.

## User administration (OWNER)

The last active owner cannot be deactivated or demoted, and nobody can deactivate their own account (`422`). Deactivation ends that user's sessions at once and reactivation does not revive old tokens.
