# Frontend integration guide

How to build a client without surprises. Conventions are in the [README](README.md), error codes in [errors.md](errors.md), behaviour in [rules.md](rules.md).

## 1. Set up

Generate a typed client from the committed spec; no running server is needed:

```powershell
npx openapi-typescript docs/api/openapi.json -o src/api/schema.d.ts
```

Every operation has a stable `operationId` (`deliveriesCreate`, `refund`, `summary`) and a tag per area. Regenerate whenever the spec changes; it changes in the same pull request as the code.

During development either call `http://localhost:8080` directly (CORS allows `:5173` and `:3000`) or proxy `/api` so the browser sees one origin and CORS is not involved. Production serving the UI and `/api` from one origin needs no CORS setting.

```ts
// vite.config.ts
export default defineConfig({ server: { proxy: { '/api': 'http://localhost:8080' } } });
```

The first OWNER comes from the `APP_BOOTSTRAP_OWNER_*` variables ([README](../../README.md#quick-start)); create an assistant with `POST /api/users` to test the other role.

## 2. Session lifecycle

1. `POST /api/auth/login` returns `{ token, expiresAt, role, displayName }`. Keep the token in memory (or `sessionStorage`). Send `Authorization: Bearer <token>` on every call. There are no cookies and no CSRF surface.
2. The token lasts 8 hours and cannot be refreshed. Log out when `expiresAt` passes or **any** call answers `401`.
3. On app start (and after a `401`) call `GET /api/auth/me` and use its `capabilities` to show or hide actions. If `mustChangePassword` is `true`, send the user to the password form first.
4. `POST /api/auth/logout` (`204`) ends every session of that user. Changing your own password does too: go to login after `204`.
5. On `429` from login, disable the form for `Retry-After` seconds.

## 3. Errors

Switch on `code`, show `detail`, treat unknown codes as generic failures. `401` and `403` have no body.

```ts
async function call(path: string, init: RequestInit = {}) {
  const res = await fetch(path, { ...init, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...init.headers } });
  if (res.ok) return res.status === 204 ? undefined : res.json();
  if (res.status === 401) { logout(); throw new Error('Session ended'); }
  if (res.status === 403) throw new Error('Not allowed');
  throw await res.json();   // problem details with a stable code
}
```

`VALIDATION_FAILED.detail` is `field message; field message`: split on `; ` and match field names to inputs. Show `X-Request-Id` in "something went wrong" dialogs.

## 4. Money, dates, numbers

- **Money** has at most 2 decimals. Do not add or multiply money in the browser; show server totals. Format with `Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP' })`. Send `Number(input).toFixed(2)`, never a computed float.
- **Calendar dates** are `yyyy-MM-dd` in Manila time. Build them from the date picker, not `new Date().toISOString()` (UTC can be yesterday in Manila).
- **Timestamps** are UTC instants: `new Date(value)`, display in `Asia/Manila`.
- **Quantities** are whole numbers; stock `quantityDelta` is signed.

## 5. Creating a delivery safely

A delivery moves stock and money in one transaction, so a double submit would sell twice. Send an `Idempotency-Key` ([rules](rules.md#idempotency-key)):

```ts
const key = crypto.randomUUID();   // once per form submission
await call('/api/deliveries', { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(payload) });
// On a network error or double click resend with the SAME key. 201 = new, 200 = original returned.
// After success drop the key. After a 4xx the key is not used up: fix the form and resend.
```

On `409 CONFLICT` (insufficient stock) nothing was saved; refresh the branch's balances.

## 6. The business flow

```text
buy stock ──► POST /api/stock/movements (PURCHASE)      cash out, updates average cost
sell ───────► POST /api/deliveries                       deducts stock, writes sale + cost of goods + payment
finish ─────► POST /api/deliveries/{id}/done             final; can no longer be cancelled
or undo ────► POST /api/deliveries/{id}/cancel (OWNER)   stock returns, sale reversed, payment retained
              POST /api/deliveries/{id}/return (OWNER)   same, for a Done delivery
refund ─────► POST /api/deliveries/{id}/refund (OWNER)   hands retained money back, partial allowed
review ─────► GET  /api/reports/summary | profit | inventory-value
```

## 7. Worked example

```powershell
$base = "http://localhost:8080"
$json = @{ "Content-Type" = "application/json" }

# Log in as the owner
$login = Invoke-RestMethod "$base/api/auth/login" -Method Post -Headers $json `
  -Body (@{ username = "owner"; password = "<owner-password>" } | ConvertTo-Json)
$auth = @{ Authorization = "Bearer $($login.token)"; "Content-Type" = "application/json" }

# Buy the stock one Package Small needs for Branch 1
$small = Invoke-RestMethod "$base/api/packages" -Headers $auth | Where-Object name -eq "Package Small"
foreach ($item in $small.items) {
  Invoke-RestMethod "$base/api/stock/movements" -Method Post -Headers $auth -Body (@{
    productId = $item.productId; branchId = 1; type = "PURCHASE"; quantityDelta = $item.quantity
    unitCost = 100.00; paymentMethod = "CASH"; occurredOn = (Get-Date -Format "yyyy-MM-dd") } | ConvertTo-Json) | Out-Null
}

# Sell it (the key makes a retry safe)
$delivery = Invoke-RestMethod "$base/api/deliveries" -Method Post -Body (@{
  customerName = "Juan dela Cruz"; driverName = "Pedro Reyes"; fromBranchId = 1
  destination = "Brgy. San Isidro, Tarlac"; paymentMethod = "GCASH"
  lines = @(@{ packageId = $small.id; quantity = 1 }) } | ConvertTo-Json -Depth 5) `
  -Headers ($auth + @{ "Idempotency-Key" = [guid]::NewGuid().ToString() })

# Finish it, or cancel and refund it (alternatives; after done, cancel is 409)
Invoke-RestMethod "$base/api/deliveries/$($delivery.id)/done" -Method Post -Headers $auth `
  -Body (@{ completedAt = (Get-Date).ToUniversalTime().ToString("o") } | ConvertTo-Json)

# A report for this month
Invoke-RestMethod "$base/api/reports/summary?from=2026-11-01&to=2026-11-30" -Headers $auth
```

## 8. Checklist before shipping a screen

- One helper handles `401` (log out) and `403` (hide or disable) without reading a body.
- Forms map `VALIDATION_FAILED` to fields and show `detail` for `409` and `422`.
- Money shown from server values; dates sent as Manila calendar dates.
- Delivery creation sends and reuses an `Idempotency-Key`.
- Owner-only actions hidden from assistants via `capabilities` or `x-roles`.
- Growing lists (deliveries, ledger, movements) are paged.
