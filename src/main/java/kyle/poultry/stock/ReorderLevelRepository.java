package kyle.poultry.stock;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to {@link ReorderLevel}, keyed by product id. Package-private: only {@link StockOverviewService}
 * uses it.
 */
interface ReorderLevelRepository extends JpaRepository<ReorderLevel, Long> {}
