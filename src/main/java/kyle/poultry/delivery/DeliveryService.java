package kyle.poultry.delivery;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates a delivery across stock and finance. Every public method is one transaction, so a delivery,
 * its stock movements and its sale/cost/payment entries are created, cancelled or refunded together or not at all.
 * Package-private; used by {@link DeliveryController} and this module's tests.
 */
@Service
@Transactional
class DeliveryService {
    /** Marks stock and finance entries as belonging to a delivery, so they can be found and reversed later. */
    private static final String SOURCE_TYPE = "DELIVERY";

    private final DeliveryRepository deliveries;
    private final CatalogApi catalog;
    private final StockApi stock;
    private final FinanceApi finance;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    /**
     * Creates the service.
     *
     * @param deliveries delivery persistence
     * @param catalog    package and branch lookups
     * @param stock      stock deduction and reversal
     * @param finance    sale, cost and payment ledger
     * @param clock      source of "now" and of the farm's time zone
     * @param jdbc       used for the idempotency table and its advisory lock
     */
    DeliveryService(DeliveryRepository deliveries, CatalogApi catalog, StockApi stock, FinanceApi finance, Clock clock,
                    JdbcTemplate jdbc) {
        this.deliveries = deliveries; this.catalog = catalog; this.stock = stock; this.finance = finance; this.clock = clock;
        this.jdbc = jdbc;
    }

    /**
     * One package line of a new delivery.
     *
     * @param packageId the sales package being sold
     * @param quantity  how many packages; must be positive
     * @param unitPrice price override for this delivery only, or null to use the package price
     */
    record LineRequest(long packageId, long quantity, BigDecimal unitPrice) {}

    /**
     * Everything needed to create a delivery.
     *
     * @param customerName  who the goods are for
     * @param driverName    who delivers them
     * @param fromBranchId  branch whose stock is used
     * @param destination   where the goods go
     * @param startedAt     when the trip starts, or null for now
     * @param paymentMethod how the customer paid
     * @param lines         the packages sold; at least one
     */
    record CreateRequest(String customerName, String driverName, long fromBranchId, String destination,
                         Instant startedAt, PaymentMethod paymentMethod, List<LineRequest> lines) {}

    /**
     * Outcome of a create call.
     *
     * @param delivery the delivery (new, or the earlier one for a repeated idempotency key)
     * @param created  false when an earlier request with the same idempotency key already created {@code delivery}
     */
    record CreateResult(DeliveryView delivery, boolean created) {}

    /**
     * Creates a delivery without an idempotency key.
     *
     * @param r     the request
     * @param actor who is creating it
     * @return the new delivery
     * @throws RuleViolation when the request breaks a business rule (see {@link #create(CreateRequest, Actor, String)})
     */
    DeliveryView create(CreateRequest r, Actor actor) {
        return create(r, actor, null).delivery();
    }

    /**
     * Creates a delivery, at most once per (user, idempotency key).
     *
     * <p>With a key, the check, the delivery and the key row share this one transaction. A transaction-scoped advisory
     * lock on (key, user) makes concurrent submits of the same key run one after the other, so the second finds the
     * first's committed row instead of repeating (or failing on) the work; the primary key remains the backstop.
     *
     * @param r              the request
     * @param actor          who is creating it
     * @param idempotencyKey client-chosen key (1-100 chars) or null for no idempotency
     * @return the delivery and whether it was newly created
     * @throws RuleViolation when the key was already used with a different request, or when the request is invalid
     *                       (missing names, no lines, duplicate package, non-positive quantity or price, future start)
     * @throws NotFoundException when a package or branch does not exist
     */
    CreateResult create(CreateRequest r, Actor actor, String idempotencyKey) {
        if (idempotencyKey != null) {
            // Serialise same-key requests: the lock is released automatically when this transaction ends.
            jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                    actor.userId() + ":" + idempotencyKey);
            var existing = jdbc.query("select delivery_id from delivery_idempotency where idempotency_key = ? and user_id = ?",
                    (rs, i) -> rs.getLong(1), idempotencyKey, actor.userId());
            if (!existing.isEmpty()) {
                // Repeat: create nothing, but refuse if the body differs (the key would be hiding a different order).
                var d = deliveries.findById(existing.getFirst())
                        .orElseThrow(() -> new NotFoundException("Delivery", existing.getFirst()));
                if (!sameRequest(d, r)) throw new RuleViolation("Idempotency-Key was already used with a different request");
                return new CreateResult(view(d), false);
            }
        }
        var view = createNew(r, actor);
        if (idempotencyKey != null) {
            // Same transaction as the delivery: either both exist or neither does.
            jdbc.update("insert into delivery_idempotency (idempotency_key, user_id, delivery_id, created_at) values (?, ?, ?, ?)",
                    idempotencyKey, actor.userId(), view.id(), java.sql.Timestamp.from(clock.instant()));
        }
        return new CreateResult(view, true);
    }

    /**
     * Compares what the client controls with an existing delivery; a missing price or start time in the repeat
     * means "default", so it matches.
     *
     * @param d the delivery created by the first request
     * @param r the repeated request
     * @return true when the repeat describes the same delivery
     */
    private static boolean sameRequest(Delivery d, CreateRequest r) {
        if (r.lines() == null || r.lines().size() != d.lines().size()) return false;
        if (!strip(r.customerName()).equals(d.customerName()) || !strip(r.driverName()).equals(d.driverName())
                || !strip(r.destination()).equals(d.destination()) || r.fromBranchId() != d.fromBranchId()
                || r.paymentMethod() != d.paymentMethod()) return false;
        // Stored times were truncated to microseconds, so compare at that precision.
        if (r.startedAt() != null && !r.startedAt().truncatedTo(ChronoUnit.MICROS).equals(d.startedAt())) return false;
        var stored = new HashMap<Long, DeliveryLine>();
        d.lines().forEach(l -> stored.put(l.packageId(), l));
        for (LineRequest l : r.lines()) {
            var s = stored.get(l.packageId());
            if (s == null || s.quantity() != l.quantity()) return false;
            if (l.unitPrice() != null && l.unitPrice().compareTo(s.unitPrice()) != 0) return false;
        }
        return true;
    }

    /**
     * Trims a text, treating null as empty.
     *
     * @param s the text or null
     * @return the trimmed text
     */
    private static String strip(String s) { return s == null ? "" : s.strip(); }

    /**
     * Saves the delivery as ACTIVE, deducts the packages' contents from the branch, records sale, cost and full
     * payment. Runs inside the caller's transaction, so a failure anywhere undoes all of it.
     *
     * @param r     the request
     * @param actor who is creating it
     * @return the new delivery
     * @throws RuleViolation     for a missing name, destination or payment method, no lines, a duplicate package, a
     *                           non-positive quantity or price, a price with more than 2 decimals, or a future start
     * @throws NotFoundException when the branch or a package is missing or inactive
     */
    private DeliveryView createNew(CreateRequest r, Actor actor) {
        // Validate the request before touching stock or money.
        requireText(r.customerName(), "Customer name");
        requireText(r.driverName(), "Driver name");
        requireText(r.destination(), "Destination");
        if (r.paymentMethod() == null) throw new RuleViolation("Payment method is required");
        if (r.lines() == null || r.lines().isEmpty()) throw new RuleViolation("A delivery needs at least one package");
        catalog.requireActiveBranch(r.fromBranchId());

        // Postgres timestamps keep microseconds; truncating here makes the returned and the stored values identical.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
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
            // Expand the package into its products; TreeMap keeps stock locking in a stable order.
            pkg.items().forEach(i -> productQuantities.merge(i.productId(), i.quantity() * l.quantity(), Long::sum));
        }

        // Human-readable code from a database sequence, so concurrent deliveries never collide.
        String code = "DLV-%d-%04d".formatted(now.atZone(clock.getZone()).getYear(), deliveries.nextCodeNumber());
        var delivery = deliveries.save(Delivery.open(code, r.customerName().strip(), r.driverName().strip(), r.fromBranchId(),
                r.destination().strip(), startedAt, r.paymentMethod(), lines, actor.userId(), now));

        var source = new SourceRef(SOURCE_TYPE, delivery.id());
        LocalDate on = startedAt.atZone(clock.getZone()).toLocalDate();

        // Stock first: if there is not enough, this throws and the whole transaction (delivery included) rolls back.
        var movements = stock.deduct(productQuantities.entrySet().stream()
                .map(e -> new StockLine(e.getKey(), e.getValue())).toList(), r.fromBranchId(), source, on, actor);
        BigDecimal costOfGoods = movements.stream().map(MovementView::totalCost).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Money: revenue, the cost of what left the shelf (at weighted average cost), and the payment received.
        finance.recordSale(delivery.total(), on, source, actor);
        finance.recordCostOfGoods(costOfGoods, on, source, actor);
        finance.recordPayment(delivery.total(), r.paymentMethod(), on, source, actor);
        return view(delivery);
    }

    /**
     * Records arrival. No stock or financial effect.
     *
     * @param id          the delivery
     * @param completedAt when it arrived
     * @param actor       who records it
     * @return the updated delivery
     * @throws NotFoundException   when the delivery does not exist
     * @throws kyle.poultry.shared.ConflictException when the delivery is no longer ACTIVE
     * @throws RuleViolation       when the time is missing, in the future, or before the start
     */
    DeliveryView markDone(long id, Instant completedAt, Actor actor) {
        // Row lock so a concurrent "cancel" on the same delivery is decided strictly before or after this.
        var d = lock(id);
        d.markDone(completedAt, clock.instant());
        return view(d);
    }

    /**
     * Owner only. Restores stock at its original cost and reverses the sale and cost; the payment is kept until
     * refunded.
     *
     * @param id     the delivery
     * @param reason why it is cancelled
     * @param actor  must be an owner
     * @return the cancelled delivery
     * @throws AccessDeniedException when the actor is not an owner
     * @throws NotFoundException     when the delivery does not exist
     * @throws kyle.poultry.shared.ConflictException when the delivery is no longer ACTIVE
     * @throws RuleViolation         when no reason is given
     */
    DeliveryView cancel(long id, String reason, Actor actor) {
        if (!actor.isOwner()) throw new AccessDeniedException("Only the owner can cancel a delivery");
        var d = lock(id);
        d.cancel(reason, clock.instant());
        var source = new SourceRef(SOURCE_TYPE, d.id());
        LocalDate on = LocalDate.now(clock);
        // Originals are kept; reversal entries are added so the ledger stays an append-only history.
        stock.reverseAllFor(source, "Delivery " + d.code() + " cancelled", on, actor);
        finance.reverseSaleAndCost(source, on, actor);
        return view(d);
    }

    /**
     * Owner only. Money actually handed back for a cancelled delivery, never more than was paid.
     *
     * @param id          the delivery
     * @param amount      amount refunded
     * @param method      how it was refunded
     * @param description note for the ledger; defaults to "Refund for CODE" when blank
     * @param actor       must be an owner
     * @return the delivery with its remaining retained payment
     * @throws AccessDeniedException when the actor is not an owner
     * @throws NotFoundException     when the delivery does not exist
     * @throws RuleViolation         when the delivery is not CANCELLED or the amount exceeds what is still retained
     */
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

    /**
     * Reads one delivery.
     *
     * @param id the delivery
     * @return its view
     * @throws NotFoundException when it does not exist
     */
    @Transactional(readOnly = true)
    DeliveryView get(long id) {
        return view(deliveries.findById(id).orElseThrow(() -> new NotFoundException("Delivery", id)));
    }

    /**
     * Lists deliveries, newest first.
     *
     * @param status only this status, or null for all
     * @param page   page and size
     * @return one page of deliveries
     */
    @Transactional(readOnly = true)
    Page<DeliveryView> list(DeliveryStatus status, Pageable page) {
        var result = status == null ? deliveries.findAllByOrderByStartedAtDescIdDesc(page)
                : deliveries.findByStatusOrderByStartedAtDescIdDesc(status, page);
        return result.map(this::view);
    }

    /**
     * Loads a delivery with a row lock held until the transaction ends.
     *
     * @param id the delivery
     * @return the locked entity
     * @throws NotFoundException when it does not exist
     */
    private Delivery lock(long id) {
        return deliveries.lockById(id).orElseThrow(() -> new NotFoundException("Delivery", id));
    }

    /**
     * Maps a delivery to its API view, adding the retained payment for cancelled deliveries.
     *
     * @param d the entity
     * @return the view
     */
    private DeliveryView view(Delivery d) {
        BigDecimal retained = d.status() == DeliveryStatus.CANCELLED
                ? finance.retainedPayment(new SourceRef(SOURCE_TYPE, d.id())) : null;
        return new DeliveryView(d.id(), d.code(), d.status(), d.customerName(), d.driverName(), d.fromBranchId(),
                d.destination(), d.startedAt(), d.completedAt(), d.total(), d.paymentMethod(), d.cancelReason(),
                retained, List.copyOf(d.lines()), d.createdBy(), d.createdAt());
    }

    /**
     * Requires a non-blank text field.
     *
     * @param value the text
     * @param field its name for the error message
     * @throws RuleViolation when the text is null or blank
     */
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new RuleViolation(field + " is required");
    }
}
