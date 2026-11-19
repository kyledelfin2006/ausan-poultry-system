package kyle.poultry.stock;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import kyle.poultry.identity.Actor;
import kyle.poultry.shared.RuleViolation;
import kyle.poultry.stock.StockApi.MovementView;
import kyle.poultry.stock.StockApi.SourceRef;
import kyle.poultry.stock.StockApi.StockCommand;

/** One immutable fact: "this much stock entered or left this branch, for this reason". Never updated or deleted. */
@Entity
@Table(name = "stock_movement")
class StockMovement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private long productId;
    private long branchId;
    @Enumerated(EnumType.STRING) private MovementType type;
    private long quantityDelta;
    private BigDecimal unitCost;
    private String reason;
    private LocalDate occurredOn;
    private String sourceType;
    private Long sourceId;
    private Long reversesId;
    private long createdBy;
    private Instant createdAt;

    protected StockMovement() {}

    static StockMovement create(StockCommand c, SourceRef source, Actor actor, Instant now) {
        if (c.type() == null) throw new RuleViolation("Movement type is required");
        if (!c.type().accepts(c.quantityDelta()))
            throw new RuleViolation(c.type() + " does not allow a quantity of " + c.quantityDelta());
        if (c.type().needsReason() && (c.reason() == null || c.reason().isBlank()))
            throw new RuleViolation(c.type() + " requires a reason");
        if (c.unitCost() != null && c.unitCost().signum() < 0) throw new RuleViolation("Unit cost cannot be negative");
        if (c.occurredOn() == null) throw new RuleViolation("Date is required");

        var m = new StockMovement();
        m.productId = c.productId();
        m.branchId = c.branchId();
        m.type = c.type();
        m.quantityDelta = c.quantityDelta();
        m.unitCost = c.unitCost() == null ? null : c.unitCost().setScale(2, RoundingMode.HALF_UP);
        m.reason = c.reason();
        m.occurredOn = c.occurredOn();
        if (source != null) { m.sourceType = source.type(); m.sourceId = source.id(); }
        m.createdBy = actor.userId();
        m.createdAt = now;
        return m;
    }

    /** The compensating movement for a delivery stock-out: same product and branch, opposite sign. */
    StockMovement reversal(String reason, LocalDate on, Actor actor, Instant now) {
        var m = new StockMovement();
        m.productId = productId;
        m.branchId = branchId;
        m.type = MovementType.DELIVERY_REVERSAL;
        m.quantityDelta = -quantityDelta;
        m.reason = reason;
        m.occurredOn = on;
        m.sourceType = sourceType;
        m.sourceId = sourceId;
        m.reversesId = id;
        m.createdBy = actor.userId();
        m.createdAt = now;
        return m;
    }

    Long id() { return id; }
    long productId() { return productId; }
    long branchId() { return branchId; }
    long quantityDelta() { return quantityDelta; }
    MovementType type() { return type; }

    MovementView view() {
        return new MovementView(id, productId, branchId, type, quantityDelta, unitCost, reason, occurredOn,
                sourceType, sourceId, reversesId, createdBy, createdAt);
    }
}
