package kyle.poultry.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import kyle.poultry.catalog.CatalogApi.BranchView;
import kyle.poultry.catalog.CatalogApi.PackageView;
import kyle.poultry.catalog.CatalogApi.ProductView;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class CatalogController {
    private final CatalogService catalog;

    CatalogController(CatalogService catalog) { this.catalog = catalog; }

    record CreateProduct(@NotBlank @Size(max = 120) String name,
                         @NotBlank @Size(max = 20) String unit,
                         @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal defaultPrice) {}

    record UpdateProduct(@Size(min = 1, max = 120) String name,
                         @Size(min = 1, max = 20) String unit,
                         @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal defaultPrice,
                         Boolean active) {}

    record CreateBranch(@NotBlank @Size(max = 80) String name) {}

    record UpdateBranch(@Size(min = 1, max = 80) String name, Boolean active) {}

    record ItemRequest(@NotNull Long productId, @Positive long quantity) {}

    record CreatePackage(@NotBlank @Size(max = 40) String sku,
                         @NotBlank @Size(max = 120) String name,
                         @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal price,
                         @NotEmpty List<@Valid ItemRequest> items) {}

    record UpdatePackage(@Size(min = 1, max = 120) String name,
                         @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal price,
                         Boolean active) {}

    @GetMapping("/products")
    List<ProductView> products() { return catalog.products(); }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    ProductView createProduct(@Valid @RequestBody CreateProduct r) {
        return catalog.createProduct(r.name(), r.unit(), r.defaultPrice());
    }

    @PatchMapping("/products/{id}")
    @PreAuthorize("hasRole('OWNER')")
    ProductView updateProduct(@PathVariable long id, @Valid @RequestBody UpdateProduct r) {
        return catalog.updateProduct(id, r.name(), r.unit(), r.defaultPrice(), r.active());
    }

    @GetMapping("/branches")
    List<BranchView> branches() { return catalog.branches(); }

    @PostMapping("/branches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    BranchView createBranch(@Valid @RequestBody CreateBranch r) { return catalog.createBranch(r.name()); }

    @PatchMapping("/branches/{id}")
    @PreAuthorize("hasRole('OWNER')")
    BranchView updateBranch(@PathVariable long id, @Valid @RequestBody UpdateBranch r) {
        return catalog.updateBranch(id, r.name(), r.active());
    }

    @GetMapping("/packages")
    List<PackageView> packages() { return catalog.packages(); }

    @PostMapping("/packages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    PackageView createPackage(@Valid @RequestBody CreatePackage r) {
        return catalog.createPackage(r.sku(), r.name(), r.price(),
                r.items().stream().map(i -> new PackageItem(i.productId(), i.quantity())).toList());
    }

    /** Name, price and active flag only. Past deliveries keep the price and name they were sold at. */
    @PatchMapping("/packages/{id}")
    @PreAuthorize("hasRole('OWNER')")
    PackageView updatePackage(@PathVariable long id, @Valid @RequestBody UpdatePackage r) {
        return catalog.updatePackage(id, r.name(), r.price(), r.active());
    }
}
