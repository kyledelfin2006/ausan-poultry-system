# Errors

Every error with a body is an RFC 9457 problem detail (`application/problem+json`):

```json
{ "title": "Unprocessable Content", "status": 422, "code": "RULE_VIOLATION",
  "detail": "Date cannot be in the future", "instance": "/api/stock/movements" }
```

Branch on `code`: it is stable, never renamed or reused, and new codes may appear, so keep a fallback. `detail` is safe to show to the user but its wording may change; never parse it.

| `code` | HTTP | When | UI action |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | A body field failed validation. `detail` is `field message; field message`. | Highlight the fields. |
| `BAD_REQUEST` | 400 | Unreadable JSON, a missing or mistyped parameter, or a bad `Idempotency-Key`. | Client bug; show a generic error and log it. |
| `UNAUTHENTICATED` | 401 | Wrong username or password at login. | Show on the login form. |
| `FORBIDDEN` | 403 | Role not allowed (see below: the API sends this status with no body). | Hide the action. |
| `NOT_FOUND` | 404 | The record or route does not exist. | Show "no longer exists" and refresh. |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP method for the path. | Client bug. |
| `NOT_ACCEPTABLE` | 406 | Cannot produce the `Accept` type. | Client bug. |
| `CONFLICT` | 409 | Clashes with current state: insufficient stock, username taken, delivery no longer ACTIVE. | Show `detail`; refresh. Never auto-retry. |
| `DATA_CONFLICT` | 409 | A database rule refused it, e.g. duplicate product, branch or package name or SKU. | Say the name or SKU is probably taken. Never auto-retry. |
| `REQUEST_IN_PROGRESS` | 409 | An identical request with the same `Idempotency-Key` is still running. `Retry-After` gives seconds. | **The only 409 safe to repeat:** wait, resend the identical request with the same key, give up after about 3 tries. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Body is not JSON. | Client bug. |
| `RULE_VIOLATION` | 422 | Well-formed but a business rule forbids it: future or over-backdated date, inactive product/branch/package, missing payment method, refund above what is held, duplicate line, period over 366 days, wrong current password, a password rule, reused `Idempotency-Key` with a different body. | Show `detail`. |
| `TOO_MANY_REQUESTS` | 429 | Login or password throttle, or three CSV exports already running. `Retry-After` gives seconds. | Disable the control for that long; never auto-retry. |
| `INTERNAL_ERROR` | 500 | Unexpected failure. `detail` is always generic; nothing internal leaks. | "Try again later"; report the request id. |

## 401 and 403 from the security layer have no body

A missing, malformed, expired or revoked token answers **401 with an empty body**; a role that may not do something answers **403 with an empty body**. They come from the security filter before any controller runs, so there is no `code`: the status is the contract. Do not call `res.json()` on them.

| Status | Means | UI action |
|---|---|---|
| 401 (any endpoint but login) | Token missing, tampered, expired, or the user was deactivated or deleted | Clear the token, go to login |
| 403 | Signed in, but this role may not do this | Hide or disable the action; do not log out |

The spec lists which statuses each operation can answer.
