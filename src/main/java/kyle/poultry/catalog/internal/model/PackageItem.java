package kyle.poultry.catalog.internal.model;

import jakarta.persistence.Embeddable;

/**
 * One line of a package's contents: this many units of this product.
 * <p>
 * It is embedded in {@code SalesPackage} and stored in the {@code package_item} table. It holds only the
 * product id, not a product object, so the package stays decoupled from later product edits.
 *
 * @param productId the id of the product contained in the package
 * @param quantity  how many units of that product one package contains (positive)
 */
@Embeddable
public record PackageItem(long productId, long quantity) {}
