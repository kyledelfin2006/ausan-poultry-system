package kyle.poultry.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Product}, keyed by its numeric id.
 * <p>
 * Package-private so only {@link CatalogService} reads or writes products; other modules go through
 * {@link CatalogApi}. It adds no custom queries beyond the standard CRUD methods.
 */
interface ProductRepository extends JpaRepository<Product, Long> {}
