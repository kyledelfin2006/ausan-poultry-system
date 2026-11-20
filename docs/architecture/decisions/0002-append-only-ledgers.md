# ADR-0002: Append-only ledgers

- Status: Accepted
- Date: 2026-11-20 (records the decision taken at project start)

## Context

The owner must be able to answer "why do we have 37 chickens, and where did the money go?" months later, and trust that the
answer was not changed after the fact. Staff make mistakes: a wrong quantity, a cancelled delivery. An `UPDATE` that fixes a number
destroys the evidence of what happened.

## Decision

Stock (`stock_movement`) and money (`financial_entry`) are **append-only**:

- Rows are inserted, never updated or deleted. The code has no update path, and the schema has no mutable columns for them.
- A correction is a new row. A **reversal** carries `reverses_id` pointing at the row it undoes, and `reverses_id` is `unique`, so a
  row can be undone once. An **adjustment** (owner only, reason required) fixes a counted difference.
- Every row records who (`created_by`), when (`created_at`) and the business date (`occurred_on`, Manila).
- `stock_balance` is a read model kept in step with the ledger by the same statement in the same transaction, never written alone.
- Business records (a delivery) may change status, but their effect on the books is always a ledger row.

## Consequences

- Complete audit trail; a report for any past date can be reproduced.
- A cancelled delivery leaves four visible facts (sale, cost, their reversals) instead of silently vanishing; reports net them to zero.
- Queries sum rows; the current balance is a cache to avoid that. The invariant "balance equals the sum of movements" must be
  tested, and is (`WeightedAverageCostTests`).
- Ledgers only grow. At this business's volume (a few thousand rows a year) that is irrelevant; an archive strategy can come later.
- A mistake in a *reversal* is corrected with an adjustment, which is more steps than editing, by design.

## Alternatives considered

- **Mutable rows with an audit table.** Rejected: two sources of truth and an easy way to forget the audit.
- **Event sourcing with a dedicated store.** Rejected: far more machinery than two ledgers need; a relational ledger gives the same
  history with SQL reporting.
- **Soft delete flag.** Rejected: it hides history rather than preserving it, and a deleted row still affects the balance.
