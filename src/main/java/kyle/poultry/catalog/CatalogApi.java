package kyle.poultry.catalog;

import java.math.BigDecimal;
import java.util.List;

/**
 * Public face of the catalog module: reference data other modules may depend on.
 * <p>
 * Contract for every method: it is a plain method call that joins the caller's transaction when one is active
 * (the implementation is read-only by default, so it never writes), takes no locks and returns immutable
 * views, never entities. Failures are reported with the shared exceptions, which the web layer maps to HTTP
 * status codes: {@link kyle.poultry.shared.NotFoundException} (404) and
 * {@link kyle.poultry.shared.RuleViolation} (422).
 * <p>
 * Callers: the stock module validates products and branches here; the delivery module validates packages.
 */
public interface CatalogApi {

    /**
     * Loads a product that must exist and be active.
     *
     * @param id the product id
     * @return the product view
     * @throws kyle.poultry.shared.NotFoundException if no product has that id
     * @throws kyle.poultry.shared.RuleViolation     if the product exists but is inactive
     */
    ProductView requireActiveProduct(long id);

    /**
     * Loads a branch that must exist and be active.
     *
     * @param id the branch id
     * @return the branch view
     * @throws kyle.poultry.shared.NotFoundException if no branch has that id
     * @throws kyle.poultry.shared.RuleViolation     if the branch exists but is inactive
     */
    BranchView requireActiveBranch(long id);

    /**
     * Loads a sellable package with its contents, with the same failure modes as the other
     * {@code requireActive...} methods. Only the package's own flag is checked, not that of its products.
     *
     * @param id the package id
     * @return the package view including its items with product names
     * @throws kyle.poultry.shared.NotFoundException if no package has that id
     * @throws kyle.poultry.shared.RuleViolation     if the package exists but is inactive
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

    /**
     * Public representation of a product.
     *
     * @param id           the product id
     * @param name         the unique product name
     * @param unit         the unit of measure, e.g. {@code "pc"}
     * @param defaultPrice the suggested selling price at 2 decimals, or {@code null} if none
     * @param active       whether the product may be used for new movements and packages
     */
    record ProductView(long id, String name, String unit, BigDecimal defaultPrice, boolean active) {}

    /**
     * Public representation of a branch.
     *
     * @param id     the branch id
     * @param name   the unique branch name
     * @param active whether the branch may be used for new movements and deliveries
     */
    record BranchView(long id, String name, boolean active) {}

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
    record PackageView(long id, String sku, String name, BigDecimal price, boolean active, List<PackageItemView> items) {}

    /**
     * One line of a package's contents.
     *
     * @param productId   the id of the contained product
     * @param productName the product's current name
     * @param quantity    units of that product in one package
     */
    record PackageItemView(long productId, String productName, long quantity) {}
}
