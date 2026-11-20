# Catalog: products, branches, packages

Reading is open to both roles; creating and editing is OWNER only (`403` for an assistant).

Seed data: products `Cage` (pc), `Chicken` (head), `Nipple` (pc), `Water jug` (pc), `Feeders` (pc), `Feed` (bag);
branches `Branch 1`, `Branch 2` (placeholders, the owner renames them); packages `Package Small` (70,000),
`Package Medium` (160,000), `Package Commercial` (700,000).

Lists include inactive records (`active: false`); filter in the UI. Records are never deleted: deactivate instead.

## Products

### `GET /api/products` (any role)

`200`:

```json
[ { "id": 2, "name": "Chicken", "unit": "head", "defaultPrice": null, "active": true },
  { "id": 3, "name": "Nipple", "unit": "pc", "defaultPrice": null, "active": true } ]
```

`defaultPrice` is provisional and currently unused: customers buy [packages](#packages), not single products.

### `POST /api/products` (OWNER)

| Field | Type | Required | Limits |
|---|---|---|---|
| name | string | yes | max 120, unique |
| unit | string | yes | max 20 |
| defaultPrice | number | no | >= 0, up to 2 decimals |

`201` returns the product. Errors: `400`, `403`, `409` (`DATA_CONFLICT`, duplicate name).

### `PATCH /api/products/{id}` (OWNER)

Any subset of `name` (1-120), `unit` (1-20), `defaultPrice` (>= 0), `active` (boolean). `200` returns the product.
Errors: `400`, `403`, `404`, `409`.

## Branches

### `GET /api/branches` (any role)

`200`: `[ { "id": 1, "name": "Branch 1", "active": true } ]`

### `POST /api/branches` (OWNER)

`{ "name": "Branch 3" }` (required, max 80, unique). `201` returns the branch. Errors: `400`, `403`, `409`.

### `PATCH /api/branches/{id}` (OWNER)

Any subset of `name` (1-80), `active`. `200` returns the branch. Errors: `400`, `403`, `404`, `409`.

There are no transfers between branches: each branch's stock is separate.

## Packages

A package is a fixed set of products sold together at a fixed price.

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

`201` returns the package. Errors: `400`, `403`, `404` (unknown product), `409` (duplicate sku or name), `422` (empty
items, duplicate product).

### `PATCH /api/packages/{id}` (OWNER)

Any subset of `name` (1-120), `price` (>= 0.01), `active`. **Contents cannot be edited** after creation. Past deliveries
keep the name and price they were sold at. `200` returns the package. Errors: `400`, `403`, `404`, `409`.
