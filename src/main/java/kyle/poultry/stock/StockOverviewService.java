package kyle.poultry.stock;

import static java.util.Comparator.comparing;
import static java.util.Comparator.comparingLong;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kyle.poultry.catalog.CatalogApi;
import kyle.poultry.catalog.CatalogApi.ProductView;
import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.identity.api.model.Capability;
import kyle.poultry.shared.error.NotFoundException;
import kyle.poultry.shared.error.RuleViolation;
import kyle.poultry.stock.StockApi.BranchStockView;
import kyle.poultry.stock.StockApi.ProductStockView;
import kyle.poultry.stock.StockApi.ReorderLevelView;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model of the stock module: the all-branch summary with low-stock flags, and the reorder levels behind those
 * flags. {@link StockLedger} delegates to it so the ledger class stays about movements. It never touches movements
 * or balances, only reads them, and the only thing it writes is the reorder-level configuration.
 */
@Service
@Transactional(readOnly = true)
class StockOverviewService {
    private final StockBalanceRepository balances;
    private final ReorderLevelRepository levels;
    private final CatalogApi catalog;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param balances current quantities and average costs
     * @param levels   configured reorder levels
     * @param catalog  product names, units and active flags
     * @param clock    source of "now" for the level's update time
     */
    StockOverviewService(StockBalanceRepository balances, ReorderLevelRepository levels, CatalogApi catalog, Clock clock) {
        this.balances = balances; this.levels = levels; this.catalog = catalog; this.clock = clock;
    }

    /**
     * Builds the stock-per-product summary across all branches.
     *
     * @param lowOnly true to keep only products that are low
     * @return products ordered by name
     * @see StockApi#summary(boolean)
     */
    List<ProductStockView> summary(boolean lowOnly) {
        // One catalog call and two table reads; everything else is assembled in memory (a handful of products).
        Map<Long, ProductView> products = catalog.products().stream()
                .collect(Collectors.toMap(ProductView::id, Function.identity()));
        Map<Long, Long> levelByProduct = levels.findAll().stream()
                .collect(Collectors.toMap(ReorderLevel::productId, ReorderLevel::level));
        Map<Long, List<StockBalance>> rowsByProduct = balances.findAll().stream()
                .collect(Collectors.groupingBy(b -> b.id().productId()));

        var result = new ArrayList<ProductStockView>();
        for (ProductView p : products.values()) {
            List<StockBalance> rows = rowsByProduct.getOrDefault(p.id(), List.of());
            long total = rows.stream().mapToLong(StockBalance::quantity).sum();
            // An inactive product with nothing left on any shelf is history, not stock.
            if (!p.active() && total == 0) continue;
            Long level = levelByProduct.get(p.id());
            var branches = rows.stream().sorted(comparingLong(b -> b.id().branchId()))
                    // "At or below": a branch that has exactly the reorder quantity is already due for restocking.
                    .map(b -> new BranchStockView(b.id().branchId(), b.quantity(), b.avgUnitCost(),
                            level != null && b.quantity() <= level))
                    .toList();
            // A product with a level that no branch has ever held is out of stock everywhere, so it is low too.
            boolean low = level != null && (branches.isEmpty() || branches.stream().anyMatch(BranchStockView::low));
            if (lowOnly && !low) continue;
            result.add(new ProductStockView(p.id(), p.name(), p.unit(), total, level, low, branches));
        }
        result.sort(comparing(ProductStockView::productName));
        return result;
    }

    /**
     * Lists the configured reorder levels.
     *
     * @return levels ordered by product name
     */
    List<ReorderLevelView> reorderLevels() {
        Map<Long, ProductView> products = catalog.products().stream()
                .collect(Collectors.toMap(ProductView::id, Function.identity()));
        return levels.findAll().stream()
                .map(l -> view(l, products.get(l.productId()).name()))
                .sorted(comparing(ReorderLevelView::productName))
                .toList();
    }

    /**
     * Sets or replaces a product's reorder level (owner only).
     *
     * @param productId the product
     * @param level     units at or below which a branch is low
     * @param actor     who sets it
     * @return the stored level
     * @throws AccessDeniedException when the actor is not the owner
     * @throws RuleViolation         when the level is negative or the product is inactive
     * @throws NotFoundException     when the product does not exist
     */
    @Transactional
    ReorderLevelView setReorderLevel(long productId, long level, Actor actor) {
        requireOwner(actor);
        if (level < 0) throw new RuleViolation("Reorder level cannot be negative");
        // Also proves the product exists, and refuses configuring a retired one.
        ProductView product = catalog.requireActiveProduct(productId);
        var now = clock.instant();
        // Upsert: change the existing row, or add the first one for this product.
        var row = levels.findById(productId).orElse(null);
        if (row == null) row = new ReorderLevel(productId, level, actor.userId(), now);
        else row.change(level, actor.userId(), now);
        return view(levels.save(row), product.name());
    }

    /**
     * Removes a product's reorder level (owner only).
     *
     * @param productId the product
     * @param actor     who removes it
     * @throws AccessDeniedException when the actor is not the owner
     * @throws NotFoundException     when the product has no level
     */
    @Transactional
    void clearReorderLevel(long productId, Actor actor) {
        requireOwner(actor);
        var row = levels.findById(productId).orElseThrow(() -> new NotFoundException("Reorder level of product", productId));
        levels.delete(row);
    }

    /**
     * Maps a stored level to its public view.
     *
     * @param l           the entity
     * @param productName the product's name
     * @return the view
     */
    private static ReorderLevelView view(ReorderLevel l, String productName) {
        return new ReorderLevelView(l.productId(), productName, l.level(), l.updatedBy(), l.updatedAt());
    }

    /**
     * Requires the owner role.
     *
     * @param actor who is acting
     * @throws AccessDeniedException when the actor is not the owner
     */
    private static void requireOwner(Actor actor) {
        actor.require(Capability.SET_REORDER_LEVELS);
    }
}
