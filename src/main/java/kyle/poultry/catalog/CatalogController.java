package kyle.poultry.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import kyle.poultry.catalog.CatalogApi.BranchView;
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
}
