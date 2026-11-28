package kyle.poultry.catalog.api;

import kyle.poultry.catalog.api.model.BranchView;
import kyle.poultry.catalog.api.model.PackageView;
import kyle.poultry.catalog.api.model.ProductView;
import java.util.List;

/**
 * Public face of the catalog module: reference data other modules may depend on.
 * <p>
 * Contract for every method: it is a plain method call that joins the caller's transaction when one is active
 * (the implementation is read-only by default, so it never writes), takes no locks and returns immutable
 * views, never entities. Failures are reported with the shared exceptions, which the web layer maps to HTTP
 * status codes: {@link kyle.poultry.shared.error.NotFoundException} (404) and
 * {@link kyle.poultry.shared.error.RuleViolation} (422).
 * <p>
 * Callers: the stock module validates products and branches here; the delivery module validates packages.
 */
public interface CatalogApi {

    /**
     * Loads a product that must exist and be active.
     *
     * @param id the product id
     * @return the product view
     * @throws kyle.poultry.shared.error.NotFoundException if no product has that id
     * @throws kyle.poultry.shared.error.RuleViolation     if the product exists but is inactive
     */
    ProductView requireActiveProduct(long id);

    /**
     * Loads a branch that must exist and be active.
     *
     * @param id the branch id
     * @return the branch view
     * @throws kyle.poultry.shared.error.NotFoundException if no branch has that id
     * @throws kyle.poultry.shared.error.RuleViolation     if the branch exists but is inactive
     */
    BranchView requireActiveBranch(long id);

    /**
     * Loads a sellable package with its contents, with the same failure modes as the other
     * {@code requireActive...} methods. Only the package's own flag is checked, not that of its products.
     *
     * @param id the package id
     * @return the package view including its items with product names
     * @throws kyle.poultry.shared.error.NotFoundException if no package has that id
     * @throws kyle.poultry.shared.error.RuleViolation     if the package exists but is inactive
     */
    PackageView requireActivePackage(long id);

    /**
     * Lists every product, active or not, ordered by id.
     *
     * @return all products
     */
    List<ProductView> products();

    /**
     * Lists every package, active or not, ordered by id, each with its contents.
     *
     * @return all packages
     */
    List<PackageView> packages();
}
