# Frontend integration guide

How to build a client against this API without surprises. Reference pages: [conventions](README.md),
[errors](errors.md), and one page per area. The machine-readable contract is [openapi.json](openapi.json).

## 1. Set up

**Typed client.** Generate types from the committed spec; no running server is needed:

```powershell
npx openapi-typescript docs/api/openapi.json -o src/api/schema.d.ts
```

`openapi-typescript` + `openapi-fetch` give typed paths, bodies and responses; any OpenAPI generator works. Every
operation has a stable `operationId` (for example `deliveriesCreate`, `refund`, `summary`) and a tag per area, so
generated function names are predictable. Regenerate whenever the spec changes (it changes in the same pull request as
the code).

**Base URL.** `http://localhost:8080` in development. The backend allows browser calls from `http://localhost:5173` (Vite)
and `http://localhost:3000` by default; production allows only the origins listed in `APP_CORS_ALLOWED_ORIGINS`
([CORS details](auth-session.md#cors-browser-frontends)). Alternatively, proxy `/api` through the dev server so the browser
only ever sees one origin, which also avoids CORS entirely:

```ts
// vite.config.ts
export default defineConfig({ server: { proxy: { '/api': 'http://localhost:8080' } } });
```

In production, serving the frontend and `/api` from one origin (a reverse proxy) needs no CORS setting at all.

**Development login.** The first OWNER comes from the `APP_BOOTSTRAP_OWNER_*` variables in the
[README](../../README.md#development-quickstart); create an assistant with `POST /api/users` to test the other role.

## 2. Session lifecycle

1. `POST /api/auth/login` with `{ username, password }` returns `{ token, expiresAt, role, displayName }`.
2. Keep the token in memory (or `sessionStorage` if a page reload must survive) and send
   `Authorization: Bearer <token>` on every request. There are no cookies and no CSRF surface.
3. The token lasts 8 hours and **cannot be refreshed**. Log the user out when `expiresAt` passes, or when **any** call
   answers `401`: the token expired, or the user was deactivated (the server re-checks on every request).
4. Call `GET /api/auth/me` on app start (and after a `401`) to restore the signed-in state. It returns the **current**
   role from the database, so use it, not the login response, to show or hide actions (section 6). A role change by an
   owner applies on the user's next request.
5. Log out with `POST /api/auth/logout` (`204`): it ends every session of that user on every device. Discard the token
   afterwards. Changing your own password also ends your sessions, so send the user to login after `204`.
6. Handle `429` on login: disable the form for `Retry-After` seconds.
7. Details of all of this, plus the password rules (`422` with a `detail` to show under the field), are in
   [auth-session.md](auth-session.md).

## 3. Errors

Switch on `code`, show `detail`, and treat unknown codes as generic failures:

```ts
interface Problem { title: string; status: number; code: string; detail?: string; instance?: string }

async function call(path: string, init: RequestInit = {}) {
  const res = await fetch(path, { ...init, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...init.headers } });
  if (res.ok) return res.status === 204 ? undefined : res.json();
  if (res.status === 401) { logout(); throw new Error('Session ended'); }   // empty body
  if (res.status === 403) throw new Error('Not allowed');                    // empty body
  const problem: Problem = await res.json();                                  // every other error has a body
  throw problem;
}
```

Full list of codes and the recommended UI reaction: [errors.md](errors.md). Every response also has an `X-Request-Id`
header; show it in "something went wrong" dialogs so a problem can be traced in the server log. Two things to remember:

- `401` and `403` from the security layer have **no body**; do not call `res.json()` on them.
- `VALIDATION_FAILED.detail` is `field message; field message`. Split on `; ` and match each field name to the input.

## 4. Money, dates and numbers

- **Money** is a JSON number with at most 2 decimals (`70000.00`). JavaScript numbers are binary floats, so do not add
  or multiply money in the browser: show server-computed totals (`total`, `lineTotal`, report figures). To show a value,
  `new Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP' }).format(n)`. To send a value, send a number
  with no more than 2 decimals (`Number(input).toFixed(2)` then parse), never a computed float such as `0.1 + 0.2`.
- **Average cost** has up to 4 decimals; display it, do not round it before sending anything back.
- **Calendar dates** (`occurredOn`, report `from`/`to`) are `yyyy-MM-dd` in Manila time. Build them from the user's local
  date picker value, not from `new Date().toISOString()` (that is UTC and can be yesterday in Manila).
- **Timestamps** (`startedAt`, `completedAt`, `createdAt`, `expiresAt`) are UTC instants: `new Date(value)` and format
  in `Asia/Manila`. Send instants with `toISOString()`.
- **Ids** are integers.
- **Quantities** are whole numbers. `quantityDelta` on stock movements is **signed**: positive adds, negative removes.

## 5. Lists and paging

Paged lists (`stock/movements`, `finance/entries`, `deliveries`, `audit`) return `{ content, page: { size, number, totalElements,
totalPages } }`; pass `?page=0&size=20`. All other lists (`products`, `branches`, `packages`, `users`, `stock/balances`) are
plain arrays. Catalog lists include inactive records: filter `active` in the UI (and keep inactive ones visible in
admin screens).

File downloads (`*.csv` endpoints) need the bearer header too, so fetch them with the token and save the blob; see
[exports-csv.md](exports-csv.md). Filters, status counts and the receipt for deliveries are in
[deliveries-queries.md](deliveries-queries.md); the dashboard and time series for charts are in
[reports-dashboard-series.md](reports-dashboard-series.md).

## 6. Role-based UI

Each operation in the spec carries `x-roles` (for example `["OWNER"]`). Use it, or the table in
[README.md](README.md#who-may-call-what), to hide what a role cannot do:

| Hide for ASSISTANT | Why |
|---|---|
| User administration, catalog create/edit | OWNER only |
| Cancel delivery, refund | OWNER only |
| Stock `OPENING` and `ADJUSTMENT` types | OWNER only (the other types are fine) |
| Profit report, profit series, delivery receipt | OWNER only |
| Reorder levels (set, clear), audit trail | OWNER only |

Hiding is a convenience; the server enforces every rule and answers `403` regardless.

## 7. Creating a delivery safely

Deliveries move stock and money in one transaction, so a double submit would sell the goods twice. Use the idempotency
key (details in [deliveries.md](deliveries.md#idempotency-safe-retries-with-idempotency-key)):

```ts
const key = crypto.randomUUID();           // once per form submission, e.g. when the user opens the form
await call('/api/deliveries', { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(payload) });
// On a network error or a double click, resend with the SAME key. Success is 201 (new) or 200 (original returned).
// After a success, drop the key. After a 4xx failure the key is not used up: fix the form and resend it.
```

Check `409 CONFLICT` (`Insufficient stock ...`) and refresh the branch's stock balances; nothing was saved.

## 8. The business flow

```text
buy stock ──► POST /api/stock/movements (type PURCHASE)        records cash out and updates average cost
sell ───────► POST /api/deliveries                              deducts stock, records sale + cost of goods + payment
finish ─────► POST /api/deliveries/{id}/done                    final; can no longer be cancelled
or undo ────► POST /api/deliveries/{id}/cancel   (OWNER)        stock returns, sale reversed, payment retained
refund ─────► POST /api/deliveries/{id}/refund   (OWNER)        hands retained money back, partial allowed
review ─────► GET  /api/reports/summary|profit|inventory-value
```

## 9. Worked example

PowerShell:

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
foreach ($item in $small.items) {
  Invoke-RestMethod "$base/api/stock/movements" -Method Post -Headers $auth -Body (@{
    productId = $item.productId; branchId = 1; type = "PURCHASE"; quantityDelta = $item.quantity
    unitCost = 100.00; paymentMethod = "CASH"; occurredOn = (Get-Date -Format "yyyy-MM-dd") } | ConvertTo-Json) | Out-Null
}
Invoke-RestMethod "$base/api/stock/balances?branchId=1" -Headers $auth

# 4. Create a delivery with Package Small (the key makes a retry safe)
$delivery = Invoke-RestMethod "$base/api/deliveries" -Method Post -Body (@{
  customerName = "Juan dela Cruz"; driverName = "Pedro Reyes"; fromBranchId = 1
  destination = "Brgy. San Isidro, Tarlac"; paymentMethod = "GCASH"
  lines = @(@{ packageId = $small.id; quantity = 1 }) } | ConvertTo-Json -Depth 5) `
  -Headers ($auth + @{ "Idempotency-Key" = [guid]::NewGuid().ToString() })
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

# 7. A report for this month
Invoke-RestMethod "$base/api/reports/summary?from=2026-11-01&to=2026-11-30" -Headers $auth
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
curl -s $BASE/api/deliveries -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"customerName":"Juan dela Cruz","driverName":"Pedro Reyes","fromBranchId":1,"destination":"Tarlac","paymentMethod":"GCASH","lines":[{"packageId":1,"quantity":1}]}'
curl -s -X POST $BASE/api/deliveries/1/cancel -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"reason":"Customer cancelled"}'
curl -s -X POST $BASE/api/deliveries/1/refund -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"amount":70000.00,"method":"GCASH"}'
```

## 10. Checklist before shipping a screen

- Calls go through one helper that handles `401` (log out) and `403` (hide/disable) without reading a body.
- Forms map `VALIDATION_FAILED` to fields and show `detail` for `409`/`422`.
- Money is displayed from server values, never summed in the browser; dates sent are Manila calendar dates.
- Delivery creation sends an `Idempotency-Key` and reuses it on retry.
- Owner-only actions are hidden from assistants using `x-roles`.
- Lists that can grow (deliveries, ledger, movements) are paged.
