# Ausan API contract

This folder is the contract between the backend and the frontend. It describes what the running API does today.

| Document | What it holds |
|---|---|
| [integration-guide.md](integration-guide.md) | How to build a client: login flow, errors, money and dates, paging, retries, role-based UI, worked example. **Start here.** |
| [errors.md](errors.md) | Every HTTP status and stable error `code`, and what the UI should do for each |
| [auth.md](auth.md) | Login and changing your own password |
| [users.md](users.md) | User administration (OWNER only) |
| [catalog.md](catalog.md) | Products, branches, sales packages |
| [stock.md](stock.md) | Stock movements, balances, history |
| [finance.md](finance.md) | Expenses and the append-only financial ledger |
| [deliveries.md](deliveries.md) | Creating, finishing, cancelling and refunding deliveries; idempotency keys |
| [reports.md](reports.md) | Period summary, profit and inventory value |
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
| Dates | `occurredOn`, `from`, `to`: `yyyy-MM-dd` (business zone Asia/Manila). `occurredOn` must not be in the future. |
| Timestamps | ISO-8601 instants in UTC, e.g. `2026-11-20T02:15:00Z`. |
| Ids | `long` (JSON integer). |
| Roles | `OWNER` or `ASSISTANT`, in the token and in the login response. |
| Errors | RFC 9457 problem details with a stable `code`; see [errors.md](errors.md). |
| Partial updates | `PATCH` bodies contain only the fields to change; a missing field means "leave as is". |
| Inactive records | Lists include inactive products, branches and packages (`active: false`); filter in the UI. |

### Paging

Paged endpoints take `?page=0&size=20&sort=property,desc` (all optional; `page` is zero-based, default size 20) and
return:

```json
{ "content": [ ... ],
  "page": { "size": 20, "number": 0, "totalElements": 135, "totalPages": 7 } }
```

Paged today: `GET /api/stock/movements`, `GET /api/finance/entries`, `GET /api/deliveries`. Every other list returns a
plain JSON array.

### Who may call what

| Area | OWNER | ASSISTANT |
|---|---|---|
| Login, change own password | yes | yes |
| User administration | yes | no (403) |
| Read products, branches, packages | yes | yes |
| Create/edit products, branches, packages | yes | no (403) |
| Stock: PURCHASE, DONATION, USAGE movements | yes | yes |
| Stock: OPENING, ADJUSTMENT movements | yes | no (403) |
| Stock balances and history | yes | yes |
| Record expense, read ledger | yes | yes |
| Create delivery, list, get, mark done | yes | yes |
| Cancel delivery, refund | yes | no (403) |
| Reports: summary, inventory value | yes | yes |
| Report: profit | yes | no (403) |

The same information is in the spec as the `x-roles` extension on every operation, so a client can hide actions the
current role cannot use.

## Added in the current development phase

Endpoints that ship after this contract was written are listed here when their pull requests merge. Each links to its
own page.

| Endpoint | Page |
|---|---|
| *(none merged yet)* | |
