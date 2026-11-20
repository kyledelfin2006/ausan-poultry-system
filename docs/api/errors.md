# Errors

Every error that has a body is an RFC 9457 problem detail (`Content-Type: application/problem+json`) with a stable
machine-readable `code`:

```json
{ "title": "Unprocessable Content", "status": 422, "code": "RULE_VIOLATION",
  "detail": "Date cannot be in the future", "instance": "/api/stock/movements" }
```

| Member | Meaning |
|---|---|
| `status` | The HTTP status, repeated. |
| `code` | Stable identifier. **Branch on this.** A code is never renamed or reused; new codes may be added, so keep a fallback for unknown ones. |
| `detail` | Human-readable explanation, safe to show to the user. Wording may change; never parse it. |
| `title`, `instance` | Standard status text and the request path. |

## Error codes

| `code` | HTTP | When | What the UI should do |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | A request body field failed validation. `detail` is `field message; field message`, e.g. `quantity must be greater than 0`. | Highlight the fields; the order of fields in `detail` is not guaranteed. |
| `BAD_REQUEST` | 400 | The request could not be understood: unreadable JSON, a missing or wrongly typed query parameter or path id (`Failed to convert 'id' ...`), or a bad `Idempotency-Key` header. | This is a client bug; show a generic error and log it. |
| `UNAUTHENTICATED` | 401 | Login with a wrong username or password (`Invalid username or password`). | Show the message on the login form. |
| `FORBIDDEN` | 403 | The role may not perform the action. *Not sent by the API with a body today, see below.* | Hide the action for that role. |
| `NOT_FOUND` | 404 | The addressed record does not exist (`Delivery 99 not found`), or the route does not exist. | Show "no longer exists" and refresh the list. |
| `METHOD_NOT_ALLOWED` | 405 | The path exists, but not for this HTTP method. | Client bug. |
| `NOT_ACCEPTABLE` | 406 | The server cannot produce what the `Accept` header asks for. | Client bug. |
| `CONFLICT` | 409 | The request clashes with the current state: insufficient stock (`Insufficient stock for product 2 at branch 1`), username already taken, delivery no longer ACTIVE (done or cancelled twice, cancel after Done). | Show `detail`; refresh the data the user was looking at. |
| `DATA_CONFLICT` | 409 | A database rule rejected the change, e.g. a duplicate product, branch or package name or SKU. `detail` is always `The change conflicts with existing data`. | Tell the user the name or SKU is probably already taken. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | The body is not JSON. | Client bug; send `Content-Type: application/json`. |
| `RULE_VIOLATION` | 422 | The request is well-formed but a business rule forbids it: future date, inactive product/branch/package, missing payment method, refund above what is held, duplicate item, report period longer than 366 days, wrong current password, `Idempotency-Key` reused with a different request. | Show `detail` to the user. |
| `TOO_MANY_REQUESTS` | 429 | Five failed logins within 15 minutes for the same username and address. The `Retry-After` header holds the seconds to wait. | Disable the login button and show a countdown. |
| `INTERNAL_ERROR` | 500 | An unexpected server error. `detail` is always `An unexpected error occurred`; nothing internal is leaked. | Show a generic "try again later" and report it. |

Rule of thumb for the UI: `400` = fix the form or it's a bug, `409`/`422` = show `detail`, `401` = go to login,
`403` = hide the action, `5xx` = retry later.

## 401 and 403 from the security layer have no body

A missing, malformed, expired or revoked token answers **401 with an empty body**, and a role that is not allowed to do
something answers **403 with an empty body**. These come from the security filter chain before any controller runs, so
there is no `code`: the HTTP status is the contract.

| Status | Means | UI action |
|---|---|---|
| 401 (any endpoint except login) | No valid session: token missing, tampered, expired, or the user was deactivated or deleted. | Clear the token and go to the login page. |
| 403 | Logged in, but this role may not do this. | Hide or disable the action; do not log out. |

The exception is the login endpoint, whose 401 carries a body with `code: UNAUTHENTICATED`.

## Statuses per endpoint

The OpenAPI spec lists, for every operation, exactly which of these statuses it can answer, and the per-area pages
mention the interesting ones next to each endpoint.
