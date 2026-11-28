package kyle.poultry.report.internal.model;

import java.math.BigDecimal;

/**
 * One product's stock value inside a branch.
 *
 * @param productId   the product
 * @param productName display name of the product
 * @param unit        unit of measure (e.g. head, pc, bag)
 * @param quantity    units on hand
 * @param averageCost weighted average cost per unit (4 decimals)
 * @param value       quantity x average cost, rounded to centavos
 */
public record InventoryLine(long productId, String productName, String unit, long quantity,
                            BigDecimal averageCost, BigDecimal value) {}
