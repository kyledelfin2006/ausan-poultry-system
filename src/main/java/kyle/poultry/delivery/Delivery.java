package kyle.poultry.delivery;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.shared.ConflictException;
import kyle.poultry.shared.RuleViolation;

/** A paid delivery. Owns its status transitions; stock and money effects are orchestrated by {@link DeliveryService}. */
@Entity
@Table(name = "delivery")
class Delivery {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String code;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private DeliveryStatus status;
    private String customerName;
    private String driverName;
    private long fromBranchId;
    private String destination;
    private Instant startedAt;
    private Instant completedAt;
    private BigDecimal total;
    @Enumerated(EnumType.STRING) private PaymentMethod paymentMethod;
    private String cancelReason;
    private Instant cancelledAt;
    private long createdBy;
    private Instant createdAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "delivery_line", joinColumns = @JoinColumn(name = "delivery_id"))
    private List<DeliveryLine> lines = new ArrayList<>();

    protected Delivery() {}

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
        d.total = lines.stream().map(DeliveryLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        d.createdBy = createdBy;
        d.createdAt = now;
        return d;
    }

    void markDone(Instant completedAt, Instant now) {
        requireActive("marked as done");
        if (completedAt == null) throw new RuleViolation("Completion time is required");
        if (completedAt.isAfter(now)) throw new RuleViolation("Completion time cannot be in the future");
        if (completedAt.isBefore(startedAt)) throw new RuleViolation("Completion time cannot be before the start time");
        this.status = DeliveryStatus.DONE;
        this.completedAt = completedAt;
    }

    void cancel(String reason, Instant now) {
        requireActive("cancelled");
        if (reason == null || reason.isBlank()) throw new RuleViolation("A cancellation reason is required");
        this.status = DeliveryStatus.CANCELLED;
        this.cancelReason = reason;
        this.cancelledAt = now;
    }

    private void requireActive(String action) {
        if (status != DeliveryStatus.ACTIVE)
            throw new ConflictException("Delivery " + code + " is " + status + " and can no longer be " + action);
    }

    Long id() { return id; }
    String code() { return code; }
    DeliveryStatus status() { return status; }
    String customerName() { return customerName; }
    String driverName() { return driverName; }
    long fromBranchId() { return fromBranchId; }
    String destination() { return destination; }
    Instant startedAt() { return startedAt; }
    Instant completedAt() { return completedAt; }
    BigDecimal total() { return total; }
    PaymentMethod paymentMethod() { return paymentMethod; }
    String cancelReason() { return cancelReason; }
    List<DeliveryLine> lines() { return lines; }
    long createdBy() { return createdBy; }
    Instant createdAt() { return createdAt; }
}
