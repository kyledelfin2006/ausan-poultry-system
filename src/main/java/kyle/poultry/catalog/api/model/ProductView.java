package kyle.poultry.catalog.api.model;

import java.math.BigDecimal;

/**
 * Public representation of a product.
 *
 * @param id           the product id
 * @param name         the unique product name
 * @param unit         the unit of measure, e.g. {@code "pc"}
 * @param defaultPrice the suggested selling price at 2 decimals, or {@code null} if none
 * @param active       whether the product may be used for new movements and packages
 */
public record ProductView(long id, String name, String unit, BigDecimal defaultPrice, boolean active) {}
