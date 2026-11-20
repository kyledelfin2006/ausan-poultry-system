# Stock

Stock is an **append-only ledger of movements**; balances are derived from it. A movement is never edited or deleted: a
mistake is fixed by recording a correcting movement (an `ADJUSTMENT`, owner only). Each branch and product also keeps a
**weighted average cost**, updated by purchases and used as the cost of goods when stock is delivered.

## `POST /api/stock/movements`

| Field | Type | Required | Notes |
|---|---|---|---|
| productId | long | yes | active product |
| branchId | long | yes | active branch |
| type | enum | yes | `OPENING`, `PURCHASE`, `DONATION`, `USAGE`, `ADJUSTMENT`. (`DELIVERY_OUT` / `DELIVERY_REVERSAL` are produced by deliveries and rejected here with 422.) |
| quantityDelta | long | yes | **Signed.** `OPENING`/`PURCHASE`/`DONATION`: positive. `USAGE`: negative. `ADJUSTMENT`: any non-zero. |
| unitCost | number | PURCHASE | >= 0, up to 2 decimals. Required for `PURCHASE`; `OPENING`/`DONATION` default to 0. |
| paymentMethod | `CASH` \| `GCASH` \| `BANK_TRANSFER` \| `OTHER` | PURCHASE | How the supplier was paid. |
| reason | string | USAGE, ADJUSTMENT | max 255. For chickens, put the age in weeks here (e.g. `6 weeks old`). |
| occurredOn | date | yes | not in the future |

- A `PURCHASE` also appends a `STOCK_PURCHASE` row to the [finance ledger](finance.md) (cash out) in the same
  transaction and updates the weighted average cost.
- `OPENING` and `ADJUSTMENT` are **owner only** (`403` for an assistant); the other types are open to both roles.
- Opening stock and donations cost 0.

`201`:

```json
{ "id": 14, "productId": 2, "branchId": 1, "type": "PURCHASE", "quantityDelta": 100, "unitCost": 380.0000,
  "reason": null, "occurredOn": "2026-11-20", "sourceType": null, "sourceId": null, "reversesId": null,
  "createdBy": 1, "createdAt": "2026-11-20T02:15:00Z" }
```

Errors: `400`, `401`, `403` (assistant using OPENING/ADJUSTMENT), `404` (unknown product or branch), `409` (would take
stock below zero: `Insufficient stock for product 2 at branch 1`), `422` (wrong sign for the type, future date, inactive
product or branch, missing payment method on a purchase, a type that only deliveries may create).

## `GET /api/stock/balances?branchId=` (any role)

`branchId` is optional (omit it for all branches). `200`, a plain array:

```json
[ { "productId": 2, "productName": "Chicken", "unit": "head", "branchId": 1, "quantity": 100, "averageCost": 380.0000 } ]
```

## `GET /api/stock/movements?productId=&branchId=&page=&size=` (any role)

`productId` and `branchId` are required (`400` if missing). A [paged](README.md#paging) list of the movement shape
above, newest first. Movements produced by deliveries carry `sourceType: "DELIVERY"` and the delivery's `sourceId`;
reversals carry `reversesId`.
