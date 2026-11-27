package kyle.poultry.delivery;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kyle.poultry.finance.api.model.PaymentMethod;
import kyle.poultry.shared.error.ConflictException;
import kyle.poultry.shared.error.RuleViolation;

/**
 * A paid delivery. Owns its status transitions; stock and money effects are orchestrated by {@link DeliveryService}.
 * JPA entity for table {@code delivery}; package-private so only this module changes it.
 */
@Entity
@Table(name = "delivery")
class Delivery {
    /** Database-generated primary key. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** Unique human-readable code such as {@code DLV-2026-0001}. */
    @Column(nullable = false, unique = true) private String code;
    /** Lifecycle state. */
    @Enumerated(EnumType.STRING) @Column(nullable = false) private DeliveryStatus status;
    /** Who the goods are for. */
    private String customerName;
    /** Who delivers them. */
    private String driverName;
    /** Branch whose stock was used. */
    private long fromBranchId;
    /** Where the goods go. */
    private String destination;
    /** When the trip started. */
    private Instant startedAt;
    /** When it arrived; null until done. */
    private Instant completedAt;
    /** Sum of the line totals. */
    private BigDecimal total;
    /** How the customer paid. */
    @Enumerated(EnumType.STRING) private PaymentMethod paymentMethod;
    /** Why it was cancelled; null unless cancelled. */
    private String cancelReason;
    /** When it was cancelled; null unless cancelled. */
    private Instant cancelledAt;
    /** Why the delivered goods came back; null unless returned. */
    private String returnReason;
    /** When the return was recorded; null unless returned. */
    private Instant returnedAt;
    /** Id of the user who created it. */
    private long createdBy;
    /** When it was created. */
    private Instant createdAt;

    /**
     * The packages sold, loaded together with the delivery (a delivery has only a handful of lines). Kept EAGER on
     * purpose: stock writes use {@code clearAutomatically}, which detaches the delivery mid-transaction, so a lazy
     * collection would fail when the view is built. {@code default_batch_fetch_size} keeps list pages to one extra
     * query for all lines.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "delivery_line", joinColumns = @JoinColumn(name = "delivery_id"))
    private List<DeliveryLine> lines = new ArrayList<>();

    /** Required by JPA; not for application code. */
    protected Delivery() {}

    /**
     * Creates a new ACTIVE delivery and computes its total from the lines.
     *
     * @param code         unique delivery code
     * @param customerName who the goods are for
     * @param driverName   who delivers them
     * @param fromBranchId branch whose stock is used
     * @param destination  where the goods go
     * @param startedAt    when the trip starts
     * @param method       how the customer paid
     * @param lines        the packages sold
     * @param createdBy    id of the creating user
     * @param now          creation time
     * @return the new, not yet saved delivery
     */
    static Delivery open(String code, String customerName, String driverName, long fromBranchId, String destination,
                         Instant startedAt, PaymentMethod method, List<DeliveryLine> lines, long createdBy, Instant now) {
        var d = new Delivery();
        d.code = code;
        d.status = DeliveryStatus.ACTIVE;
        d.customerName = customerName;
        d.driverName = driverName;
        d.fromBranchId = fromBranchId;
        d.destination = destination;
        d.startedAt = startedAt;
        d.paymentMethod = method;
        d.lines = new ArrayList<>(lines);
        // The total is derived once here so it can never disagree with the stored lines.
        d.total = lines.stream().map(DeliveryLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        d.createdBy = createdBy;
        d.createdAt = now;
        return d;
    }

    /**
     * Marks the delivery as arrived (ACTIVE -> DONE).
     *
     * @param completedAt when it arrived
     * @param now         the current time, used to reject future times
     * @throws ConflictException when the delivery is not ACTIVE
     * @throws RuleViolation     when the time is missing, in the future, or before the start
     */
    void markDone(Instant completedAt, Instant now) {
        requireActive("marked as done");
        if (completedAt == null) throw new RuleViolation("Completion time is required");
        if (completedAt.isAfter(now)) throw new RuleViolation("Completion time cannot be in the future");
        if (completedAt.isBefore(startedAt)) throw new RuleViolation("Completion time cannot be before the start time");
        this.status = DeliveryStatus.DONE;
        this.completedAt = completedAt;
    }

    /**
     * Cancels the delivery (ACTIVE -> CANCELLED).
     *
     * @param reason why it is cancelled
     * @param now    the cancellation time
     * @throws ConflictException when the delivery is not ACTIVE
     * @throws RuleViolation     when the reason is blank
     */
    void cancel(String reason, Instant now) {
        requireActive("cancelled");
        if (reason == null || reason.isBlank()) throw new RuleViolation("A cancellation reason is required");
        this.status = DeliveryStatus.CANCELLED;
        this.cancelReason = reason;
        this.cancelledAt = now;
    }

    /**
     * Records that the whole delivery came back after arrival (DONE -> RETURNED).
     *
     * @param reason why it came back
     * @param now    when the return is recorded
     * @throws ConflictException when the delivery is not DONE
     * @throws RuleViolation     when the reason is blank
     */
    void markReturned(String reason, Instant now) {
        if (status != DeliveryStatus.DONE)
            throw new ConflictException("Delivery " + code + " is " + status + "; only a DONE delivery can be returned");
        if (reason == null || reason.isBlank()) throw new RuleViolation("A return reason is required");
        this.status = DeliveryStatus.RETURNED;
        this.returnReason = reason;
        this.returnedAt = now;
    }

    /** {@return true when the sale was undone: the delivery is CANCELLED or RETURNED} */
    boolean isReversed() { return status == DeliveryStatus.CANCELLED || status == DeliveryStatus.RETURNED; }

    /**
     * Guards a transition: DONE and CANCELLED are final.
     *
     * @param action what is being attempted, for the error message
     * @throws ConflictException when the delivery is not ACTIVE
     */
    private void requireActive(String action) {
        if (status != DeliveryStatus.ACTIVE)
            throw new ConflictException("Delivery " + code + " is " + status + " and can no longer be " + action);
    }

    /** {@return the database id} */
    Long id() { return id; }
    /** {@return the delivery code} */
    String code() { return code; }
    /** {@return the lifecycle state} */
    DeliveryStatus status() { return status; }
    /** {@return the customer name} */
    String customerName() { return customerName; }
    /** {@return the driver name} */
    String driverName() { return driverName; }
    /** {@return the id of the branch the stock came from} */
    long fromBranchId() { return fromBranchId; }
    /** {@return the destination} */
    String destination() { return destination; }
    /** {@return the start time} */
    Instant startedAt() { return startedAt; }
    /** {@return the arrival time, or null} */
    Instant completedAt() { return completedAt; }
    /** {@return the total price} */
    BigDecimal total() { return total; }
    /** {@return the payment method} */
    PaymentMethod paymentMethod() { return paymentMethod; }
    /** {@return the cancellation reason, or null} */
    String cancelReason() { return cancelReason; }
    /** {@return the return reason, or null} */
    String returnReason() { return returnReason; }
    /** {@return the package lines} */
    List<DeliveryLine> lines() { return lines; }
    /** {@return the id of the creating user} */
    long createdBy() { return createdBy; }
    /** {@return the creation time} */
    Instant createdAt() { return createdAt; }
}
