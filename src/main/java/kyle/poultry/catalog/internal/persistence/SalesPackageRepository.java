package kyle.poultry.catalog.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link SalesPackage}, keyed by its numeric id.
 * <p>
 * Internal to the catalog module so only {@code CatalogService} reads or writes packages; other modules go through
 * {@code CatalogApi}. The package contents are loaded eagerly together with the package.
 */
public interface SalesPackageRepository extends JpaRepository<SalesPackage, Long> {}
