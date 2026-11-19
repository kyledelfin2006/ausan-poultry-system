package kyle.poultry.catalog;

import java.math.BigDecimal;
import java.util.List;
import kyle.poultry.shared.NotFoundException;
import kyle.poultry.shared.RuleViolation;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class CatalogService implements CatalogApi {
    private final ProductRepository products;
    private final BranchRepository branches;

    CatalogService(ProductRepository products, BranchRepository branches) {
        this.products = products; this.branches = branches;
    }

    @Override
    public ProductView requireActiveProduct(long id) {
        var p = products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
        if (!p.active()) throw new RuleViolation("Product " + id + " is inactive");
        return p.view();
    }

    @Override
    public BranchView requireActiveBranch(long id) {
        var b = branches.findById(id).orElseThrow(() -> new NotFoundException("Branch", id));
        if (!b.active()) throw new RuleViolation("Branch " + id + " is inactive");
        return b.view();
    }

    @Override
    public List<ProductView> products() {
        return products.findAll(Sort.by("id")).stream().map(Product::view).toList();
    }

    List<BranchView> branches() {
        return branches.findAll(Sort.by("id")).stream().map(Branch::view).toList();
    }

    @Transactional
    ProductView createProduct(String name, String unit, BigDecimal defaultPrice) {
        return products.save(new Product(name, unit, defaultPrice)).view();
    }

    @Transactional
    ProductView updateProduct(long id, String name, String unit, BigDecimal defaultPrice, Boolean active) {
        var p = products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
        p.update(name, unit, defaultPrice, active);
        return p.view();
    }

    @Transactional
    BranchView createBranch(String name) {
        return branches.save(new Branch(name)).view();
    }

    @Transactional
    BranchView updateBranch(long id, String name, Boolean active) {
        var b = branches.findById(id).orElseThrow(() -> new NotFoundException("Branch", id));
        b.update(name, active);
        return b.view();
    }
}
