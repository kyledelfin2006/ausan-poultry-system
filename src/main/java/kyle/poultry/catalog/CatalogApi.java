package kyle.poultry.catalog;

import java.math.BigDecimal;
import java.util.List;

/** Public face of the catalog module: reference data other modules may depend on. */
public interface CatalogApi {

    /** @throws kyle.poultry.shared.NotFoundException if missing; @throws kyle.poultry.shared.RuleViolation if inactive */
    ProductView requireActiveProduct(long id);

    BranchView requireActiveBranch(long id);

    /** The sellable package with its contents, same failure modes as above. */
    PackageView requireActivePackage(long id);

    List<ProductView> products();

    List<PackageView> packages();

    record ProductView(long id, String name, String unit, BigDecimal defaultPrice, boolean active) {}

    record BranchView(long id, String name, boolean active) {}

    record PackageView(long id, String sku, String name, BigDecimal price, boolean active, List<PackageItemView> items) {}

    record PackageItemView(long productId, String productName, long quantity) {}
}
