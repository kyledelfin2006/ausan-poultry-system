package kyle.poultry.stock.internal.persistence;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

/**
 * Composite primary key of {@link StockBalance}: one balance per product at each branch.
 * <p>
 * It is {@link Serializable} because JPA requires composite identifiers to be. Used only inside the
 * stock module.
 *
 * @param productId the product the balance is for
 * @param branchId  the branch that holds it
 */
@Embeddable
public record StockBalanceId(long productId, long branchId) implements Serializable {}
