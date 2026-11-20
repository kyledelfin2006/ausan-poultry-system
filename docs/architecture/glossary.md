# Glossary

Business words are in plain language; code names are given in `code font` where a term maps to a class or table.

| Term | Meaning |
|---|---|
| **Actor** (`identity.Actor`) | Who is doing something: a user id and a role. It is passed into every service call explicitly, so the rules can be tested without a security context. |
| **Adjustment** | A correction of the stock after a physical count (owner only, reason required). Adds or removes units at the current average cost. |
| **Append-only** | A table where rows are only ever added. Mistakes are fixed with new rows, never edits. See [ADR-0002](decisions/0002-append-only-ledgers.md). |
| **Assistant** | The second role (finance/assistant). Can record purchases, usage, donations, expenses and deliveries; cannot enter opening stock or adjustments, cancel, refund, or change the catalog. |
| **`*Api`** | The public interface of a module (`CatalogApi`, `StockApi`, `FinanceApi`). The only way one module may use another. |
| **Ausan Poultry System** | The official name of this software: the backend (and, later, the frontend) used by the Ausan Poultry Farm business. Its repository is `ausan-poultry-system`. The Maven artifact and Java package (`poultry`, `kyle.poultry`) are technical identifiers and are not the product name. |
| **Average cost** | The weighted average cost per unit of a product at a branch, four decimals. See [ledger-and-costing.md](ledger-and-costing.md). |
| **Audit trail** (`audit_log`) | The append-only record of who changed a user, product, branch or package, and what changed. Owner-only to read. Different from the ledgers: the ledgers record business events, the audit trail records changes to master data. |
| **Balance** (`stock_balance`) | The current quantity and average cost of one product at one branch; derived from the movements. |
| **Branch** | One of the two places that hold stock (Branch 1, Branch 2). Stock is never transferred between branches. |
| **Business date** (`occurred_on`) | The calendar date in Manila that a movement or entry belongs to. Reports are by business date. |
| **Cancel** | Owner-only reversal of an ACTIVE delivery: stock returns, sale and cost are reversed, the payment stays held until refunded. |
| **Catalog** | The module holding products, branches and sales packages. |
| **Centavo** | One hundredth of a peso; the smallest money unit. Amounts have at most two decimals. |
| **Cost of goods** (`COST_OF_GOODS`) | The cost of the stock a delivery took out, at the average cost of that moment. |
| **Delivery** | One sale of one or more packages to a customer from one branch, with a driver and a destination; paid in full when created. |
| **Delivery code** | The human-readable identifier such as `DLV-2026-0001` (year and sequence number). Unique, not gapless. |
| **Donation** | Stock received free of charge; enters at cost 0, which lowers the average. |
| **Entry** (`financial_entry`) | One row of the financial ledger. |
| **Idempotency key** | A value the client sends with a delivery request so a retry returns the original delivery instead of creating another. |
| **Ledger** | An append-only list of facts. There are two: the stock ledger (`stock_movement`) and the financial ledger (`financial_entry`). |
| **Manila time** | The business time zone, `Asia/Manila` (UTC+8). The injected `Clock` uses it; "today" is the Manila date. |
| **Module** | A package under `kyle.poultry` with its own job and public face. See [overview.md](overview.md). |
| **Movement** (`stock_movement`) | One row of the stock ledger: a signed quantity of one product at one branch, for a reason (its *type*). |
| **Opening stock** | The quantity counted when the system starts or when a branch first stocks a product (owner only). |
| **Owner** | The administrator role. Can do everything. |
| **Package** (`sales_package`) | A fixed bundle with a price (Small, Medium, Commercial) that customers buy. Its contents are fixed once created. |
| **PHP** | Philippine peso, the only currency. |
| **ProblemDetail** | The standard (RFC 9457) JSON shape of every error response. |
| **Purchase** | Stock bought from a supplier; adds units at a cost and records the cash out. |
| **Refund** | Money handed back to a customer for a *cancelled* delivery, by the owner, never more than is still held. |
| **Reorder level** (`stock_reorder_level`) | The quantity at or below which a branch counts as *low* on a product. Optional, one per product, owner-set. |
| **Request id** | The `X-Request-Id` of an HTTP request, shown in the log lines, the response and the audit row, so one action can be followed everywhere. |
| **Retained payment** | The money still held for a cancelled delivery: payments received minus refunds paid. |
| **Reversal** | A new row that exactly cancels an earlier one (`DELIVERY_REVERSAL`, `SALE_REVERSAL`, `COGS_REVERSAL`). The original is never changed. |
| **Revocation** | Making a token stop working before it expires. Done by re-reading the user on every request (deactivation, role change) and comparing the token's version with the user's *token version* (sign-out, password change), rather than by a token blacklist. |
| **Sale** (`SALE`) | The revenue of a delivery; moves no cash by itself (the payment is a separate entry). |
| **Slice** | One step of the build order (foundation, stock, finance, delivery, reports). |
| **`SourceRef`** | A loose (type, id) pointer from a ledger row to the business record that caused it, with no foreign key. |
| **Token version** (`app_user.token_version`) | A counter stored with each user and copied into their tokens (claim `ver`). Sign-out and password change raise it, which invalidates every earlier token of that user. |
| **Usage** | Stock consumed or lost on the farm (reason required); removes units at the average cost. |
| **View** | An immutable record a module hands to callers instead of an entity. |
