package kyle.poultry.catalog;

import jakarta.persistence.*;
import kyle.poultry.catalog.CatalogApi.BranchView;

/**
 * A farm branch (a location that holds stock and receives deliveries), stored in the {@code branch} table.
 * <p>
 * It is package-private: other modules see branches only through {@link BranchView} returned by
 * {@link CatalogApi}. A branch is never deleted; it is deactivated so history that points at it stays valid.
 */
@Entity
@Table(name = "branch")
class Branch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    private boolean active = true;

    /** Required by JPA to instantiate the entity when loading it; not for application use. */
    protected Branch() {}

    /**
     * Creates a new, active branch.
     *
     * @param name the unique branch name
     */
    Branch(String name) { this.name = name; }

    /**
     * Applies a partial update; a {@code null} argument leaves that field unchanged.
     *
     * @param name   the new name, or {@code null} to keep the current one
     * @param active the new active flag, or {@code null} to keep the current one
     */
    void update(String name, Boolean active) {
        // Partial-update (PATCH) semantics: only fields the caller sent are changed.
        if (name != null) this.name = name;
        if (active != null) this.active = active;
    }

    /** {@return whether the branch may still be used for new stock movements and deliveries} */
    boolean active() { return active; }

    /** {@return the immutable public representation of this branch} */
    BranchView view() { return new BranchView(id, name, active); }
}
