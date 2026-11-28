package kyle.poultry.catalog.api.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Public representation of a sales package.
 *
 * @param id     the package id
 * @param sku    the unique stock-keeping code
 * @param name   the unique display name
 * @param price  the selling price at 2 decimals
 * @param active whether the package may still be sold
 * @param items  the package contents, fixed since creation
 */
public record PackageView(long id, String sku, String name, BigDecimal price, boolean active, List<PackageItemView> items) {}
