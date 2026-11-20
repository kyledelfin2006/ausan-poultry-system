# Stock: all-branch summary, low-stock flags and reorder levels

Read-side additions to the stock endpoints. Recording movements, balances and history are unchanged (see the main API
contract).

| Endpoint | Roles | Purpose |
|---|---|---|
| `GET /api/stock/summary?lowOnly=` | OWNER, ASSISTANT | stock per product over all branches, with low flags |
| `GET /api/stock/reorder-levels` | OWNER, ASSISTANT | the configured reorder levels |
| `PUT /api/stock/reorder-levels/{productId}` | **OWNER only** | set or replace a level |
| `DELETE /api/stock/reorder-levels/{productId}` | **OWNER only** | remove a level |

## The low-stock rule

A **reorder level** belongs to a product and is a number of units (0 or more). It is applied **per branch**, because stock does
not move between branches:

- a branch is **low** on a product when the product has a level and the branch holds **at most** that many units (a branch
  at exactly the level is already low; a level of 0 flags only an empty branch);
- a product is **low** when any of its branches is low;
- a product that has a level but that no branch has ever held is low too (it is out of stock everywhere);
- a product without a level is never low, however empty.

Limitation: only branches that have held the product are listed (a branch that never stocked it has no row). Such a branch is
not flagged on its own; if no branch holds the product at all, the product-level rule above applies.

## `GET /api/stock/summary`

Query: `lowOnly` (default `false`). With `lowOnly=true` only low products come back. Ordered by product name. Active products
are always listed (with a total of 0 when nothing is held); an inactive product is listed only while some branch still holds it.

```json
[
  { "productId": 2, "productName": "Chicken", "unit": "head", "totalQuantity": 112,
    "reorderLevel": 50, "low": true,
    "branches": [
      { "branchId": 1, "quantity": 40, "averageCost": 380.0000, "low": true },
      { "branchId": 2, "quantity": 72, "averageCost": 391.2500, "low": false }
    ] },
  { "productId": 3, "productName": "Nipple", "unit": "pc", "totalQuantity": 0,
    "reorderLevel": null, "low": false, "branches": [] }
]
```

`branches` is ordered by branch id. Branch names belong to the catalog: look them up with `GET /api/branches`.

## `GET /api/stock/reorder-levels`

```json
[ { "productId": 2, "productName": "Chicken", "level": 50, "updatedBy": 1, "updatedAt": "2026-11-20T02:15:00Z" } ]
```

Ordered by product name. Only the latest value is kept (`updatedBy` / `updatedAt` describe the last change).

## `PUT /api/stock/reorder-levels/{productId}` (OWNER)

Body: `{ "level": 50 }`. Creates the level or replaces the existing one; returns the stored level (same shape as one list item).

| Status | When |
|---|---|
| 200 | stored |
| 400 | `level` missing or negative |
| 401 / 403 | no token / caller is an assistant |
| 404 | the product does not exist |
| 422 | the product is inactive |

## `DELETE /api/stock/reorder-levels/{productId}` (OWNER)

`204` with no body when removed. `404` when the product has no level; `403` for an assistant.
