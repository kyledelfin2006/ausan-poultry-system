package kyle.poultry.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import kyle.poultry.catalog.CatalogApi.ProductView;

/**
 * A stockable, sellable product (for example a chicken or a bag of feed), stored in the {@code product} table.
 * <p>
 * It is package-private: other modules see products only through {@link ProductView} returned by
 * {@link CatalogApi}. Products are deactivated rather than deleted so stock and delivery history stays valid.
 */
@Entity
@Table(name = "product")
class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(nullable = false) private String unit;
    private BigDecimal defaultPrice;
    private boolean active = true;

    /** Required by JPA to instantiate the entity when loading it; not for application use. */
    protected Product() {}

    /**
     * Creates a new, active product.
     *
     * @param name         the unique product name
     * @param unit         the unit of measure shown with quantities, e.g. {@code "pc"}
     * @param defaultPrice the suggested selling price, or {@code null} if none; rounded to 2 decimals
     */
    Product(String name, String unit, BigDecimal defaultPrice) {
        this.name = name; this.unit = unit; this.defaultPrice = scale(defaultPrice);
    }

    /**
     * Applies a partial update. Null arguments leave the field unchanged.
     *
     * @param name         the new name, or {@code null} to keep the current one
     * @param unit         the new unit, or {@code null} to keep the current one
     * @param defaultPrice the new default price (rounded to 2 decimals), or {@code null} to keep the current one
     * @param active       the new active flag, or {@code null} to keep the current one
     */
    void update(String name, String unit, BigDecimal defaultPrice, Boolean active) {
        // Partial-update (PATCH) semantics: only fields the caller sent are changed.
        if (name != null) this.name = name;
        if (unit != null) this.unit = unit;
        if (defaultPrice != null) this.defaultPrice = scale(defaultPrice);
        if (active != null) this.active = active;
    }

    /** {@return whether the product may still be used for new stock movements and packages} */
    boolean active() { return active; }

    /** {@return the immutable public representation of this product} */
    ProductView view() { return new ProductView(id, name, unit, defaultPrice, active); }

    /**
     * Rounds a money amount to centavos (2 decimals, half-up), passing {@code null} through.
     *
     * @param v the amount to round, may be {@code null}
     * @return the amount at scale 2, or {@code null} if {@code v} was {@code null}
     */
    private static BigDecimal scale(BigDecimal v) { return v == null ? null : v.setScale(2, RoundingMode.HALF_UP); }
}
