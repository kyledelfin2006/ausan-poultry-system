package kyle.poultry.catalog;

import kyle.poultry.identity.Capability;
import kyle.poultry.identity.RequiresCapability;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import kyle.poultry.catalog.CatalogApi.BranchView;
import kyle.poultry.catalog.CatalogApi.PackageView;
import kyle.poultry.catalog.CatalogApi.ProductView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoints for the catalog: products, branches and sales packages under {@code /api}.
 * <p>
 * Listing is a plain {@code GET} with no method-level role check; creating and changing reference data is
 * restricted with {@code @RequiresCapability(MANAGE_CATALOG)}. Request bodies are validated with Bean Validation
 * ({@code @Valid}); failures become HTTP 400 through the shared exception handler. The controller only
 * translates HTTP to {@link CatalogService} calls and holds no business logic.
 */
@RestController
@RequestMapping("/api")
class CatalogController {
    private final CatalogService catalog;

    /**
     * Creates the controller.
     *
     * @param catalog the service that implements the catalog use cases
     */
    CatalogController(CatalogService catalog) { this.catalog = catalog; }

    /**
     * Request body for creating a product.
     *
     * @param name         required, at most 120 characters
     * @param unit         required unit of measure, at most 20 characters
     * @param defaultPrice optional, not below 0.00, at most 10 integer and 2 fraction digits
     */
    record CreateProduct(@NotBlank @Size(max = 120) String name,
                         @NotBlank @Size(max = 20) String unit,
                         @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal defaultPrice) {}

    /**
     * Request body for a partial product update; any field left out (null) stays unchanged.
     *
     * @param name         new name, 1 to 120 characters if present
     * @param unit         new unit, 1 to 20 characters if present
     * @param defaultPrice new default price, not below 0.00, at most 10 integer and 2 fraction digits
     * @param active       new active flag (false hides the product from new use)
     */
    record UpdateProduct(@Size(min = 1, max = 120) String name,
                         @Size(min = 1, max = 20) String unit,
                         @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal defaultPrice,
                         Boolean active) {}

    /**
     * Request body for creating a branch.
     *
     * @param name required, at most 80 characters
     */
    record CreateBranch(@NotBlank @Size(max = 80) String name) {}

    /**
     * Request body for a partial branch update; any field left out (null) stays unchanged.
     *
     * @param name   new name, 1 to 80 characters if present
     * @param active new active flag
     */
    record UpdateBranch(@Size(min = 1, max = 80) String name, Boolean active) {}

    /**
     * One content line in a package-creation request.
     *
     * @param productId required id of the product
     * @param quantity  units of that product, from 1 to 1,000,000
     */
    record ItemRequest(@NotNull Long productId, @Positive @Max(1_000_000) long quantity) {}

    /**
     * Request body for creating a package.
     *
     * @param sku   required unique code, at most 40 characters
     * @param name  required unique name, at most 120 characters
     * @param price required, at least 0.01, at most 12 integer and 2 fraction digits
     * @param items required non-empty list of valid content lines
     */
    record CreatePackage(@NotBlank @Size(max = 40) String sku,
                         @NotBlank @Size(max = 120) String name,
                         @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal price,
                         @NotEmpty List<@Valid ItemRequest> items) {}

    /**
     * Request body for a partial package update; any field left out (null) stays unchanged.
     * The contents cannot be changed.
     *
     * @param name   new name, 1 to 120 characters if present
     * @param price  new price, at least 0.01, at most 12 integer and 2 fraction digits
     * @param active new active flag
     */
    record UpdatePackage(@Size(min = 1, max = 120) String name,
                         @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal price,
                         Boolean active) {}

    /**
     * Lists all products (active and inactive).
     *
     * @return products ordered by id
     */
    @GetMapping("/products")
    List<ProductView> products() { return catalog.products(); }

    /**
     * Creates a product (owner only).
     *
     * @param r the validated request body
     * @return the created product, with HTTP 201
     */
    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresCapability(Capability.MANAGE_CATALOG)
    ProductView createProduct(@Valid @RequestBody CreateProduct r) {
        return catalog.createProduct(r.name(), r.unit(), r.defaultPrice());
    }

    /**
     * Partially updates a product (owner only).
     *
     * @param id the product id from the URL
     * @param r  the validated request body; null fields are left unchanged
     * @return the updated product
     * @throws kyle.poultry.shared.NotFoundException if no product has that id (HTTP 404)
     */
    @PatchMapping("/products/{id}")
    @RequiresCapability(Capability.MANAGE_CATALOG)
    ProductView updateProduct(@PathVariable long id, @Valid @RequestBody UpdateProduct r) {
        return catalog.updateProduct(id, r.name(), r.unit(), r.defaultPrice(), r.active());
    }

    /**
     * Lists all branches (active and inactive).
     *
     * @return branches ordered by id
     */
    @GetMapping("/branches")
    List<BranchView> branches() { return catalog.branches(); }

    /**
     * Creates a branch (owner only).
     *
     * @param r the validated request body
     * @return the created branch, with HTTP 201
     */
    @PostMapping("/branches")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresCapability(Capability.MANAGE_CATALOG)
    BranchView createBranch(@Valid @RequestBody CreateBranch r) { return catalog.createBranch(r.name()); }

    /**
     * Partially updates a branch (owner only).
     *
     * @param id the branch id from the URL
     * @param r  the validated request body; null fields are left unchanged
     * @return the updated branch
     * @throws kyle.poultry.shared.NotFoundException if no branch has that id (HTTP 404)
     */
    @PatchMapping("/branches/{id}")
    @RequiresCapability(Capability.MANAGE_CATALOG)
    BranchView updateBranch(@PathVariable long id, @Valid @RequestBody UpdateBranch r) {
        return catalog.updateBranch(id, r.name(), r.active());
    }

    /**
     * Lists all sales packages (active and inactive) with their contents.
     *
     * @return packages ordered by id
     */
    @GetMapping("/packages")
    List<PackageView> packages() { return catalog.packages(); }

    /**
     * Creates a sales package (owner only).
     *
     * @param r the validated request body
     * @return the created package with product names, with HTTP 201
     * @throws kyle.poultry.shared.RuleViolation if an item is repeated or refers to an inactive product (HTTP 422)
     * @throws kyle.poultry.shared.NotFoundException if an item refers to an unknown product (HTTP 404)
     */
    @PostMapping("/packages")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresCapability(Capability.MANAGE_CATALOG)
    PackageView createPackage(@Valid @RequestBody CreatePackage r) {
        // Convert the request lines into the module's internal item type before calling the service.
        return catalog.createPackage(r.sku(), r.name(), r.price(),
                r.items().stream().map(i -> new PackageItem(i.productId(), i.quantity())).toList());
    }

    /**
     * Name, price and active flag only. Past deliveries keep the price and name they were sold at.
     *
     * @param id the package id from the URL
     * @param r  the validated request body; null fields are left unchanged
     * @return the updated package
     * @throws kyle.poultry.shared.NotFoundException if no package has that id (HTTP 404)
     */
    @PatchMapping("/packages/{id}")
    @RequiresCapability(Capability.MANAGE_CATALOG)
    PackageView updatePackage(@PathVariable long id, @Valid @RequestBody UpdatePackage r) {
        return catalog.updatePackage(id, r.name(), r.price(), r.active());
    }
}
