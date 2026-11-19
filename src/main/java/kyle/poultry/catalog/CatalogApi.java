package kyle.poultry.catalog;

import java.math.BigDecimal;
import java.util.List;

/** Public face of the catalog module: reference data other modules may depend on. */
public interface CatalogApi {

    /** @throws kyle.poultry.shared.NotFoundException if missing; @throws kyle.poultry.shared.RuleViolation if inactive */
    ProductView requireActiveProduct(long id);

    BranchView requireActiveBranch(long id);

    List<ProductView> products();

    record ProductView(long id, String name, String unit, BigDecimal defaultPrice, boolean active) {}

    record BranchView(long id, String name, boolean active) {}
}
