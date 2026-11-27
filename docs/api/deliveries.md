# Deliveries

A delivery sells one or more [packages](catalog.md#packages) to a customer from one branch. It is paid in full when it
is created.

Status flow: `ACTIVE -> DONE`, `ACTIVE -> CANCELLED`, or `DONE -> RETURNED`. `CANCELLED` and `RETURNED` are final.
**A Done delivery cannot be cancelled; if the goods come back, return it** (the whole delivery, see
[`/return`](#post-apideliveriesidreturn-owner)). Saved deliveries are never edited or deleted.

`DeliveryView`, returned by every delivery endpoint:

```json
{ "id": 7, "code": "DLV-2026-0007", "status": "ACTIVE", "customerName": "Juan dela Cruz", "driverName": "Pedro Reyes",
  "fromBranchId": 1, "destination": "Brgy. San Isidro, Tarlac", "startedAt": "2026-11-20T03:00:00Z", "completedAt": null,
  "total": 70000.00, "paymentMethod": "GCASH", "cancelReason": null, "returnReason": null, "retainedPayment": null,
  "lines": [ { "packageId": 1, "packageName": "Package Small", "quantity": 1, "unitPrice": 70000.00, "lineTotal": 70000.00 } ],
  "createdBy": 2, "createdAt": "2026-11-20T03:00:01Z" }
```

`retainedPayment` is only set for `CANCELLED` and `RETURNED` deliveries: the money received and not yet refunded. Line names and prices
are frozen at sale time, so later package edits never change an old delivery.

## `POST /api/deliveries` (any role)

| Field | Type | Required | Limits |
|---|---|---|---|
| customerName | string | yes | non-blank, max 120 |
| driverName | string | yes | non-blank, max 120 (free text) |
| fromBranchId | long | yes | active branch |
| destination | string | yes | non-blank, max 255 |
| startedAt | instant | no | default now; not in the future; assistants may backdate at most 7 Manila calendar days, owners may use any past date |
| paymentMethod | enum | yes | `CASH`, `GCASH`, `BANK_TRANSFER`, `OTHER` |
| lines | array | yes | non-empty; each `{ packageId (required, active package, once per delivery), quantity (> 0), unitPrice (optional, >= 0.01, 2 decimals) }` |

`unitPrice` defaults to the package price; supplying it overrides the price for this delivery only. The sale is paid in
full at creation. In **one transaction** the system deducts each package's contents from the branch (all-or-nothing),
records `SALE`, `COST_OF_GOODS` (at the branch's weighted average cost at that moment) and `PAYMENT_IN`.

`201` returns the `DeliveryView`. Errors: `400`, `404` (unknown package or branch), `409` (`Insufficient stock for
product X at branch Y`; nothing is saved), `422` (inactive package or branch, future start, duplicate package line).

### Idempotency: safe retries with `Idempotency-Key`

If the network drops after you sent the request, you do not know whether the delivery was created. Send an optional
`Idempotency-Key` header (1-100 characters; a UUID is fine) and resend the **same key** on every retry of the **same**
submission:

| Situation | Answer |
|---|---|
| First request with this key | `201` and the new delivery |
| Same key again, same user, same request | `200` and the **original** delivery; nothing new is created or deducted |
| Same key, same user, but a different request body | `422` (`RULE_VIOLATION`, `Idempotency-Key was already used with a different request`) |
| Same key again while the first request is still running for more than about 2.5 s | `409` (`REQUEST_IN_PROGRESS`) with `Retry-After: 1`; repeat the identical request with the same key after the delay |
| Same key used by a different user | Independent: keys are per user |
| First attempt failed (400, 404, 409, 422) | The key is **not** used up; you may retry it with a corrected request |
| Key empty or longer than 100 characters | `400` |

Generate one key per form submission (when the user opens the form or presses "Create"), reuse it for automatic
retries and double-clicks, and discard it after a success. Two simultaneous requests with the same key create exactly one
delivery.

## `GET /api/deliveries?status=&page=&size=` (any role)

`status` is optional (`ACTIVE`, `DONE`, `CANCELLED`, `RETURNED`). A [paged](README.md#paging) list of `DeliveryView`, newest
`startedAt` first.

## `GET /api/deliveries/{id}` (any role)

`200` `DeliveryView`; `404` if unknown.

## `POST /api/deliveries/{id}/done` (any role)

Body `{ "completedAt": "2026-11-20T06:30:00Z" }` (required). No stock or money effect. `200` returns the delivery with
`status: DONE`. Errors: `400`, `404`, `409` (not ACTIVE).

## `POST /api/deliveries/{id}/cancel` (OWNER)

Body `{ "reason": "Customer cancelled the order" }` (required, max 255). Restores the stock at its original cost and
appends `SALE_REVERSAL` and `COGS_REVERSAL`. The payment is **kept**: money only leaves through a refund. `200` returns
the delivery with `status: CANCELLED` and `retainedPayment` equal to what was paid. Errors: `400`, `403`, `404`, `409`
(already cancelled, or Done).

## `POST /api/deliveries/{id}/return` (OWNER)

Body `{ "reason": "Customer rejected the goods" }` (required, max 255). For a **Done** delivery whose goods came back as a
whole: the stock goes back to sellable stock at its original cost and `SALE_REVERSAL` and `COGS_REVERSAL` are appended,
exactly as for a cancellation. The payment is **kept**; hand money back with the refund endpoint. `200` returns the
delivery with `status: RETURNED`, `returnReason` and `retainedPayment` equal to what was paid. Errors: `400`, `403`, `404`,
`409` (not Done: active, cancelled or already returned). Part-returns are not supported.

## `POST /api/deliveries/{id}/refund` (OWNER)

| Field | Type | Required | Limits |
|---|---|---|---|
| amount | number | yes | >= 0.01, 2 decimals, not above `retainedPayment` |
| method | enum | yes | payment method used to hand the money back |
| description | string | no | max 255; default `Refund for DLV-...` |

Partial refunds are allowed; repeat until `retainedPayment` is 0. `200` returns the delivery with the reduced
`retainedPayment`. Errors: `400`, `403`, `404`, `422` (delivery is not CANCELLED or RETURNED, or amount exceeds the payment still
held).
