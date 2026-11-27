package kyle.poultry.stock.api.model;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One branch's stock of a product inside a {@link ProductStockView}.
 *
 * @param branchId    the branch
 * @param quantity    units on hand
 * @param averageCost weighted average cost per unit at 4 decimals; omitted from assistant responses
 * @param low         true when the product has a reorder level and {@code quantity} is at or below it
 */
public record BranchStockView(long branchId, long quantity,
                              @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal averageCost, boolean low) {}
