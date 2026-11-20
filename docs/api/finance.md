# Finance (append-only ledger)

Entries are never edited or deleted. A mistake is corrected by a reversal or a new opposite entry, so the history stays
auditable. Both roles may record expenses and read the ledger.

## `POST /api/finance/expenses` (any role)

Records an operating cost (fuel, wages, repairs, ...).

| Field | Type | Required | Limits |
|---|---|---|---|
| amount | number | yes | >= 0.01, up to 2 decimals |
| method | `CASH` \| `GCASH` \| `BANK_TRANSFER` \| `OTHER` | yes | |
| occurredOn | date | yes | not in the future |
| description | string | yes | non-blank, max 255 |

`201`:

```json
{ "id": 31, "type": "EXPENSE", "cashDirection": "OUT", "amount": 1500.00, "method": "CASH",
  "occurredOn": "2026-11-20", "description": "Fuel for delivery truck", "sourceType": null, "sourceId": null,
  "reversesId": null, "createdBy": 1, "createdAt": "2026-11-20T02:20:00Z" }
```

Errors: `400`, `401`, `422` (future date).

## `GET /api/finance/entries?from=&to=&type=&page=&size=` (any role)

`from` and `to` are inclusive dates and both required; `to` must not be before `from` (`422`). `type` is optional. A
[paged](README.md#paging) list of the entry shape above, newest first.

Entry types (amounts are always positive; the type fixes the direction):

| type | meaning | cashDirection |
|---|---|---|
| `SALE` / `SALE_REVERSAL` | revenue recognised / cancelled | NONE |
| `COST_OF_GOODS` / `COGS_REVERSAL` | cost of stock delivered / cancelled | NONE |
| `PAYMENT_IN` | customer payment received | IN |
| `REFUND` | money handed back | OUT |
| `STOCK_PURCHASE` | cash paid for inventory | OUT |
| `EXPENSE` | operating cost | OUT |

Entries tied to a delivery carry `sourceType: "DELIVERY"` and `sourceId`. Entries recorded by other endpoints
(purchases, expenses) are created by those endpoints; the ledger has no free-form "add entry" call.

These are the rows behind every figure in the [reports](reports.md).
