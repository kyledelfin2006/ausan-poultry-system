package kyle.poultry.stock.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to {@link ReorderLevel}, keyed by product id. Internal to the stock module: only {@code StockOverviewService}
 * uses it.
 */
public interface ReorderLevelRepository extends JpaRepository<ReorderLevel, Long> {}
