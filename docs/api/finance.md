# Finance (append-only ledger)

Entries are never edited or deleted. A mistake is corrected by a reversal or a new opposite entry, so the history stays
auditable. Both roles may record expenses. Owners read every ledger type; assistants see sales, sale reversals, payments
and refunds, while purchase and expense entries are filtered out.

## `POST /api/finance/expenses` (any role)

Records an operating cost (fuel, wages, repairs, ...).

| Field | Type | Required | Limits |
|---|---|---|---|
| amount | number | yes | >= 0.01, up to 2 decimals |
| method | `CASH` \| `GCASH` \| `BANK_TRANSFER` \| `OTHER` | yes | |
| occurredOn | date | yes | not in the future; assistants may backdate at most 7 calendar days, owners may use any past date |
| description | string | yes | non-blank, max 255 |

`201`:

```json
{ "id": 31, "type": "EXPENSE", "cashDirection": "OUT", "amount": 1500.00, "method": "CASH",
  "occurredOn": "2026-11-20", "description": "Fuel for delivery truck", "sourceType": null, "sourceId": null,
  "reversesId": null, "createdBy": 1, "createdAt": "2026-11-20T02:20:00Z" }
```

Errors: `400`, `401`, `422` (future date or an assistant date more than 7 days in the past).

## `GET /api/finance/entries?from=&to=&type=&page=&size=` (any role)

`from` and `to` are inclusive dates and both required; `to` must not be before `from` (`422`). `type` is optional. A
[paged](README.md#paging) list of entries, newest first. Assistants receive only `SALE`, `SALE_REVERSAL`,
`PAYMENT_IN`, and `REFUND`; requesting a cost entry type returns an empty page. Owners can read every type.

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
(purchases, expenses) are created by those endpoints; the ledger has no free-form "add entry" call. CSV export follows
the same role filter as the JSON ledger.

These are the rows behind every figure in the [reports](reports.md).
