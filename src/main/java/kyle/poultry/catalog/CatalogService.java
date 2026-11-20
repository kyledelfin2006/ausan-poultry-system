package kyle.poultry.catalog;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kyle.poultry.audit.AuditAction;
import kyle.poultry.audit.AuditApi;
import kyle.poultry.audit.AuditChanges;
import kyle.poultry.audit.AuditEntity;
import kyle.poultry.shared.NotFoundException;
import kyle.poultry.shared.RuleViolation;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the catalog use cases and the public {@link CatalogApi}.
 * <p>
 * The class is read-only transactional by default; every method that changes data re-declares
 * {@code @Transactional} to make it read-write. Updates rely on JPA dirty checking, so the changed entity is
 * flushed at commit without an explicit save. It is package-private: other modules use it only through
 * {@link CatalogApi}, and {@link CatalogController} calls the non-API methods.
 */
@Service
@Transactional(readOnly = true)
class CatalogService implements CatalogApi {
    private final ProductRepository products;
    private final BranchRepository branches;
    private final SalesPackageRepository packages;
    private final AuditApi audit;

    /**
     * Creates the service.
     *
     * @param products the product repository
     * @param branches the branch repository
     * @param packages the sales package repository
     * @param audit    append-only trail of who changed what
     */
    CatalogService(ProductRepository products, BranchRepository branches, SalesPackageRepository packages, AuditApi audit) {
        this.products = products; this.branches = branches; this.packages = packages; this.audit = audit;
    }

    /** {@inheritDoc} */
    @Override
    public ProductView requireActiveProduct(long id) {
        var p = products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
        // Inactive products stay in the database for history but may not be used for anything new.
        if (!p.active()) throw new RuleViolation("Product " + id + " is inactive");
        return p.view();
    }

    /** {@inheritDoc} */
    @Override
    public BranchView requireActiveBranch(long id) {
        var b = branches.findById(id).orElseThrow(() -> new NotFoundException("Branch", id));
        // Inactive branches stay in the database for history but may not be used for anything new.
        if (!b.active()) throw new RuleViolation("Branch " + id + " is inactive");
        return b.view();
    }

    /** {@inheritDoc} */
    @Override
    public PackageView requireActivePackage(long id) {
        var p = packages.findById(id).orElseThrow(() -> new NotFoundException("Package", id));
        // The message uses the package name (not the id) because that is what staff recognise.
        if (!p.active()) throw new RuleViolation("Package " + p.name() + " is inactive");
        return view(p, productNames());
    }

    /** {@inheritDoc} */
    @Override
    public List<ProductView> products() {
        return products.findAll(Sort.by("id")).stream().map(Product::view).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<PackageView> packages() {
        // Load the product names once, then reuse them for every package.
        var names = productNames();
        return packages.findAll(Sort.by("id")).stream().map(p -> view(p, names)).toList();
    }

    /**
     * Lists every branch, active or not, ordered by id.
     *
     * @return all branches
     */
    List<BranchView> branches() {
        return branches.findAll(Sort.by("id")).stream().map(Branch::view).toList();
    }

    /**
     * Creates a product.
     *
     * @param name         the unique product name
     * @param unit         the unit of measure
     * @param defaultPrice the suggested price, or {@code null}
     * @return the saved product
     */
    @Transactional
    ProductView createProduct(String name, String unit, BigDecimal defaultPrice) {
        var saved = products.save(new Product(name, unit, defaultPrice)).view();
        audit.record(AuditAction.CREATED, AuditEntity.PRODUCT, saved.id(), "name: " + saved.name() + "; unit: " + saved.unit());
        return saved;
    }

    /**
     * Partially updates a product; {@code null} arguments leave a field unchanged.
     *
     * @param id           the product id
     * @param name         the new name, or {@code null}
     * @param unit         the new unit, or {@code null}
     * @param defaultPrice the new default price, or {@code null}
     * @param active       the new active flag, or {@code null}
     * @return the updated product
     * @throws NotFoundException if no product has that id
     */
    @Transactional
    ProductView updateProduct(long id, String name, String unit, BigDecimal defaultPrice, Boolean active) {
        var p = products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
        var before = p.view();
        // No save() needed: the entity is managed, so the change is flushed when the transaction commits.
        p.update(name, unit, defaultPrice, active);
        var after = p.view();
        var changes = AuditChanges.of().field("name", before.name(), after.name()).field("unit", before.unit(), after.unit())
                .field("defaultPrice", before.defaultPrice(), after.defaultPrice()).field("active", before.active(), after.active());
        if (!changes.isEmpty()) audit.record(AuditAction.UPDATED, AuditEntity.PRODUCT, id, changes.summary());
        return after;
    }

    /**
     * Creates a branch.
     *
     * @param name the unique branch name
     * @return the saved branch
     */
    @Transactional
    BranchView createBranch(String name) {
        var saved = branches.save(new Branch(name)).view();
        audit.record(AuditAction.CREATED, AuditEntity.BRANCH, saved.id(), "name: " + saved.name());
        return saved;
    }

    /**
     * Partially updates a branch; {@code null} arguments leave a field unchanged.
     *
     * @param id     the branch id
     * @param name   the new name, or {@code null}
     * @param active the new active flag, or {@code null}
     * @return the updated branch
     * @throws NotFoundException if no branch has that id
     */
    @Transactional
    BranchView updateBranch(long id, String name, Boolean active) {
        var b = branches.findById(id).orElseThrow(() -> new NotFoundException("Branch", id));
        var before = b.view();
        // No save() needed: the entity is managed, so the change is flushed when the transaction commits.
        b.update(name, active);
        var after = b.view();
        var changes = AuditChanges.of().field("name", before.name(), after.name()).field("active", before.active(), after.active());
        if (!changes.isEmpty()) audit.record(AuditAction.UPDATED, AuditEntity.BRANCH, id, changes.summary());
        return after;
    }

    /**
     * Creates a sales package after validating its contents.
     *
     * @param sku   the unique stock-keeping code
     * @param name  the unique display name
     * @param price the selling price
     * @param items the contents; must be non-empty, with positive quantities and each product listed once
     * @return the saved package, with product names
     * @throws RuleViolation if the list is empty, a quantity is not positive, a product appears twice,
     *                       or a product is inactive
     * @throws NotFoundException if a listed product does not exist
     */
    @Transactional
    PackageView createPackage(String sku, String name, BigDecimal price, List<PackageItem> items) {
        // The controller already validates input, but the service re-checks so other callers cannot bypass it.
        if (items == null || items.isEmpty()) throw new RuleViolation("A package needs at least one item");
        var seen = new HashSet<Long>();
        for (var item : items) {
            if (item.quantity() <= 0) throw new RuleViolation("Item quantities must be positive");
            // A product listed twice would be ambiguous (two lines for one product), so it is refused.
            if (!seen.add(item.productId())) throw new RuleViolation("Product " + item.productId() + " is listed twice");
            // Fails with not-found or inactive, so a package can never be created around a retired product.
            requireActiveProduct(item.productId());
        }
        var saved = packages.save(new SalesPackage(sku, name, price, items));
        audit.record(AuditAction.CREATED, AuditEntity.PACKAGE, saved.id(),
                "sku: " + saved.sku() + "; name: " + saved.name() + "; price: " + saved.price().toPlainString());
        return view(saved, productNames());
    }

    /**
     * Partially updates a package's name, price or active flag; the contents never change.
     *
     * @param id     the package id
     * @param name   the new name, or {@code null}
     * @param price  the new price, or {@code null}
     * @param active the new active flag, or {@code null}
     * @return the updated package
     * @throws NotFoundException if no package has that id
     */
    @Transactional
    PackageView updatePackage(long id, String name, BigDecimal price, Boolean active) {
        var p = packages.findById(id).orElseThrow(() -> new NotFoundException("Package", id));
        var beforeName = p.name(); var beforePrice = p.price(); var beforeActive = p.active();
        // No save() needed: the entity is managed, so the change is flushed when the transaction commits.
        p.update(name, price, active);
        var changes = AuditChanges.of().field("name", beforeName, p.name()).field("price", beforePrice, p.price())
                .field("active", beforeActive, p.active());
        if (!changes.isEmpty()) audit.record(AuditAction.UPDATED, AuditEntity.PACKAGE, id, changes.summary());
        return view(p, productNames());
    }

    /**
     * Builds a lookup of product id to product name, including inactive products.
     *
     * @return a map from product id to name
     */
    private Map<Long, String> productNames() {
        return products.findAll().stream().collect(Collectors.toMap(p -> p.view().id(), p -> p.view().name()));
    }

    /**
     * Converts a package entity into its public view, attaching the product name to each content line.
     *
     * @param p     the package entity
     * @param names the product id to name lookup from {@link #productNames()}
     * @return the public package view
     */
    private static PackageView view(SalesPackage p, Map<Long, String> names) {
        // The package stores only product ids; names are looked up so callers get a readable view.
        var items = p.items().stream()
                .map(i -> new PackageItemView(i.productId(), names.get(i.productId()), i.quantity()))
                .toList();
        return new PackageView(p.id(), p.sku(), p.name(), p.price(), p.active(), items);
    }
}
