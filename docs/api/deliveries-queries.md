# Deliveries: filters, status counts and receipt

Read-side additions to the delivery endpoints. Creating, finishing, cancelling and refunding are unchanged (see the main
API contract). Conventions (money, dates, errors, roles) are those of the main contract.

| Endpoint | Roles | Purpose |
|---|---|---|
| `GET /api/deliveries` | OWNER, ASSISTANT | list with filters, newest first |
| `GET /api/deliveries/summary` | OWNER, ASSISTANT | counts per status for the same filters |
| `GET /api/deliveries/{id}/receipt` | **OWNER only** | what the delivery cost and earned |

## `GET /api/deliveries`

Query parameters, all optional and combined with AND:

| Parameter | Type | Meaning |
|---|---|---|
| `status` | `ACTIVE`, `DONE`, `CANCELLED` | only this status |
| `branchId` | long | only deliveries whose stock came from this branch |
| `customer` | text, at most 120 characters | the customer name **contains** this text, ignoring case. `%` and `_` are plain characters. Blank means no search. |
| `from` | `yyyy-MM-dd` | first business date, inclusive |
| `to` | `yyyy-MM-dd` | last business date, inclusive |
| `page`, `size` | ints | paging (default page 0, size 20) |

**Which date is filtered:** the day the trip *started* (`startedAt`), read in the business zone Asia/Manila. That is also the
date the sale and the stock-out are booked on. A delivery started at `2021-07-14T16:00:00Z` is on 15 July, because Manila is
UTC+8: send `from=2021-07-15&to=2021-07-15` to find it.

**Order:** always newest first (start time, then id). A `sort` parameter is ignored; a client cannot reorder this list.

Response: the same page of delivery objects as before.

```json
{ "content": [ { "id": 7, "code": "DLV-2026-0007", "status": "DONE", "customerName": "Maria Dela Cruz", "...": "..." } ],
  "number": 0, "size": 20, "totalElements": 1, "totalPages": 1, "first": true, "last": true, "empty": false }
```

Errors: `422` when `from` is after `to`; `400` for a date that is not `yyyy-MM-dd`, an unknown `status`, or a `customer` longer
than 120 characters; `401` without a token.

## `GET /api/deliveries/summary`

Takes the same `branchId`, `customer`, `from` and `to` filters. There is **no** `status` filter: every status is counted, so
one call fills the "Active / Done / Cancelled" badges for the current selection.

```json
{ "active": 2, "done": 1, "cancelled": 1, "total": 4 }
```

Errors as for the list (`422` for a backwards range).

## `GET /api/deliveries/{id}/receipt` (OWNER only)

The owner's view of one delivery: the sale, what the stock cost, and the profit, per package line and per product. An
assistant gets `403`; an unknown id gets `404`. The ordinary `GET /api/deliveries/{id}` does **not** contain any cost or profit,
so assistants never see them.

```json
{
  "id": 7, "code": "DLV-2026-0007", "status": "ACTIVE",
  "customerName": "Maria Dela Cruz", "driverName": "Pedro Reyes", "fromBranchId": 1, "destination": "Tarlac",
  "startedAt": "2026-11-20T02:15:00Z", "completedAt": null, "paymentMethod": "GCASH",
  "cancelReason": null, "retainedPayment": null, "countedInProfit": true,
  "total": 70000.00, "costOfGoods": 31250.00, "grossProfit": 38750.00, "marginPercent": 55.36,
  "lines": [
    { "packageId": 1, "packageName": "Package Small", "quantity": 1, "unitPrice": 70000.00, "lineTotal": 70000.00,
      "costOfGoods": 31250.00, "profit": 38750.00 }
  ],
  "products": [
    { "productId": 2, "productName": "Chicken", "unit": "head", "quantity": 24, "unitCost": 380.0000, "totalCost": 9120.00 }
  ]
}
```

How to read it:

- `costOfGoods` is what the stock cost **at the average cost at the moment of the sale**. It is read from the delivery's own
  stock movements, so it always equals the cost of goods booked in the finance ledger for this delivery. It is never recomputed
  from today's average cost.
- `grossProfit = total - costOfGoods`; `marginPercent = grossProfit / total * 100`, 2 decimals.
- `products` lists the stock taken out, per product (`unitCost` has 4 decimals; `totalCost` is rounded to centavos as booked).
- A package line's cost is **allocated**: stock is taken out per product, not per line. When several lines use the same
  product, its cost is split in proportion to the units each line used (package quantity x units per package). Any centavo
  left over from rounding goes to the last line using the product, so `sum(lines[].costOfGoods) = costOfGoods` exactly.
- **Cancelled deliveries:** the figures describe the sale as it was made. Cancelling reverses the sale and its cost in the
  ledger, so `countedInProfit` is `false` and the delivery adds nothing to the profit report. `retainedPayment` is the money
  still held for the customer (null for other statuses). `cancelReason` is filled in.
