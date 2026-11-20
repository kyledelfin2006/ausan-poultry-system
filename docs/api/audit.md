# Audit trail (OWNER only)

An append-only record of **who changed the master data**: users, products, branches and sales packages. Money and
stock have their own append-only ledgers (`/api/finance/entries`, `/api/stock/movements`), each row of which already
records its author; the audit trail covers what those ledgers do not.

Entries are written in the same database transaction as the change they describe: a change that fails leaves no entry,
and a change that succeeds always has one. The database refuses `UPDATE`, `DELETE` and `TRUNCATE` on the table, so history
cannot be edited even by a bug.

## `GET /api/audit` (OWNER)

Newest first. Every filter is optional and filters combine with AND.

| Query parameter | Meaning |
|---|---|
| `entityType` | `USER`, `PRODUCT`, `BRANCH` or `PACKAGE` |
| `entityId` | the id of that record (use with `entityType`) |
| `actorId` | only changes made by this user id |
| `from`, `to` | `yyyy-MM-dd`, inclusive business days in Manila time. `from` after `to` is `422`. |
| `page`, `size` | zero-based page (default 0) and size 1-100 (default 20). Out of range is `400`. |

```json
{ "content": [
    { "id": 41, "occurredAt": "2026-11-20T02:15:00Z", "actorUserId": 1, "actorUsername": "owner",
      "action": "UPDATED", "entityType": "PACKAGE", "entityId": 2,
      "summary": "price: 160000.00 -> 165000.00", "requestId": "3f0c2a52-6a71-4e2e-9bd2-0c3a6a1c9a10" } ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 } }
```

| Field | Notes |
|---|---|
| `actorUserId`, `actorUsername` | who did it. `null` / `"system"` when the system did it (the first owner created at startup). The username is a copy taken at the time, so old entries stay readable. |
| `action` | `CREATED`, `UPDATED`, `PASSWORD_RESET`, `PASSWORD_CHANGED`, `SIGNED_OUT` |
| `entityType`, `entityId` | what changed. For `USER` entries the id is the affected user's. |
| `summary` | readable text for display. `UPDATED` lists only the fields that changed as `field: old -> new`, separated by `; `. Never contains passwords, hashes or tokens. Treat it as display text, not as something to parse. |
| `requestId` | the `X-Request-Id` of the HTTP call that made the change, to find its server log lines. May be `null`. |

An update that changes nothing (same values) is **not** recorded.

What is recorded:

| `entityType` | Actions |
|---|---|
| `USER` | `CREATED`; `UPDATED` (display name, role, active); `PASSWORD_RESET` (owner reset); `PASSWORD_CHANGED` (own change); `SIGNED_OUT` |
| `PRODUCT` | `CREATED`; `UPDATED` (name, unit, defaultPrice, active) |
| `BRANCH` | `CREATED`; `UPDATED` (name, active) |
| `PACKAGE` | `CREATED`; `UPDATED` (name, price, active) |

Errors: `401` no token, `403` assistant, `400` bad parameter, `422` inverted date range.

Suggested UI: an owner-only "Activity" screen with filters for record type and person, and an "Activity" link on each
product, branch, package and user page that opens this list with `entityType` and `entityId` filled in.
