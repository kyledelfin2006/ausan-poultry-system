package kyle.poultry.stock;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kyle.poultry.identity.Actor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Public face of the stock module. All callers must join the caller's transaction (they do, by default). */
public interface StockApi {

    /** Manual movement: opening, purchase, donation, usage or adjustment. */
    MovementView record(StockCommand cmd, Actor actor);

    /**
     * Delivery stock-out for all lines, all-or-nothing within the caller's transaction.
     * @throws InsufficientStockException if any line exceeds the branch balance
     */
    List<MovementView> deduct(List<StockLine> lines, long branchId, SourceRef source, LocalDate on, Actor actor);

    /** Restores every not-yet-reversed DELIVERY_OUT for the source. Safe to call twice. */
    List<MovementView> reverseAllFor(SourceRef source, String reason, LocalDate on, Actor actor);

    long balance(long productId, long branchId);

    List<BalanceView> balances(Long branchId);

    Page<MovementView> history(long productId, long branchId, Pageable page);

    record StockCommand(long productId, long branchId, MovementType type, long quantityDelta,
                        BigDecimal unitCost, String reason, LocalDate occurredOn) {}

    record StockLine(long productId, long quantity) {}

    record SourceRef(String type, long id) {}

    record MovementView(long id, long productId, long branchId, MovementType type, long quantityDelta,
                        BigDecimal unitCost, String reason, LocalDate occurredOn,
                        String sourceType, Long sourceId, Long reversesId, long createdBy, Instant createdAt) {}

    record BalanceView(long productId, String productName, String unit, long branchId, long quantity) {}
}
