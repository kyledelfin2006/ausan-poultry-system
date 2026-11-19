package kyle.poultry.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** A pre-set bundle sold at one price, e.g. "Package Small". Its contents define the stock movement of a sale. */
@Entity
@Table(name = "sales_package")
class SalesPackage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String sku;
    @Column(nullable = false, unique = true) private String name;
    @Column(nullable = false) private BigDecimal price;
    private boolean active = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "package_item", joinColumns = @JoinColumn(name = "package_id"))
    private List<PackageItem> items = new ArrayList<>();

    protected SalesPackage() {}

    SalesPackage(String sku, String name, BigDecimal price, List<PackageItem> items) {
        this.sku = sku; this.name = name; this.price = price.setScale(2, RoundingMode.HALF_UP);
        this.items = new ArrayList<>(items);
    }

    /** Null arguments leave the field unchanged. Contents are fixed once created. */
    void update(String name, BigDecimal price, Boolean active) {
        if (name != null) this.name = name;
        if (price != null) this.price = price.setScale(2, RoundingMode.HALF_UP);
        if (active != null) this.active = active;
    }

    Long id() { return id; }
    String sku() { return sku; }
    String name() { return name; }
    BigDecimal price() { return price; }
    boolean active() { return active; }
    List<PackageItem> items() { return items; }
}
