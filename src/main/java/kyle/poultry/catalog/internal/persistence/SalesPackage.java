package kyle.poultry.catalog.internal.persistence;

import kyle.poultry.catalog.api.model.PackageView;
import kyle.poultry.catalog.internal.model.PackageItem;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * A pre-set bundle sold at one price, e.g. "Package Small". Its contents define the stock movement of a sale.
 * <p>
 * Stored in {@code sales_package} with its lines in {@code package_item}. It is internal to the catalog module: other modules
 * see it as {@code PackageView}. The contents are fixed after creation; only name, price and the
 * active flag may change.
 */
@Entity
@Table(name = "sales_package")
public class SalesPackage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String sku;
    @Column(nullable = false, unique = true) private String name;
    @Column(nullable = false) private BigDecimal price;
    private boolean active = true;

    // Eager: the contents are always needed together with the package, and the list is small.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "package_item", joinColumns = @JoinColumn(name = "package_id"))
    private List<PackageItem> items = new ArrayList<>();

    /** Required by JPA to instantiate the entity when loading it; not for application use. */
    protected SalesPackage() {}

    /**
     * Creates a new, active package.
     *
     * @param sku   the unique stock-keeping code
     * @param name  the unique display name
     * @param price the selling price, rounded to 2 decimals (half-up)
     * @param items the contents; copied so later changes to the caller's list do not affect the package
     */
    public SalesPackage(String sku, String name, BigDecimal price, List<PackageItem> items) {
        this.sku = sku; this.name = name; this.price = price.setScale(2, RoundingMode.HALF_UP);
        this.items = new ArrayList<>(items);
    }

    /**
     * Applies a partial update. Null arguments leave the field unchanged. Contents are fixed once created.
     *
     * @param name   the new name, or {@code null} to keep the current one
     * @param price  the new price (rounded to 2 decimals), or {@code null} to keep the current one
     * @param active the new active flag, or {@code null} to keep the current one
     */
    public void update(String name, BigDecimal price, Boolean active) {
        // Partial-update (PATCH) semantics: only fields the caller sent are changed.
        if (name != null) this.name = name;
        if (price != null) this.price = price.setScale(2, RoundingMode.HALF_UP);
        if (active != null) this.active = active;
    }

    /** {@return the database id (null until the package is saved)} */
    public Long id() { return id; }

    /** {@return the unique stock-keeping code} */
    public String sku() { return sku; }

    /** {@return the display name} */
    public String name() { return name; }

    /** {@return the selling price at 2 decimals} */
    public BigDecimal price() { return price; }

    /** {@return whether the package may still be sold} */
    public boolean active() { return active; }

    /** {@return the package contents (the live list held by the entity)} */
    public List<PackageItem> items() { return items; }
}
