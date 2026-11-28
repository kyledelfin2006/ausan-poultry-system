package kyle.poultry.stock.internal.persistence;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Read model of the current quantity and average cost. Written only through
 * {@link StockBalanceRepository#applyDelta}.
 * <p>
 * One row per product and branch in {@code stock_balance}. The database forbids a negative quantity. The row
 * has no setters on purpose: changing it through JPA would bypass the atomic update statement that keeps the
 * quantity and weighted average correct under concurrency. Only the stock module reads it.
 */
@Entity
@Table(name = "stock_balance")
public class StockBalance {
    @EmbeddedId private StockBalanceId id;
    private long quantity;
    private BigDecimal avgUnitCost;

    /** Required by JPA to instantiate the entity when loading it; not for application use. */
    protected StockBalance() {}

    /** {@return the composite key (product and branch)} */
    public StockBalanceId id() { return id; }

    /** {@return the units currently on hand} */
    public long quantity() { return quantity; }

    /** {@return the weighted average cost per unit (4 decimals)} */
    public BigDecimal avgUnitCost() { return avgUnitCost; }
}
