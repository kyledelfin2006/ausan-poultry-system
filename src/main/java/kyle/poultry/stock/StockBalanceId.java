package kyle.poultry.stock;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

@Embeddable
record StockBalanceId(long productId, long branchId) implements Serializable {}
