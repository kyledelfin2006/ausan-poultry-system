package kyle.poultry.catalog.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Branch}, keyed by its numeric id.
 * <p>
 * Internal to the catalog module so only {@code CatalogService} reads or writes branches; other modules go through
 * {@code CatalogApi}. It adds no custom queries beyond the standard CRUD methods.
 */
public interface BranchRepository extends JpaRepository<Branch, Long> {}
