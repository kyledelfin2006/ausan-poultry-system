package kyle.poultry.finance;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import kyle.poultry.finance.FinanceApi.EntryView;
import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.shared.error.RuleViolation;
import kyle.poultry.shared.reference.SourceRef;

/**
 * One immutable financial fact: a sale, a cost, a payment, a refund, a purchase or an expense, stored as a row of
 * the {@code financial_entry} table. It belongs to the finance module and is package-private; other modules see only
 * {@link EntryView}. It is never updated or deleted; mistakes are corrected by new reversal rows.
 */
@Entity
@Table(name = "financial_entry")
class FinancialEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Enumerated(EnumType.STRING) private EntryType type;
    @Enumerated(EnumType.STRING) private EntryType.CashDirection cashDirection;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING) private PaymentMethod method;
    private LocalDate occurredOn;
    private String description;
    private String sourceType;
    private Long sourceId;
    private Long reversesId;
    private long createdBy;
    private Instant createdAt;

    /** Required by JPA to load rows; application code builds entries through {@link #of}. */
    protected FinancialEntry() {}

    /**
     * Builds a new entry and enforces the rules that hold for every row.
     *
     * @param type        what the entry means; fixes the cash direction
     * @param amount      positive amount already at scale 2
     * @param method      payment method; required when cash moves, dropped when it does not
     * @param on          business date
     * @param description optional note
     * @param source      originating record, or null
     * @param actor       who is recording it
     * @param now         creation timestamp
     * @return the new, unsaved entry
     * @throws RuleViolation if a cash entry has no method or the date is missing
     */
    static FinancialEntry of(EntryType type, BigDecimal amount, PaymentMethod method, LocalDate on,
                             String description, SourceRef source, Actor actor, Instant now) {
        // Cash must always say how it moved; the database enforces the same rule as a last line of defence.
        if (type.cashDirection() != EntryType.CashDirection.NONE && method == null)
            throw new RuleViolation(type + " requires a payment method");
        if (on == null) throw new RuleViolation("Date is required");
        var e = new FinancialEntry();
        e.type = type;
        e.cashDirection = type.cashDirection();
        e.amount = amount;
        // Sales and costs move no cash, so any method passed in is discarded rather than stored.
        e.method = type.cashDirection() == EntryType.CashDirection.NONE ? null : method;
        e.occurredOn = on;
        e.description = description;
        if (source != null) { e.sourceType = source.type(); e.sourceId = source.id(); }
        e.createdBy = actor.userId();
        e.createdAt = now;
        return e;
    }

    /**
     * Builds the compensating row that cancels this SALE or COST_OF_GOODS: same amount and source, a reversal type,
     * and a link back to the original so it can only be reversed once.
     *
     * @param on    business date of the cancellation
     * @param actor who is cancelling
     * @param now   creation timestamp
     * @return the new, unsaved reversal entry
     * @throws RuleViolation if this entry is not a SALE or COST_OF_GOODS
     */
    FinancialEntry reversal(LocalDate on, Actor actor, Instant now) {
        // Only revenue and cost can be cancelled; money rows are corrected with a refund instead.
        EntryType reversalType = switch (type) {
            case SALE -> EntryType.SALE_REVERSAL;
            case COST_OF_GOODS -> EntryType.COGS_REVERSAL;
            default -> throw new RuleViolation(type + " cannot be reversed");
        };
        var e = new FinancialEntry();
        e.type = reversalType;
        e.cashDirection = EntryType.CashDirection.NONE;
        // The reversal carries the same amount so the pair nets to zero in reports.
        e.amount = amount;
        e.occurredOn = on;
        e.sourceType = sourceType;
        e.sourceId = sourceId;
        // The unique reverses_id column makes a second reversal of the same row impossible.
        e.reversesId = id;
        e.createdBy = actor.userId();
        e.createdAt = now;
        return e;
    }

    /** {@return the database id, null until saved} */
    Long id() { return id; }

    /** {@return what this entry means} */
    EntryType type() { return type; }

    /** {@return the positive PHP amount} */
    BigDecimal amount() { return amount; }

    /** {@return an immutable copy of this entry for use outside the module} */
    EntryView view() {
        return new EntryView(id, type, cashDirection, amount, method, occurredOn, description,
                sourceType, sourceId, reversesId, createdBy, createdAt);
    }
}
