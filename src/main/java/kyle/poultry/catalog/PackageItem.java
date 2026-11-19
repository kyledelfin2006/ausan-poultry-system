package kyle.poultry.catalog;

import jakarta.persistence.Embeddable;

/** One line of a package's contents: this many units of this product. */
@Embeddable
record PackageItem(long productId, long quantity) {}
