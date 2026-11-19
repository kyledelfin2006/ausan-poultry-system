package kyle.poultry.stock;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Read model of the current quantity and average cost. Written only through {@link StockBalanceRepository#applyDelta}. */
@Entity
@Table(name = "stock_balance")
class StockBalance {
    @EmbeddedId private StockBalanceId id;
    private long quantity;
    private BigDecimal avgUnitCost;

    protected StockBalance() {}

    StockBalanceId id() { return id; }
    long quantity() { return quantity; }
    BigDecimal avgUnitCost() { return avgUnitCost; }
}
