package kyle.poultry.stock.api.model;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Public representation of the current stock of one product at one branch.
 *
 * @param productId   the product
 * @param productName the product's name
 * @param unit        the product's unit of measure
 * @param branchId    the branch
 * @param quantity    units on hand
 * @param averageCost weighted average cost per unit at 4 decimals; omitted from assistant responses
 */
public record BalanceView(long productId, String productName, String unit, long branchId, long quantity,
                          @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal averageCost) {}
