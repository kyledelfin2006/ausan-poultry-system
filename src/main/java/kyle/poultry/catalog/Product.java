package kyle.poultry.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import kyle.poultry.catalog.CatalogApi.ProductView;

@Entity
@Table(name = "product")
class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(nullable = false) private String unit;
    private BigDecimal defaultPrice;
    private boolean active = true;

    protected Product() {}

    Product(String name, String unit, BigDecimal defaultPrice) {
        this.name = name; this.unit = unit; this.defaultPrice = scale(defaultPrice);
    }

    /** Null arguments leave the field unchanged. */
    void update(String name, String unit, BigDecimal defaultPrice, Boolean active) {
        if (name != null) this.name = name;
        if (unit != null) this.unit = unit;
        if (defaultPrice != null) this.defaultPrice = scale(defaultPrice);
        if (active != null) this.active = active;
    }

    boolean active() { return active; }

    ProductView view() { return new ProductView(id, name, unit, defaultPrice, active); }

    private static BigDecimal scale(BigDecimal v) { return v == null ? null : v.setScale(2, RoundingMode.HALF_UP); }
}
