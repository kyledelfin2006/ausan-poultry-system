package kyle.poultry.stock;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Read model of the current quantity. Written only through {@link StockBalanceRepository#applyDelta}. */
@Entity
@Table(name = "stock_balance")
class StockBalance {
    @EmbeddedId private StockBalanceId id;
    private long quantity;

    protected StockBalance() {}

    StockBalanceId id() { return id; }
    long quantity() { return quantity; }
}
