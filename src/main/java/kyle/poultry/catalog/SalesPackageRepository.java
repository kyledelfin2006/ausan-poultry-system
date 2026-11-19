package kyle.poultry.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link SalesPackage}, keyed by its numeric id.
 * <p>
 * Package-private so only {@link CatalogService} reads or writes packages; other modules go through
 * {@link CatalogApi}. The package contents are loaded eagerly together with the package.
 */
interface SalesPackageRepository extends JpaRepository<SalesPackage, Long> {}
