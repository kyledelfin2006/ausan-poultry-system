package kyle.poultry.delivery;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import kyle.poultry.catalog.CatalogApi;
import kyle.poultry.finance.FinanceApi;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.identity.Actor;
import kyle.poultry.shared.NotFoundException;
import kyle.poultry.shared.RuleViolation;
import kyle.poultry.shared.SourceRef;
import kyle.poultry.stock.StockApi;
import kyle.poultry.stock.StockApi.MovementView;
import kyle.poultry.stock.StockApi.StockLine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates a delivery across stock and finance. Every public method is one transaction, so a delivery,
 * its stock movements and its sale/cost/payment entries are created, cancelled or refunded together or not at all.
 */
@Service
@Transactional
class DeliveryService {
    private static final String SOURCE_TYPE = "DELIVERY";

    private final DeliveryRepository deliveries;
    private final CatalogApi catalog;
    private final StockApi stock;
    private final FinanceApi finance;
    private final Clock clock;

    DeliveryService(DeliveryRepository deliveries, CatalogApi catalog, StockApi stock, FinanceApi finance, Clock clock) {
        this.deliveries = deliveries; this.catalog = catalog; this.stock = stock; this.finance = finance; this.clock = clock;
    }

    record LineRequest(long packageId, long quantity, BigDecimal unitPrice) {}

    record CreateRequest(String customerName, String driverName, long fromBranchId, String destination,
                         Instant startedAt, PaymentMethod paymentMethod, List<LineRequest> lines) {}

    /** Saves the delivery as ACTIVE, deducts the packages' contents from the branch, records sale, cost and full payment. */
    DeliveryView create(CreateRequest r, Actor actor) {
        requireText(r.customerName(), "Customer name");
        requireText(r.driverName(), "Driver name");
        requireText(r.destination(), "Destination");
        if (r.paymentMethod() == null) throw new RuleViolation("Payment method is required");
        if (r.lines() == null || r.lines().isEmpty()) throw new RuleViolation("A delivery needs at least one package");
        catalog.requireActiveBranch(r.fromBranchId());

        Instant now = clock.instant();
        Instant startedAt = r.startedAt() == null ? now : r.startedAt();
        if (startedAt.isAfter(now)) throw new RuleViolation("Start time cannot be in the future");

        var lines = new ArrayList<DeliveryLine>();
        Map<Long, Long> productQuantities = new TreeMap<>();   // product id -> units to take out of the branch
        var seenPackages = new HashSet<Long>();
        for (LineRequest l : r.lines()) {
            if (l.quantity() <= 0) throw new RuleViolation("Package quantity must be positive");
            if (!seenPackages.add(l.packageId())) throw new RuleViolation("Package " + l.packageId() + " is listed twice");
            var pkg = catalog.requireActivePackage(l.packageId());

            // The package price is the default; staff may override it for this delivery only.
            BigDecimal unitPrice = l.unitPrice() != null ? l.unitPrice() : pkg.price();
            if (unitPrice.signum() <= 0) throw new RuleViolation("Price must be greater than zero");
            if (unitPrice.stripTrailingZeros().scale() > 2) throw new RuleViolation("Price cannot have more than 2 decimals");
            unitPrice = unitPrice.setScale(2);

            lines.add(new DeliveryLine(pkg.id(), pkg.name(), l.quantity(), unitPrice,
                    unitPrice.multiply(BigDecimal.valueOf(l.quantity()))));
            pkg.items().forEach(i -> productQuantities.merge(i.productId(), i.quantity() * l.quantity(), Long::sum));
        }

        String code = "DLV-%d-%04d".formatted(now.atZone(clock.getZone()).getYear(), deliveries.nextCodeNumber());
        var delivery = deliveries.save(Delivery.open(code, r.customerName().strip(), r.driverName().strip(), r.fromBranchId(),
                r.destination().strip(), startedAt, r.paymentMethod(), lines, actor.userId(), now));

        var source = new SourceRef(SOURCE_TYPE, delivery.id());
        LocalDate on = startedAt.atZone(clock.getZone()).toLocalDate();

        var movements = stock.deduct(productQuantities.entrySet().stream()
                .map(e -> new StockLine(e.getKey(), e.getValue())).toList(), r.fromBranchId(), source, on, actor);
        BigDecimal costOfGoods = movements.stream().map(MovementView::totalCost).reduce(BigDecimal.ZERO, BigDecimal::add);

        finance.recordSale(delivery.total(), on, source, actor);
        finance.recordCostOfGoods(costOfGoods, on, source, actor);
        finance.recordPayment(delivery.total(), r.paymentMethod(), on, source, actor);
        return view(delivery);
    }

    /** Records arrival. No stock or financial effect. */
    DeliveryView markDone(long id, Instant completedAt, Actor actor) {
        var d = lock(id);
        d.markDone(completedAt, clock.instant());
        return view(d);
    }

    /** Owner only. Restores stock at its original cost and reverses the sale and cost; the payment is kept until refunded. */
    DeliveryView cancel(long id, String reason, Actor actor) {
        if (!actor.isOwner()) throw new AccessDeniedException("Only the owner can cancel a delivery");
        var d = lock(id);
        d.cancel(reason, clock.instant());
        var source = new SourceRef(SOURCE_TYPE, d.id());
        LocalDate on = LocalDate.now(clock);
        stock.reverseAllFor(source, "Delivery " + d.code() + " cancelled", on, actor);
        finance.reverseSaleAndCost(source, on, actor);
        return view(d);
    }

    /** Owner only. Money actually handed back for a cancelled delivery, never more than was paid. */
    DeliveryView refund(long id, BigDecimal amount, PaymentMethod method, String description, Actor actor) {
        if (!actor.isOwner()) throw new AccessDeniedException("Only the owner can record a refund");
        var d = lock(id);
        if (d.status() != DeliveryStatus.CANCELLED)
            throw new RuleViolation("Only a cancelled delivery can be refunded; " + d.code() + " is " + d.status());
        finance.recordRefund(amount, method, LocalDate.now(clock),
                description == null || description.isBlank() ? "Refund for " + d.code() : description,
                new SourceRef(SOURCE_TYPE, d.id()), actor);
        return view(d);
    }

    @Transactional(readOnly = true)
    DeliveryView get(long id) {
        return view(deliveries.findById(id).orElseThrow(() -> new NotFoundException("Delivery", id)));
    }

    @Transactional(readOnly = true)
    Page<DeliveryView> list(DeliveryStatus status, Pageable page) {
        var result = status == null ? deliveries.findAllByOrderByStartedAtDescIdDesc(page)
                : deliveries.findByStatusOrderByStartedAtDescIdDesc(status, page);
        return result.map(this::view);
    }

    private Delivery lock(long id) {
        return deliveries.lockById(id).orElseThrow(() -> new NotFoundException("Delivery", id));
    }

    private DeliveryView view(Delivery d) {
        BigDecimal retained = d.status() == DeliveryStatus.CANCELLED
                ? finance.retainedPayment(new SourceRef(SOURCE_TYPE, d.id())) : null;
        return new DeliveryView(d.id(), d.code(), d.status(), d.customerName(), d.driverName(), d.fromBranchId(),
                d.destination(), d.startedAt(), d.completedAt(), d.total(), d.paymentMethod(), d.cancelReason(),
                retained, List.copyOf(d.lines()), d.createdBy(), d.createdAt());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new RuleViolation(field + " is required");
    }
}
