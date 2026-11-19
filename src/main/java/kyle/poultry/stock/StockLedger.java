package kyle.poultry.stock;

import static java.util.Comparator.comparingLong;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kyle.poultry.catalog.CatalogApi;
import kyle.poultry.catalog.CatalogApi.ProductView;
import kyle.poultry.identity.Actor;
import kyle.poultry.shared.RuleViolation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class StockLedger implements StockApi {
    private final StockMovementRepository movements;
    private final StockBalanceRepository balances;
    private final CatalogApi catalog;
    private final Clock clock;

    StockLedger(StockMovementRepository movements, StockBalanceRepository balances, CatalogApi catalog, Clock clock) {
        this.movements = movements; this.balances = balances; this.catalog = catalog; this.clock = clock;
    }

    @Override
    public MovementView record(StockCommand cmd, Actor actor) {
        if (cmd.type() == null || !MovementType.MANUAL.contains(cmd.type()))
            throw new RuleViolation("Movement type " + cmd.type() + " cannot be entered manually");
        if (MovementType.OWNER_ONLY.contains(cmd.type()) && !actor.isOwner())
            throw new AccessDeniedException(cmd.type() + " movements are restricted to the owner");
        if (cmd.occurredOn() != null && cmd.occurredOn().isAfter(LocalDate.now(clock)))
            throw new RuleViolation("Date cannot be in the future");
        return apply(cmd, null, actor);
    }

    @Override
    public List<MovementView> deduct(List<StockLine> lines, long branchId, SourceRef source, LocalDate on, Actor actor) {
        // Fixed lock order (ascending product id) so two concurrent deliveries cannot deadlock each other.
        return lines.stream()
                .sorted(comparingLong(StockLine::productId))
                .map(l -> {
                    if (l.quantity() <= 0) throw new RuleViolation("Quantity must be positive");
                    return apply(new StockCommand(l.productId(), branchId, MovementType.DELIVERY_OUT,
                            -l.quantity(), null, null, on), source, actor);
                })
                .toList();
    }

    @Override
    public List<MovementView> reverseAllFor(SourceRef source, String reason, LocalDate on, Actor actor) {
        return movements.findBySourceTypeAndSourceIdAndType(source.type(), source.id(), MovementType.DELIVERY_OUT).stream()
                .filter(m -> !movements.existsByReversesId(m.id()))
                .sorted(comparingLong(StockMovement::productId))
                .map(m -> {
                    var reversal = m.reversal(reason, on, actor, clock.instant());
                    balances.ensureRow(m.productId(), m.branchId());
                    balances.applyDelta(m.productId(), m.branchId(), reversal.quantityDelta());   // positive: cannot be refused
                    return movements.save(reversal).view();
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long balance(long productId, long branchId) {
        return balances.findById(new StockBalanceId(productId, branchId)).map(StockBalance::quantity).orElse(0L);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BalanceView> balances(Long branchId) {
        Map<Long, ProductView> products = catalog.products().stream().collect(Collectors.toMap(ProductView::id, Function.identity()));
        var rows = branchId == null ? balances.findAll() : balances.findByIdBranchIdOrderByIdProductId(branchId);
        return rows.stream()
                .sorted(comparingLong((StockBalance b) -> b.id().branchId()).thenComparingLong(b -> b.id().productId()))
                .map(b -> {
                    var p = products.get(b.id().productId());
                    return new BalanceView(b.id().productId(), p.name(), p.unit(), b.id().branchId(), b.quantity());
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MovementView> history(long productId, long branchId, Pageable page) {
        return movements.history(productId, branchId, page).map(StockMovement::view);
    }

    /** Validates, applies the delta atomically (never below zero), then appends the ledger row. */
    private MovementView apply(StockCommand c, SourceRef source, Actor actor) {
        catalog.requireActiveProduct(c.productId());
        catalog.requireActiveBranch(c.branchId());
        var movement = StockMovement.create(c, source, actor, clock.instant());   // sign/reason rules fail before any write
        balances.ensureRow(c.productId(), c.branchId());
        if (balances.applyDelta(c.productId(), c.branchId(), c.quantityDelta()) == 0)
            throw new InsufficientStockException(c.productId(), c.branchId());
        return movements.save(movement).view();
    }
}
