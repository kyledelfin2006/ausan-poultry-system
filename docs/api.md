# Ausan API contract (for the frontend)

Base URL: `http://localhost:8080` in development. All endpoints except login and `/actuator/health` need
`Authorization: Bearer <token>`. Bodies and responses are JSON (`Content-Type: application/json`).

The code-level reference is the Javadoc (`./mvnw javadoc:javadoc`, output in `target/reports/apidocs`). This document describes what is built today (slices 1-4). See [Pending](#pending-being-built) at the end.

## Conventions

| Topic | Rule |
|---|---|
| Money | PHP, JSON numbers with 2 decimals (`70000.00`). Never send more than 2 decimals. |
| Unit cost / average cost | Up to 4 decimals in responses (`312.5000`). |
| Dates | `occurredOn`, `from`, `to`: `yyyy-MM-dd` (business zone Asia/Manila). Must not be in the future. |
| Timestamps | ISO-8601 instants, e.g. `2026-11-20T02:15:00Z`. |
| Ids | `long`. |
| Paging | Paged endpoints take `?page=0&size=20&sort=...` and return a Spring `Page`: `{ "content": [...], "page": { "size", "number", "totalElements", "totalPages" } }`. |
| Roles | `OWNER` or `ASSISTANT`, in the token and in the login response. |

### Errors

Errors use RFC 9457 ProblemDetail (`Content-Type: application/problem+json`):

```json
{ "title": "Unprocessable Content", "status": 422,
  "detail": "Date cannot be in the future", "instance": "/api/stock/movements" }
```

| Status | When |
|---|---|
| 400 | Bean validation failed or malformed JSON/params. `detail` is `field message; field message`, e.g. `quantity must be greater than 0`. |
| 401 | Missing, invalid or expired token; also wrong credentials on login. Empty body. |
| 403 | Authenticated but the role may not do this (owner-only action by an assistant). |
| 404 | The referenced record does not exist (`detail`: `Delivery 99 not found`-style). |
| 409 | Conflict: insufficient stock, username already taken, delivery is no longer ACTIVE (done/cancelled twice, cancel after Done), unique-constraint clash. |
| 422 | A business rule was violated (future date, inactive product/branch/package, missing payment method, refund above what was paid, duplicate item, ...). |

Rule of thumb for the UI: 400 = fix the form, 409/422 = show `detail` to the user, 401 = go to login, 403 = hide the action.

### Who may call what

| Area | OWNER | ASSISTANT |
|---|---|---|
| Login | yes | yes |
| Create users | yes | no (403) |
| Read products, branches, packages | yes | yes |
| Create/edit products, branches, packages | yes | no (403) |
| Stock: PURCHASE, DONATION, USAGE movements | yes | yes |
| Stock: OPENING, ADJUSTMENT movements | yes | no (403) |
| Stock balances and history | yes | yes |
| Record expense, read ledger | yes | yes |
| Create delivery, list, get, mark done | yes | yes |
| Cancel delivery | yes | no (403) |
| Refund | yes | no (403) |

---

## Auth

### `POST /api/auth/login` (public)

```json
{ "username": "owner", "password": "your-password" }
```
Both fields required, non-blank.

`200`:
```json
{ "token": "eyJhbGciOiJIUzI1NiJ9...", "expiresAt": "2026-11-20T10:15:00Z", "role": "OWNER", "displayName": "Owner" }
```
Tokens last 8 hours; there is no refresh endpoint, log in again. Errors: 400 (blank fields), 401 `Invalid username or password`.

## Users (OWNER only)

### `POST /api/users`

| Field | Type | Required | Limits |
|---|---|---|---|
| username | string | yes | non-blank, max 50 |
| password | string | yes | 10-72 characters |
| displayName | string | yes | non-blank, max 100 |
| role | `OWNER` \| `ASSISTANT` | yes | |

`201`:
```json
{ "id": 2, "username": "maria", "displayName": "Maria Santos", "role": "ASSISTANT", "active": true }
```
Errors: 400, 401, 403, 409 `Username already taken`.

## Catalog

Seed data: products `Cage` (pc), `Chicken` (head), `Nipple` (pc), `Water jug` (pc), `Feeders` (pc), `Feed` (bag); branches `Branch 1`, `Branch 2` (placeholders, the owner renames them); packages `Package Small` (70,000), `Package Medium` (160,000), `Package Commercial` (700,000).

### `GET /api/products` (any role)
`200`:
```json
[ { "id": 2, "name": "Chicken", "unit": "head", "defaultPrice": null, "active": true },
  { "id": 3, "name": "Nipple", "unit": "pc", "defaultPrice": null, "active": true } ]
```
Includes inactive products (`active: false`); filter in the UI.

### `POST /api/products` (OWNER)
| Field | Type | Required | Limits |
|---|---|---|---|
| name | string | yes | max 120, unique |
| unit | string | yes | max 20 |
| defaultPrice | number | no | >= 0, up to 2 decimals |

`201` returns the product (shape above). Errors: 400, 403, 409 (duplicate name).

### `PATCH /api/products/{id}` (OWNER)
Any subset of `name` (1-120), `unit` (1-20), `defaultPrice` (>= 0), `active` (boolean). Returns `200` the product. Errors: 400, 403, 404, 409.

### `GET /api/branches` (any role)
`200`: `[ { "id": 1, "name": "Branch 1", "active": true } ]`

### `POST /api/branches` (OWNER)
`{ "name": "Branch 3" }` (required, max 80, unique). `201` returns the branch. Errors: 400, 403, 409.

### `PATCH /api/branches/{id}` (OWNER)
Any subset of `name` (1-80), `active`. `200` returns the branch. Errors: 400, 403, 404, 409.

### `GET /api/packages` (any role)
`200`:
```json
[ { "id": 1, "sku": "PKG-RTL-SML", "name": "Package Small", "price": 70000.00, "active": true,
    "items": [ { "productId": 2, "productName": "Chicken", "quantity": 24 },
               { "productId": 1, "productName": "Cage", "quantity": 2 },
               { "productId": 3, "productName": "Nipple", "quantity": 8 },
               { "productId": 5, "productName": "Feeders", "quantity": 4 },
               { "productId": 6, "productName": "Feed", "quantity": 6 } ] } ]
```
The three official packages and their contents:

| Package | Price | Chicken | Cage | Nipple | Feeders | Feed (bags) |
|---|---|---|---|---|---|---|
| Small | 70,000 | 24 | 2 | 8 | 4 | 6 |
| Medium | 160,000 | 96 | 4 | 24 | 8 | 16 |
| Commercial | 700,000 | 480 | 10 | 120 | 40 | 80 |

### `POST /api/packages` (OWNER)
| Field | Type | Required | Limits |
|---|---|---|---|
| sku | string | yes | max 40, unique |
| name | string | yes | max 120, unique |
| price | number | yes | >= 0.01, up to 2 decimals |
| items | array | yes | non-empty; each `{ "productId": long (required), "quantity": long > 0 }`; a product may appear once |

`201` returns the package. Errors: 400, 403, 404 (unknown product), 409 (duplicate sku/name), 422 (empty items, duplicate product).

### `PATCH /api/packages/{id}` (OWNER)
Any subset of `name` (1-120), `price` (>= 0.01), `active`. Contents cannot be edited. Past deliveries keep the name and price they were sold at. `200` returns the package.

---

## Stock

### `POST /api/stock/movements`

| Field | Type | Required | Notes |
|---|---|---|---|
| productId | long | yes | active product |
| branchId | long | yes | active branch |
| type | enum | yes | `OPENING`, `PURCHASE`, `DONATION`, `USAGE`, `ADJUSTMENT`. (`DELIVERY_OUT` / `DELIVERY_REVERSAL` are produced by deliveries and rejected here with 422.) |
| quantityDelta | long | yes | **Signed.** `OPENING`/`PURCHASE`/`DONATION`: positive. `USAGE`: negative. `ADJUSTMENT`: any non-zero. |
| unitCost | number | PURCHASE | >= 0, up to 2 decimals. Required for PURCHASE; OPENING/DONATION default to 0. |
| paymentMethod | `CASH` \| `GCASH` \| `BANK_TRANSFER` \| `OTHER` | PURCHASE | How the supplier was paid. |
| reason | string | USAGE, ADJUSTMENT | max 255 |
| occurredOn | date | yes | not in the future |

A `PURCHASE` also appends a `STOCK_PURCHASE` row to the finance ledger (cash out) in the same transaction and updates the weighted average cost. `OPENING` and `ADJUSTMENT` are owner-only.

`201`:
```json
{ "id": 14, "productId": 2, "branchId": 1, "type": "PURCHASE", "quantityDelta": 100, "unitCost": 380.0000,
  "reason": null, "occurredOn": "2026-11-20", "sourceType": null, "sourceId": null, "reversesId": null,
  "createdBy": 1, "createdAt": "2026-11-20T02:15:00Z" }
```
Errors: 400, 401, 403 (assistant using OPENING/ADJUSTMENT), 404, 409 (would take stock below zero: `Insufficient stock for product 2 at branch 1`), 422.

### `GET /api/stock/balances?branchId=` (any role)
`branchId` optional (omit for all branches). `200`:
```json
[ { "productId": 2, "productName": "Chicken", "unit": "head", "branchId": 1, "quantity": 100, "averageCost": 380.0000 } ]
```

### `GET /api/stock/movements?productId=&branchId=&page=&size=` (any role)
`productId` and `branchId` required. Paged `MovementView` (shape above), newest first.

---

## Finance (append-only ledger)

Entries are never edited or deleted. A mistake is corrected by a reversal or a new opposite entry.

### `POST /api/finance/expenses` (any role)
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

### `GET /api/finance/entries?from=&to=&type=&page=&size=` (any role)
`from` and `to` (inclusive dates, required, `to` >= `from`; else 422). `type` optional. Paged `EntryView`.

Entry types (amounts are always positive; the type fixes the direction):

| type | meaning | cashDirection |
|---|---|---|
| `SALE` / `SALE_REVERSAL` | revenue recognised / cancelled | NONE |
| `COST_OF_GOODS` / `COGS_REVERSAL` | cost of stock delivered / cancelled | NONE |
| `PAYMENT_IN` | customer payment received | IN |
| `REFUND` | money handed back | OUT |
| `STOCK_PURCHASE` | cash paid for inventory | OUT |
| `EXPENSE` | operating cost | OUT |

Entries tied to a delivery carry `sourceType: "DELIVERY"` and `sourceId`.

---

## Deliveries

Status flow: `ACTIVE -> DONE` or `ACTIVE -> CANCELLED`. `DONE` and `CANCELLED` are final: **a Done delivery cannot be cancelled.**

`DeliveryView` (returned by every delivery endpoint):
```json
{ "id": 7, "code": "DLV-2026-0007", "status": "ACTIVE", "customerName": "Juan dela Cruz", "driverName": "Pedro Reyes",
  "fromBranchId": 1, "destination": "Brgy. San Isidro, Tarlac", "startedAt": "2026-11-20T03:00:00Z", "completedAt": null,
  "total": 70000.00, "paymentMethod": "GCASH", "cancelReason": null, "retainedPayment": null,
  "lines": [ { "packageId": 1, "packageName": "Package Small", "quantity": 1, "unitPrice": 70000.00, "lineTotal": 70000.00 } ],
  "createdBy": 2, "createdAt": "2026-11-20T03:00:01Z" }
```
`retainedPayment` is only set for `CANCELLED` deliveries: the money received and not yet refunded.

### `POST /api/deliveries` (any role)
| Field | Type | Required | Limits |
|---|---|---|---|
| customerName | string | yes | non-blank, max 120 |
| driverName | string | yes | non-blank, max 120 |
| fromBranchId | long | yes | active branch |
| destination | string | yes | non-blank, max 255 |
| startedAt | instant | no | default now; not in the future |
| paymentMethod | enum | yes | `CASH`, `GCASH`, `BANK_TRANSFER`, `OTHER` |
| lines | array | yes | non-empty; each `{ packageId (required, active package, once per delivery), quantity (> 0), unitPrice (optional, >= 0.01, 2 decimals) }` |

`unitPrice` defaults to the package price; supplying it overrides the price for this delivery only. The sale is paid in full at creation. In one transaction the system deducts each package's contents from the branch (all-or-nothing), records `SALE`, `COST_OF_GOODS` (at the branch's weighted average cost) and `PAYMENT_IN`.

`201` returns the `DeliveryView`. Errors: 400, 404 (unknown package/branch), 409 (`Insufficient stock for product X at branch Y`; nothing is saved), 422 (inactive package/branch, future start, duplicate package line).

### `GET /api/deliveries?status=&page=&size=` (any role)
`status` optional (`ACTIVE`, `DONE`, `CANCELLED`). Paged `DeliveryView`, newest `startedAt` first.

### `GET /api/deliveries/{id}` (any role)
`200` `DeliveryView`; 404 if unknown.

### `POST /api/deliveries/{id}/done` (any role)
Body `{ "completedAt": "2026-11-20T06:30:00Z" }` (required). No stock or money effect. `200` returns the delivery with `status: DONE`. Errors: 400, 404, 409 (not ACTIVE).

### `POST /api/deliveries/{id}/cancel` (OWNER)
Body `{ "reason": "Customer cancelled the order" }` (required, max 255). Restores the stock at its original cost and appends `SALE_REVERSAL` and `COGS_REVERSAL`. The payment is **kept**: money only leaves through a refund. `200` returns the delivery with `status: CANCELLED` and `retainedPayment` equal to what was paid. Errors: 400, 403, 404, 409 (already cancelled, or Done).

### `POST /api/deliveries/{id}/refund` (OWNER)
| Field | Type | Required | Limits |
|---|---|---|---|
| amount | number | yes | >= 0.01, 2 decimals, not above `retainedPayment` |
| method | enum | yes | payment method used to hand the money back |
| description | string | no | max 255; default `Refund for DLV-...` |

Partial refunds are allowed; repeat until `retainedPayment` is 0. `200` returns the delivery with the reduced `retainedPayment`. Errors: 400, 403, 404, 422 (delivery is not CANCELLED, or amount exceeds the payment still held).

---

## Worked example (PowerShell)

```powershell
$base = "http://localhost:8080"
$json = @{ "Content-Type" = "application/json" }

# 1. Log in as the owner
$login = Invoke-RestMethod "$base/api/auth/login" -Method Post -Headers $json `
  -Body (@{ username = "owner"; password = "<owner-password>" } | ConvertTo-Json)
$auth = @{ Authorization = "Bearer $($login.token)"; "Content-Type" = "application/json" }

# 2. Look up ids
$products = Invoke-RestMethod "$base/api/products" -Headers $auth
$packages = Invoke-RestMethod "$base/api/packages" -Headers $auth
$small = $packages | Where-Object name -eq "Package Small"

# 3. Buy stock for Branch 1 (branchId 1): everything one Package Small needs
$needs = @{ Chicken = 24; Cage = 2; Nipple = 8; Feeders = 4; Feed = 6 }
foreach ($item in $small.items) {
  Invoke-RestMethod "$base/api/stock/movements" -Method Post -Headers $auth -Body (@{
    productId = $item.productId; branchId = 1; type = "PURCHASE"; quantityDelta = $item.quantity
    unitCost = 100.00; paymentMethod = "CASH"; occurredOn = (Get-Date -Format "yyyy-MM-dd") } | ConvertTo-Json) | Out-Null
}
Invoke-RestMethod "$base/api/stock/balances?branchId=1" -Headers $auth

# 4. Create a delivery with Package Small
$delivery = Invoke-RestMethod "$base/api/deliveries" -Method Post -Headers $auth -Body (@{
  customerName = "Juan dela Cruz"; driverName = "Pedro Reyes"; fromBranchId = 1
  destination = "Brgy. San Isidro, Tarlac"; paymentMethod = "GCASH"
  lines = @(@{ packageId = $small.id; quantity = 1 }) } | ConvertTo-Json -Depth 5)
$delivery.code, $delivery.total

# 5a. Mark it done (once Done it can never be cancelled)
Invoke-RestMethod "$base/api/deliveries/$($delivery.id)/done" -Method Post -Headers $auth `
  -Body (@{ completedAt = (Get-Date).ToUniversalTime().ToString("o") } | ConvertTo-Json)

# 5b. ...or, for a different ACTIVE delivery, cancel it (owner only)
Invoke-RestMethod "$base/api/deliveries/$($delivery.id)/cancel" -Method Post -Headers $auth `
  -Body (@{ reason = "Customer cancelled" } | ConvertTo-Json)

# 6. Refund the retained payment of a cancelled delivery (owner only)
Invoke-RestMethod "$base/api/deliveries/$($delivery.id)/refund" -Method Post -Headers $auth `
  -Body (@{ amount = 70000.00; method = "GCASH"; description = "Full refund" } | ConvertTo-Json)
```

Steps 5a and 5b are alternatives: after 5a, 5b returns `409`. To try cancel and refund, create a second delivery first.

The same calls with curl (Git Bash / Linux; in PowerShell use `curl.exe`, not the `curl` alias):

```bash
BASE=http://localhost:8080
TOKEN=$(curl -s $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"owner","password":"<owner-password>"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
curl -s $BASE/api/packages -H "Authorization: Bearer $TOKEN"
curl -s $BASE/api/stock/movements -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"productId":2,"branchId":1,"type":"PURCHASE","quantityDelta":24,"unitCost":380.00,"paymentMethod":"CASH","occurredOn":"2026-11-20"}'
curl -s $BASE/api/deliveries -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"customerName":"Juan dela Cruz","driverName":"Pedro Reyes","fromBranchId":1,"destination":"Tarlac","paymentMethod":"GCASH","lines":[{"packageId":1,"quantity":1}]}'
curl -s -X POST $BASE/api/deliveries/1/cancel -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"reason":"Customer cancelled"}'
curl -s -X POST $BASE/api/deliveries/1/refund -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"amount":70000.00,"method":"GCASH"}'
```

---

## Pending (being built)

Not available yet; details will be added once they ship. Do not code against them.

- **Reports**: `/api/reports/*`.
- **User administration** beyond creating a user (listing, deactivating, role/password changes).
- **Idempotency keys** for delivery creation (safe retries).
