package kyle.poultry.catalog;

import jakarta.persistence.*;
import kyle.poultry.catalog.CatalogApi.BranchView;

@Entity
@Table(name = "branch")
class Branch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    private boolean active = true;

    protected Branch() {}

    Branch(String name) { this.name = name; }

    void update(String name, Boolean active) {
        if (name != null) this.name = name;
        if (active != null) this.active = active;
    }

    boolean active() { return active; }

    BranchView view() { return new BranchView(id, name, active); }
}
