package kyle.poultry.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.identity.Actor;
import kyle.poultry.shared.SourceRef;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Public face of the stock module. All callers must join the caller's transaction (they do, by default). */
public interface StockApi {

    /**
     * Manual movement: opening, purchase, donation, usage or adjustment.
     * A PURCHASE also records the cash paid in the finance ledger, in the same transaction.
     */
    MovementView record(StockCommand cmd, Actor actor);

    /**
     * Delivery stock-out for all lines, all-or-nothing within the caller's transaction.
     * Each returned movement carries the average cost at that moment ({@link MovementView#totalCost()} is the cost of goods).
     * @throws InsufficientStockException if any line exceeds the branch balance
     */
    List<MovementView> deduct(List<StockLine> lines, long branchId, SourceRef source, LocalDate on, Actor actor);

    /** Restores every not-yet-reversed DELIVERY_OUT for the source at its original cost. Safe to call twice. */
    List<MovementView> reverseAllFor(SourceRef source, String reason, LocalDate on, Actor actor);

    long balance(long productId, long branchId);

    /** Weighted average cost per unit (PHP, 4 decimals); zero when the branch has never held the product. */
    BigDecimal averageCost(long productId, long branchId);

    List<BalanceView> balances(Long branchId);

    Page<MovementView> history(long productId, long branchId, Pageable page);

    /**
     * @param unitCost      required for PURCHASE; OPENING and DONATION default to zero
     * @param paymentMethod required for PURCHASE (how the supplier was paid)
     */
    record StockCommand(long productId, long branchId, MovementType type, long quantityDelta,
                        BigDecimal unitCost, String reason, LocalDate occurredOn, PaymentMethod paymentMethod) {}

    record StockLine(long productId, long quantity) {}

    record MovementView(long id, long productId, long branchId, MovementType type, long quantityDelta,
                        BigDecimal unitCost, String reason, LocalDate occurredOn,
                        String sourceType, Long sourceId, Long reversesId, long createdBy, Instant createdAt) {

        /** |quantity| x unit cost, rounded to centavos. */
        public BigDecimal totalCost() {
            if (unitCost == null) return BigDecimal.ZERO.setScale(2);
            return unitCost.multiply(BigDecimal.valueOf(Math.abs(quantityDelta))).setScale(2, RoundingMode.HALF_UP);
        }
    }

    record BalanceView(long productId, String productName, String unit, long branchId, long quantity, BigDecimal averageCost) {}
}
