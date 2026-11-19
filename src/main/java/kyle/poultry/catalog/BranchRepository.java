package kyle.poultry.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Branch}, keyed by its numeric id.
 * <p>
 * Package-private so only {@link CatalogService} reads or writes branches; other modules go through
 * {@link CatalogApi}. It adds no custom queries beyond the standard CRUD methods.
 */
interface BranchRepository extends JpaRepository<Branch, Long> {}
