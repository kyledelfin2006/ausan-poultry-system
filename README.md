# Ausan Farm System

Backend for **Ausan Poultry Farm**. Despite the name, Ausan is a **trading business**, not a farm: it buys cages, chickens, nipples, feeders, feed bags and water jugs, and resells them in pre-set **packages** through **deliveries**, across two branches. Currency is PHP. The system tracks stock, deliveries and the money that moves with them.

## Status

| Slice | State |
|---|---|
| 1. Foundation, identity (JWT), catalog | built, tested |
| 2. Stock ledger and balances | built, tested |
| 3. Finance ledger and weighted average cost | built, tested |
| 4. Packages and deliveries (create, done, cancel, refund) | built, tested |
| 5. Reports (`/api/reports/*`: summary, profit, inventory value) | built, tested |
| Read-side API: delivery filters and receipt, all-branch stock summary and reorder levels, dashboard, day/month series, CSV exports | built, tested |
| User administration, password rules, login throttling, logout and immediate session revocation, `/api/auth/me` | built, tested; see [docs/security/](docs/security/README.md) |
| Audit trail of master-data changes (`/api/audit`), request ids, structured logs, metrics, CORS, security headers | built, tested; see [docs/security/](docs/security/README.md) |
| Delivery idempotency keys (`Idempotency-Key`) | built, tested |
| Quality gates: coverage floor, architecture rules, concurrency tests, Javadoc lint | built; see [docs/quality/](docs/quality/README.md) |
| Production packaging, backups, CI | built; see [docs/operations.md](docs/operations.md) |
| API contract: OpenAPI spec, stable error codes, frontend docs | built; see [docs/api/](docs/api/README.md) |
| Frontend (React + Vite + TypeScript) | not started |

## Tech stack

Java 25, Spring Boot 4.1, Maven (wrapper included), PostgreSQL 17, Flyway migrations, Spring Security with stateless JWT (HS256), Docker. Tests use JUnit with Testcontainers (real PostgreSQL).

## Development quickstart

Prerequisites: JDK 25 and Docker Desktop running. The dev `compose.yaml` starts PostgreSQL automatically when the app starts (Spring Boot docker-compose support).

PowerShell:

```powershell
# Signing key: any string of at least 32 bytes
$env:APP_SECURITY_JWT_SECRET = "dev-only-secret-change-me-at-least-32-bytes-long"
# Creates the first OWNER if the user table is empty
$env:APP_BOOTSTRAP_OWNER_USERNAME = "owner"
$env:APP_BOOTSTRAP_OWNER_PASSWORD = "dev-owner-password"

./mvnw spring-boot:run
```

bash: `export APP_SECURITY_JWT_SECRET=...` and the same for the other two variables, then `./mvnw spring-boot:run`.

The API is then on <http://localhost:8080>. Log in and call it as shown in the [integration guide](docs/api/integration-guide.md).

### Tests

```powershell
./mvnw verify
```

Needs Docker running (Testcontainers starts PostgreSQL). CI runs the same command on every pull request ([.github/workflows/ci.yml](.github/workflows/ci.yml)).

## Production

`Dockerfile` plus `compose.prod.yaml` run the app and PostgreSQL with all settings from a `.env` file (template: [.env.example](.env.example)). The `prod` Spring profile ([application-prod.yaml](src/main/resources/application-prod.yaml)) enables graceful shutdown, hides error details and exposes only `/actuator/health` and `info`.

```powershell
Copy-Item .env.example .env      # then edit every placeholder
docker compose -f compose.prod.yaml --env-file .env up -d --build
```

Deployment, upgrades, rollback, backups and restore: [docs/operations.md](docs/operations.md).

## Code layout

A modular monolith in `kyle.poultry`:

| Module | Responsibility |
|---|---|
| `identity` | users, roles, login, JWT, owner bootstrap |
| `catalog` | products, branches, sales packages |
| `stock` | append-only stock movements, balances, weighted average cost |
| `finance` | append-only financial ledger |
| `delivery` | deliveries and their lifecycle; orchestrates stock and finance |
| `report` | read-only period reports, dashboard and series built from finance and stock |
| `audit` | append-only audit trail of master-data changes |
| `shared` | error handling and stable error codes, paging format, OpenAPI contract (`shared.openapi`), exceptions, clock |

**Rule: modules talk to each other only through their public `*Api` interfaces** (`CatalogApi`, `StockApi`, `FinanceApi`). Implementations, entities and repositories are package-private. A module must never read another module's tables or classes directly.

## Roles

| Action | OWNER | ASSISTANT |
|---|---|---|
| Record purchases, donations, usage, expenses | yes | yes |
| Create deliveries, mark them done | yes | yes |
| Opening stock and adjustments | yes | no |
| Cancel deliveries, record refunds | yes | no |
| Edit products, branches, packages; create users | yes | no |
| Profit reports, delivery receipts (cost and profit), reorder levels, audit trail | yes | no |

## Key business rules

- **Append-only ledgers.** Stock movements and financial entries are never updated or deleted. A correction is a reversal or an opposite entry, so history stays auditable.
- **Weighted average cost.** Each branch and product keeps an average cost updated on purchases. A delivery's cost of goods is taken at the average cost at that moment.
- **Packages.** Customers buy fixed packages (Small 70,000; Medium 160,000; Commercial 700,000 PHP), each a fixed set of products. Delivery lines freeze the package name and price at sale time; the price may be overridden per delivery. Stock for all lines is deducted together or not at all.
- **Payment.** A delivery is paid in full when created: it records the sale, the cost of goods and the payment.
- **Cancellation.** Owner only, only while ACTIVE. Stock returns at its original cost and sale and cost are reversed, but the payment stays on record as money held until it is refunded.
- **Refunds.** Owner only, only for cancelled deliveries, never more than the payment still held; partial refunds are allowed.
- **Done deliveries cannot be cancelled.** DONE and CANCELLED are final states.

## Code documentation standard

- Every class, interface, record and enum has Javadoc.
- Every method and constructor, of any visibility and including tests, has Javadoc: a one-liner for trivial accessors, and `@param`, `@return` and `@throws` for non-trivial ones.
- Method bodies carry short one-line `//` comments for each logical step and each non-obvious statement, explaining **why**, not what.
- Comments must stay true: update them in the same change as the code.
- Existing Flyway migrations (V1-V5 and any applied later) are **never edited**; changing one alters its checksum and breaks every existing database. Add a new migration instead.

Generate the HTML reference with `./mvnw javadoc:javadoc` (output in `target/reports/apidocs`).

## Documents

- [docs/api/](docs/api/README.md): **the API contract for the frontend**: conventions, [errors](docs/api/errors.md), one page per area, the [integration guide](docs/api/integration-guide.md) and the generated [OpenAPI spec](docs/api/openapi.json)
- [docs/architecture/](docs/architecture/README.md): system architecture, module map, data model and decision records
- [docs/security/](docs/security/README.md): threat model, controls and the test behind each, [observability](docs/security/observability.md)
- [docs/quality/](docs/quality/README.md): quality gates, [testing guide](docs/quality/testing-guide.md), [findings](docs/quality/findings.md)
- [docs/operations.md](docs/operations.md): deploy, backup, restore
- [docs/reports.md](docs/reports.md): report definitions
- [docs/business-and-system-proposal.md](docs/business-and-system-proposal.md): business problem and scope
- [docs/design-foundation-and-stock.md](docs/design-foundation-and-stock.md): design notes

### Keeping the API contract in sync

The OpenAPI spec is generated from the code and committed as `docs/api/openapi.json`; `OpenApiSnapshotTests` fails
when they differ. After an intended API change run `./mvnw test "-Dtest=OpenApiSnapshotTests" "-Dopenapi.update=true"`,
commit the regenerated file, and update the matching page under `docs/api/`. The running app also serves the spec at
`/v3/api-docs` (bearer token required).
