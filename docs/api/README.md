# Ausan Poultry System API contract

This folder is the contract between the backend and the frontend. It describes what the running API does today.

| Document | What it holds |
|---|---|
| [integration-guide.md](integration-guide.md) | How to build a client: login flow, errors, money and dates, paging, retries, role-based UI, worked example. **Start here.** |
| [errors.md](errors.md) | Every HTTP status and stable error `code`, and what the UI should do for each |
| [auth.md](auth.md) | Login and changing your own password |
| [auth-session.md](auth-session.md) | Who am I (`/me`), logout, password rules, request ids, CORS |
| [users.md](users.md) | User administration (OWNER only) |
| [audit.md](audit.md) | Audit trail of master-data changes (OWNER only) |
| [catalog.md](catalog.md) | Products, branches, sales packages |
| [stock.md](stock.md) | Stock movements, balances, history |
| [stock-overview.md](stock-overview.md) | All-branch stock summary, low-stock flags, reorder levels |
| [finance.md](finance.md) | Expenses and the append-only financial ledger |
| [deliveries.md](deliveries.md) | Creating, finishing, cancelling and refunding deliveries; idempotency keys |
| [deliveries-queries.md](deliveries-queries.md) | Delivery filters, status counts, owner's receipt |
| [reports.md](reports.md) | Period summary, profit and inventory value |
| [reports-dashboard-series.md](reports-dashboard-series.md) | Dashboard figures and day/month time series |
| [exports-csv.md](exports-csv.md) | CSV downloads of the ledger and the reports |
| [openapi.json](openapi.json) | Machine-readable OpenAPI 3 contract (generate a typed client from it) |

The code-level reference is the Javadoc (`./mvnw javadoc:javadoc`, output in `target/reports/apidocs`).

## Single source of truth

- **`openapi.json` is generated from the code**, then committed. The test `OpenApiSnapshotTests` fails when the running
  application's contract differs from the committed file, so the spec can never silently drift from the code. After an
  intended API change, regenerate it with:

  ```powershell
  ./mvnw test "-Dtest=OpenApiSnapshotTests" "-Dopenapi.update=true"
  ```

  and commit the changed file together with the code and the matching page in this folder.
- The markdown pages explain the *meaning* (business rules, examples); the spec states the *shape* (fields, types,
  statuses, who may call). When they disagree, the spec is right and the page is a bug: please report it.
- The running application serves the same document at `GET /v3/api-docs` (JSON) and `GET /v3/api-docs.yaml`. Like every
  other path it needs a bearer token.

## Conventions

Base URL: `http://localhost:8080` in development. All endpoints except `POST /api/auth/login` and `/actuator/health`
need `Authorization: Bearer <token>`. Bodies and responses are JSON (`Content-Type: application/json`).

| Topic | Rule |
|---|---|
| Money | PHP, JSON numbers with 2 decimals (`70000.00`). Never send more than 2 decimals. |
| Unit cost / average cost | Up to 4 decimals in responses (`312.5000`). |
| Dates | `occurredOn`, `from`, `to`: `yyyy-MM-dd` (business zone Asia/Manila). No future business dates. Assistants may backdate through 7 calendar days ago; owners may use any past date. No period-close rule is enforced. |
| Timestamps | ISO-8601 instants in UTC, e.g. `2026-11-20T02:15:00Z`. |
| Ids | `long` (JSON integer). |
| Roles | `OWNER` or `ASSISTANT`, in the token and in the login response. |
| Errors | RFC 9457 problem details with a stable `code`; see [errors.md](errors.md). |
| Partial updates | `PATCH` bodies contain only the fields to change; a missing field means "leave as is". |
| Inactive records | Lists include inactive products, branches and packages (`active: false`); filter in the UI. |

### Paging

Paged endpoints take `?page=0&size=20&sort=property,desc` (all optional; `page` is zero-based, default size 20, maximum size 100) and
return:

```json
{ "content": [ ... ],
  "page": { "size": 20, "number": 0, "totalElements": 135, "totalPages": 7 } }
```

Paged today: `GET /api/stock/movements`, `GET /api/finance/entries`, `GET /api/deliveries`, `GET /api/audit`. Every other list returns a
plain JSON array.

### Who may call what

| Area | OWNER | ASSISTANT |
|---|---|---|
| Login, `me`, logout, change own password | yes | yes |
| User administration | yes | no (403) |
| Audit trail | yes | no (403) |
| Read products, branches, packages | yes | yes |
| Create/edit products, branches, packages | yes | no (403) |
| Stock: PURCHASE, DONATION, USAGE movements | yes | yes |
| Stock: OPENING, ADJUSTMENT movements | yes | no (403) |
| Stock balances and history | yes, with costs | quantities; unit cost on own purchase rows only |
| Supplier unit price | yes | assistant can see the price they entered on their own purchase rows |
| Record expense | yes | yes |
| Read finance ledger | yes, all types | yes, sales and collections only |
| Create delivery, list, filter, status counts, get, mark done | yes | yes |
| Cancel delivery, refund, delivery receipt (cost and profit) | yes | no (403) |
| Stock summary, read reorder levels | yes | yes |
| Set or clear a reorder level | yes | no (403) |
| Reports: summary, dashboard, series | yes, all fields | yes, sales and collections only |
| Inventory value | yes | no (403) |
| Reports: profit, profit series | yes | no (403) |
| CSV exports | as the matching JSON endpoint | as the matching JSON endpoint |

The same information is in the spec as the `x-roles` extension on every operation, so a client can hide actions the
current role cannot use.

## Added in the current development phase

Endpoints that shipped after the first version of this contract. Each links to its own page.

| Endpoint | Page |
|---|---|
| `GET /api/auth/me`, `POST /api/auth/logout` | [auth-session.md](auth-session.md) |
| `GET /api/audit` | [audit.md](audit.md) |
| `GET /api/deliveries` filters, `GET /api/deliveries/summary`, `GET /api/deliveries/{id}/receipt` | [deliveries-queries.md](deliveries-queries.md) |
| `GET /api/stock/summary`, `GET/PUT/DELETE /api/stock/reorder-levels` | [stock-overview.md](stock-overview.md) |
| `GET /api/reports/dashboard`, `/series`, `/profit-series` | [reports-dashboard-series.md](reports-dashboard-series.md) |
| `GET /api/finance/entries.csv`, `/api/reports/*.csv` | [exports-csv.md](exports-csv.md) |

Behaviour that changed for existing endpoints: password changes and resets end sessions, passwords have extra rules (`422`),
and every response has an `X-Request-Id` header; see [auth-session.md](auth-session.md).
