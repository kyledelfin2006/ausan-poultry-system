package kyle.poultry.finance;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import kyle.poultry.finance.FinanceApi.EntryView;
import kyle.poultry.identity.Actor;
import kyle.poultry.shared.RuleViolation;
import kyle.poultry.shared.SourceRef;

/** One immutable financial fact. Never updated or deleted; mistakes are reversed by new rows. */
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

    protected FinancialEntry() {}

    static FinancialEntry of(EntryType type, BigDecimal amount, PaymentMethod method, LocalDate on,
                             String description, SourceRef source, Actor actor, Instant now) {
        if (type.cashDirection() != EntryType.CashDirection.NONE && method == null)
            throw new RuleViolation(type + " requires a payment method");
        if (on == null) throw new RuleViolation("Date is required");
        var e = new FinancialEntry();
        e.type = type;
        e.cashDirection = type.cashDirection();
        e.amount = amount;
        e.method = type.cashDirection() == EntryType.CashDirection.NONE ? null : method;
        e.occurredOn = on;
        e.description = description;
        if (source != null) { e.sourceType = source.type(); e.sourceId = source.id(); }
        e.createdBy = actor.userId();
        e.createdAt = now;
        return e;
    }

    /** The compensating row for a SALE or COST_OF_GOODS. */
    FinancialEntry reversal(LocalDate on, Actor actor, Instant now) {
        EntryType reversalType = switch (type) {
            case SALE -> EntryType.SALE_REVERSAL;
            case COST_OF_GOODS -> EntryType.COGS_REVERSAL;
            default -> throw new RuleViolation(type + " cannot be reversed");
        };
        var e = new FinancialEntry();
        e.type = reversalType;
        e.cashDirection = EntryType.CashDirection.NONE;
        e.amount = amount;
        e.occurredOn = on;
        e.sourceType = sourceType;
        e.sourceId = sourceId;
        e.reversesId = id;
        e.createdBy = actor.userId();
        e.createdAt = now;
        return e;
    }

    Long id() { return id; }
    EntryType type() { return type; }
    BigDecimal amount() { return amount; }

    EntryView view() {
        return new EntryView(id, type, cashDirection, amount, method, occurredOn, description,
                sourceType, sourceId, reversesId, createdBy, createdAt);
    }
}
