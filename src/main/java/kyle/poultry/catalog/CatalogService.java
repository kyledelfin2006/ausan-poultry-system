package kyle.poultry.catalog;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private final SalesPackageRepository packages;

    CatalogService(ProductRepository products, BranchRepository branches, SalesPackageRepository packages) {
        this.products = products; this.branches = branches; this.packages = packages;
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
    public PackageView requireActivePackage(long id) {
        var p = packages.findById(id).orElseThrow(() -> new NotFoundException("Package", id));
        if (!p.active()) throw new RuleViolation("Package " + p.name() + " is inactive");
        return view(p, productNames());
    }

    @Override
    public List<ProductView> products() {
        return products.findAll(Sort.by("id")).stream().map(Product::view).toList();
    }

    @Override
    public List<PackageView> packages() {
        var names = productNames();
        return packages.findAll(Sort.by("id")).stream().map(p -> view(p, names)).toList();
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

    @Transactional
    PackageView createPackage(String sku, String name, BigDecimal price, List<PackageItem> items) {
        if (items == null || items.isEmpty()) throw new RuleViolation("A package needs at least one item");
        var seen = new HashSet<Long>();
        for (var item : items) {
            if (item.quantity() <= 0) throw new RuleViolation("Item quantities must be positive");
            if (!seen.add(item.productId())) throw new RuleViolation("Product " + item.productId() + " is listed twice");
            requireActiveProduct(item.productId());
        }
        var saved = packages.save(new SalesPackage(sku, name, price, items));
        return view(saved, productNames());
    }

    @Transactional
    PackageView updatePackage(long id, String name, BigDecimal price, Boolean active) {
        var p = packages.findById(id).orElseThrow(() -> new NotFoundException("Package", id));
        p.update(name, price, active);
        return view(p, productNames());
    }

    private Map<Long, String> productNames() {
        return products.findAll().stream().collect(Collectors.toMap(p -> p.view().id(), p -> p.view().name()));
    }

    private static PackageView view(SalesPackage p, Map<Long, String> names) {
        var items = p.items().stream()
                .map(i -> new PackageItemView(i.productId(), names.get(i.productId()), i.quantity()))
                .toList();
        return new PackageView(p.id(), p.sku(), p.name(), p.price(), p.active(), items);
    }
}
