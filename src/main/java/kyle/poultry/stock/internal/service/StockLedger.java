package kyle.poultry.stock.internal.service;

import kyle.poultry.stock.api.model.BalanceView;
import kyle.poultry.stock.api.model.MovementView;
import kyle.poultry.stock.api.model.ProductStockView;
import kyle.poultry.stock.api.model.ReorderLevelView;
import kyle.poultry.stock.api.model.StockCommand;
import kyle.poultry.stock.api.model.StockLine;
import kyle.poultry.stock.api.StockApi;
import kyle.poultry.stock.api.exception.InsufficientStockException;
import kyle.poultry.stock.api.model.MovementType;
import kyle.poultry.stock.internal.persistence.StockBalance;
import kyle.poultry.stock.internal.persistence.StockBalanceId;
import kyle.poultry.stock.internal.persistence.StockBalanceRepository;
import kyle.poultry.stock.internal.persistence.StockMovement;
import kyle.poultry.stock.internal.persistence.StockMovementRepository;
import static java.util.Comparator.comparingLong;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kyle.poultry.catalog.api.CatalogApi;
import kyle.poultry.catalog.api.model.ProductView;
import kyle.poultry.finance.api.FinanceApi;
import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.identity.api.model.Capability;
import kyle.poultry.shared.time.BusinessDatePolicy;
import kyle.poultry.shared.error.RuleViolation;
import kyle.poultry.shared.reference.SourceRef;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link StockApi}: the append-only stock ledger plus the per-branch balance and average cost.
 * <p>
 * Every change is a new {@link StockMovement} row together with one atomic balance update, so quantity and
 * cost always agree with the ledger. The class is {@code @Transactional}: each public method joins the
 * caller's transaction or starts its own, and the read methods are marked read-only. It calls the catalog to
 * validate products and branches and the finance module to record the cash for purchases.
 */
@Service
@Transactional
class StockLedger implements StockApi {
    private final StockMovementRepository movements;
    private final StockBalanceRepository balances;
    private final CatalogApi catalog;
    private final FinanceApi finance;
    private final Clock clock;
    private final StockOverviewService overview;

    /**
     * Creates the ledger.
     *
     * @param movements the movement ledger repository
     * @param balances  the balance repository
     * @param catalog   the catalog module, used to validate products and branches
     * @param finance   the finance module, used to record the cash paid for purchases
     * @param clock     the application clock, used for "today" and timestamps
     * @param overview  the read model behind the all-branch summary and the reorder levels
     */
    StockLedger(StockMovementRepository movements, StockBalanceRepository balances, CatalogApi catalog,
                FinanceApi finance, Clock clock, StockOverviewService overview) {
        this.movements = movements; this.balances = balances; this.catalog = catalog;
        this.finance = finance; this.clock = clock; this.overview = overview;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Order of work: check the type, the owner-only rule, the date and the payment method; apply the movement;
     * then, for a purchase, record the cash in the finance ledger.
     */
    @Override
    public MovementView record(StockCommand cmd, Actor actor) {
        // Delivery types are produced by the delivery flow only, never typed in by a person.
        if (cmd.type() == null || !MovementType.MANUAL.contains(cmd.type()))
            throw new RuleViolation("Movement type " + cmd.type() + " cannot be entered manually");
        // Opening stock and adjustments create quantities without a physical event, so only the owner may.
        if (MovementType.OWNER_ONLY.contains(cmd.type())) actor.require(Capability.ADJUST_STOCK);
        BusinessDatePolicy.requireAllowed(cmd.occurredOn(), actor.can(Capability.BACKDATE_FREELY), clock);
        // Checked before applying so a purchase never changes stock without a way to record its payment.
        if (cmd.type() == MovementType.PURCHASE && cmd.paymentMethod() == null)
            throw new RuleViolation("A purchase requires the payment method");

        MovementView saved = apply(cmd, null, actor);

        if (cmd.type() == MovementType.PURCHASE) {
            // Same transaction: the stock and the cash it cost are recorded together or not at all.
            ProductView product = catalog.requireActiveProduct(cmd.productId());
            // The finance entry points back to this movement through a loose source reference.
            finance.recordStockPurchase(saved.totalCost(), cmd.paymentMethod(), cmd.occurredOn(),
                    "Purchase of " + saved.quantityDelta() + " " + product.name(),
                    new SourceRef("STOCK_MOVEMENT", saved.id()), actor);
        }
        return saved;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Each line is applied as its own atomic balance update; atomicity across lines comes from the surrounding
     * transaction, which rolls back when any line throws.
     */
    @Override
    public List<MovementView> deduct(List<StockLine> lines, long branchId, SourceRef source, LocalDate on, Actor actor) {
        BusinessDatePolicy.requireAllowed(on, actor.can(Capability.BACKDATE_FREELY), clock);
        // Fixed lock order (ascending product id) so two concurrent deliveries cannot deadlock each other.
        return lines.stream()
                .sorted(comparingLong(StockLine::productId))
                .map(l -> {
                    // Zero or negative would turn a stock-out into an addition (or nothing), so refuse it.
                    if (l.quantity() <= 0) throw new RuleViolation("Quantity must be positive");
                    // Cost is left null on purpose: apply() stamps the average cost at this moment (cost of goods).
                    return apply(new StockCommand(l.productId(), branchId, MovementType.DELIVERY_OUT,
                            -l.quantity(), null, null, on, null), source, actor);
                })
                .toList();
    }

    /**
     * {@inheritDoc}
     * <p>
     * The original stock-out rows are never changed: each reversal is a new row that points at the original.
     * The database also holds a unique constraint on the reversed movement, so a reversal cannot be duplicated
     * even by two concurrent calls.
     */
    @Override
    public List<MovementView> reverseAllFor(SourceRef source, String reason, LocalDate on, Actor actor) {
        BusinessDatePolicy.requireAllowed(on, actor.can(Capability.BACKDATE_FREELY), clock);
        return movements.findBySourceTypeAndSourceIdAndType(source.type(), source.id(), MovementType.DELIVERY_OUT).stream()
                // Skip stock-outs that already have a reversal: this is what makes a second call a no-op.
                .filter(m -> !movements.existsByReversesId(m.id()))
                // Same fixed product order as deduct, to keep lock order consistent between callers.
                .sorted(comparingLong(StockMovement::productId))
                .map(m -> {
                    var reversal = m.reversal(reason, on, actor, clock.instant());
                    balances.ensureRow(m.productId(), m.branchId());
                    // Positive delta cannot be refused; the goods return at the cost they left with, so the
                    // average moves back toward its value at the time of the stock-out (equal if nothing else changed).
                    balances.applyDelta(m.productId(), m.branchId(), reversal.quantityDelta(), reversal.unitCost());
                    return movements.save(reversal).view();
                })
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public long balance(long productId, long branchId) {
        // No balance row yet means the branch has never held the product, which is the same as zero.
        return balances.findById(new StockBalanceId(productId, branchId)).map(StockBalance::quantity).orElse(0L);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public BigDecimal averageCost(long productId, long branchId) {
        BigDecimal avg = balances.averageCost(productId, branchId);
        // Never held the product: report zero (at the usual 4 decimals) instead of null.
        return avg == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP) : avg;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<BalanceView> balances(Long branchId) {
        // One catalog call gives names and units for every balance, instead of one lookup per row.
        Map<Long, ProductView> products = catalog.products().stream().collect(Collectors.toMap(ProductView::id, Function.identity()));
        var rows = branchId == null ? balances.findAll() : balances.findByIdBranchIdOrderByIdProductId(branchId);
        // Sort in memory by branch then product so the all-branches listing has a stable order too.
        return rows.stream()
                .sorted(comparingLong((StockBalance b) -> b.id().branchId()).thenComparingLong(b -> b.id().productId()))
                .map(b -> {
                    var p = products.get(b.id().productId());
                    return new BalanceView(b.id().productId(), p.name(), p.unit(), b.id().branchId(), b.quantity(), b.avgUnitCost());
                })
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Page<MovementView> history(long productId, long branchId, Pageable page) {
        return movements.history(productId, branchId, page).map(StockMovement::view);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<MovementView> movementsFor(SourceRef source) {
        return movements.findBySourceTypeAndSourceIdOrderByIdAsc(source.type(), source.id()).stream()
                .map(StockMovement::view).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<ProductStockView> summary(boolean lowOnly) { return overview.summary(lowOnly); }

    /** {@inheritDoc} */
    @Override
    public List<ReorderLevelView> reorderLevels() { return overview.reorderLevels(); }

    /** {@inheritDoc} */
    @Override
    public ReorderLevelView setReorderLevel(long productId, long level, Actor actor) {
        return overview.setReorderLevel(productId, level, actor);
    }

    /** {@inheritDoc} */
    @Override
    public void clearReorderLevel(long productId, Actor actor) { overview.clearReorderLevel(productId, actor); }

    /**
     * Validates, applies the delta atomically (never below zero), then appends the ledger row with its cost.
     * This is the single path through which all movements are written.
     *
     * @param c      the movement to apply
     * @param source the record that caused it, or {@code null} for manual movements
     * @param actor  the user recording it
     * @return the saved movement
     * @throws kyle.poultry.shared.error.NotFoundException if the product or branch does not exist
     * @throws RuleViolation if the product or branch is inactive, or a movement rule is broken
     * @throws InsufficientStockException if the delta would take the balance below zero
     */
    private MovementView apply(StockCommand c, SourceRef source, Actor actor) {
        // Both must exist and be active; this also fails fast before any stock is touched.
        catalog.requireActiveProduct(c.productId());
        catalog.requireActiveBranch(c.branchId());
        var movement = StockMovement.create(c, source, actor, clock.instant());   // rules fail before any write
        // Guarantee a row exists so the next statement has a row to lock and update.
        balances.ensureRow(c.productId(), c.branchId());
        // One atomic statement checks "not below zero" and updates quantity and average; 0 rows means refused.
        if (balances.applyDelta(c.productId(), c.branchId(), c.quantityDelta(), movement.unitCost()) == 0)
            throw new InsufficientStockException(c.productId(), c.branchId());
        // Stock-outs, usage and adjustments carry no cost of their own; they take the balance's average cost.
        movement.stampCostIfMissing(balances.averageCost(c.productId(), c.branchId()));
        // Append-only: the ledger row is inserted last and never updated afterwards.
        return movements.save(movement).view();
    }
}
