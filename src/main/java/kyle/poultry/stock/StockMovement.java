package kyle.poultry.stock;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import kyle.poultry.identity.Actor;
import kyle.poultry.shared.RuleViolation;
import kyle.poultry.shared.SourceRef;
import kyle.poultry.stock.StockApi.MovementView;
import kyle.poultry.stock.StockApi.StockCommand;

/**
 * One immutable fact: "this much stock entered or left this branch, for this reason". Never updated or deleted.
 * <p>
 * Rows live in {@code stock_movement}, the append-only ledger. A mistake is corrected by adding a new row (a
 * reversal or an adjustment), never by editing an old one. The sign and reason rules are checked in
 * {@link #create} and again by database check constraints. Package-private: other modules see
 * {@link MovementView}.
 */
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

    /** Required by JPA to instantiate the entity when loading it; not for application use. */
    protected StockMovement() {}

    /**
     * Builds a new movement from a command after checking every business rule; nothing is saved here.
     * <p>
     * The cost is rounded to 4 decimals (half-up) to match the {@code numeric(14,4)} column. Opening stock and
     * donations default to a zero cost; other types without a cost are stamped later with the average cost.
     *
     * @param c      the command describing the movement
     * @param source the record that caused it (e.g. a delivery), or {@code null} for manual movements
     * @param actor  the user who recorded it
     * @param now    the creation timestamp
     * @return the new, unsaved movement
     * @throws RuleViolation if the type or date is missing, the quantity sign is illegal for the type, a
     *         required reason is blank, the unit cost is negative, or a purchase has no cost or a zero cost
     */
    static StockMovement create(StockCommand c, SourceRef source, Actor actor, Instant now) {
        // Rules are checked first, so an invalid command never touches the database.
        if (c.type() == null) throw new RuleViolation("Movement type is required");
        // The sign must match the type (e.g. a purchase cannot remove stock); the database repeats this check.
        if (!c.type().accepts(c.quantityDelta()))
            throw new RuleViolation(c.type() + " does not allow a quantity of " + c.quantityDelta());
        if (c.type().needsReason() && (c.reason() == null || c.reason().isBlank()))
            throw new RuleViolation(c.type() + " requires a reason");
        if (c.unitCost() != null && c.unitCost().signum() < 0) throw new RuleViolation("Unit cost cannot be negative");
        if (c.type() == MovementType.PURCHASE && c.unitCost() == null)
            throw new RuleViolation("A purchase requires a unit cost");
        // Free stock must not be recorded as a purchase: it would dilute the average cost and log a zero-peso payment.
        if (c.type() == MovementType.PURCHASE && c.unitCost().signum() == 0)
            throw new RuleViolation("A purchase needs a unit cost above zero; record free stock as a DONATION");
        if (c.occurredOn() == null) throw new RuleViolation("Date is required");

        // Copy the validated values into the new ledger row.
        var m = new StockMovement();
        m.productId = c.productId();
        m.branchId = c.branchId();
        m.type = c.type();
        m.quantityDelta = c.quantityDelta();
        // Opening stock and donations cost nothing unless the caller says otherwise.
        boolean freeByDefault = c.type() == MovementType.DONATION || c.type() == MovementType.OPENING;
        BigDecimal cost = c.unitCost() == null && freeByDefault ? BigDecimal.ZERO : c.unitCost();
        // A null cost is kept as null here; stampCostIfMissing fills it with the average cost after the balance update.
        m.unitCost = cost == null ? null : cost.setScale(4, RoundingMode.HALF_UP);
        m.reason = c.reason();
        m.occurredOn = c.occurredOn();
        // The source is a loose reference (no foreign key); manual movements have none.
        if (source != null) { m.sourceType = source.type(); m.sourceId = source.id(); }
        m.createdBy = actor.userId();
        m.createdAt = now;
        return m;
    }

    /**
     * The compensating movement for a delivery stock-out: same product, branch and cost, opposite sign.
     * The new row points back at this one through {@code reversesId}; this row is left untouched.
     *
     * @param reason the explanation for the reversal
     * @param on     the business date of the reversal
     * @param actor  the user who requested it
     * @param now    the creation timestamp
     * @return the new, unsaved reversal movement of type {@code DELIVERY_REVERSAL}
     */
    StockMovement reversal(String reason, LocalDate on, Actor actor, Instant now) {
        var m = new StockMovement();
        m.productId = productId;
        m.branchId = branchId;
        m.type = MovementType.DELIVERY_REVERSAL;
        // Opposite sign: a stock-out of -7 becomes a reversal of +7.
        m.quantityDelta = -quantityDelta;
        // Same cost as the original so the goods return at the cost they left with.
        m.unitCost = unitCost;
        m.reason = reason;
        m.occurredOn = on;
        // Same source as the original, so the reversal is found alongside it.
        m.sourceType = sourceType;
        m.sourceId = sourceId;
        // Link to the original; the database allows each movement to be reversed at most once.
        m.reversesId = id;
        m.createdBy = actor.userId();
        m.createdAt = now;
        return m;
    }

    /**
     * Movements without an explicit cost (stock-outs, adjustments) record the average cost at that moment.
     *
     * @param averageCost the balance's average unit cost after the update; ignored if a cost is already set
     */
    void stampCostIfMissing(BigDecimal averageCost) {
        // Only fills a gap: purchases, openings and donations already carry their own cost.
        if (unitCost == null) unitCost = averageCost;
    }

    /** {@return the database id (null until the movement is saved)} */
    Long id() { return id; }

    /** {@return the product affected} */
    long productId() { return productId; }

    /** {@return the branch affected} */
    long branchId() { return branchId; }

    /** {@return the signed quantity (positive adds stock, negative removes it)} */
    long quantityDelta() { return quantityDelta; }

    /** {@return the movement type} */
    MovementType type() { return type; }

    /** {@return the cost per unit (4 decimals), or {@code null} if not yet stamped} */
    BigDecimal unitCost() { return unitCost; }

    /** {@return the immutable public representation of this movement} */
    MovementView view() {
        return new MovementView(id, productId, branchId, type, quantityDelta, unitCost, reason, occurredOn,
                sourceType, sourceId, reversesId, createdBy, createdAt);
    }
}
