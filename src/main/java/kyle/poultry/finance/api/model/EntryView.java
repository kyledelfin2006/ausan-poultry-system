package kyle.poultry.finance.api.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import kyle.poultry.finance.api.model.EntryType.CashDirection;

/**
 * Read-only copy of one ledger entry, safe to hand to other modules and to serialise.
 *
 * @param id            entry id
 * @param type          what the entry means
 * @param cashDirection whether cash came in, went out or did not move
 * @param amount        positive PHP amount
 * @param method        payment method; null for entries that move no cash
 * @param occurredOn    business date (Asia/Manila)
 * @param description   optional note
 * @param sourceType    kind of record that caused it (e.g. DELIVERY); null for expenses
 * @param sourceId      id of that record
 * @param reversesId    for reversal entries, the entry being cancelled
 * @param createdBy     id of the user who recorded it
 * @param createdAt     when it was recorded
 */
public record EntryView(long id, EntryType type, CashDirection cashDirection, BigDecimal amount, PaymentMethod method,
                        LocalDate occurredOn, String description, String sourceType, Long sourceId, Long reversesId,
                        long createdBy, Instant createdAt) {}
