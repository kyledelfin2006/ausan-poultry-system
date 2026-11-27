package kyle.poultry.catalog.api.model;

/**
 * One line of a package's contents.
 *
 * @param productId   the id of the contained product
 * @param productName the product's current name
 * @param quantity    units of that product in one package
 */
public record PackageItemView(long productId, String productName, long quantity) {}
