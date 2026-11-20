# ADR-0005: Idempotent delivery creation

- Status: Accepted
- Date: 2026-11-20 (records the decision taken during the delivery slice)

## Context

Staff will work on phones over unreliable mobile networks. When a "create delivery" request times out, the client cannot know whether
the delivery exists. Retrying blindly can sell the same packages twice (two stock-outs, two payments); not retrying can lose the delivery.

## Decision

`POST /api/deliveries` accepts an optional `Idempotency-Key` header (client-chosen, e.g. a UUID):

- The key is scoped **per user**: the same key from two users is two different keys.
- Inside the creating transaction, a transaction-scoped advisory lock `pg_advisory_xact_lock(hashtextextended('<user>:<key>', 0))`
  serialises requests that carry the same key; the second finds the committed row of the first.
- `delivery_idempotency (idempotency_key, user_id)` is the primary key and the backstop.
- A retry with an **identical body** returns the original delivery (the same representation), changing nothing.
- A retry with a **different body** is refused with 422: the key is already bound to another request.
- The key row is written in the same transaction as the delivery, so a failed creation does not consume the key and can be retried.
- Without the header, behaviour is unchanged (no protection).

## Consequences

- A client can retry safely until it gets an answer.
- The lock is on the *key*, not on a table, so unrelated deliveries never wait for each other.
- Keys are kept indefinitely (one small row per keyed delivery); no cleanup is needed at this volume.
- "Identical body" is judged on the business content (branch, customer, lines, price, payment method, start time); an omitted
  price override is treated as matching any price. Listed as an observation in [../../quality/findings.md](../../quality/findings.md).
- Idempotency covers creation only. Cancel and refund are naturally safe to repeat (a second cancel is 409, a refund is capped).

## Alternatives considered

- **Natural-key duplicate detection** (same customer, same packages, within N minutes). Rejected: guesses at intent and would
  block two legitimate identical sales.
- **Unique constraint only, no advisory lock.** Correct for the second row, but the loser would fail after having done the stock work
  and surface a constraint error instead of the first result.
- **Client-generated delivery id (PUT).** Possible, but the human-readable code and the server-side sequence suit the business better.
