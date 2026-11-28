package kyle.poultry.delivery.internal.service;

import kyle.poultry.delivery.internal.model.DeliveryLine;
import kyle.poultry.delivery.internal.model.DeliveryReceipt;
import kyle.poultry.delivery.internal.persistence.Delivery;
import kyle.poultry.delivery.internal.persistence.DeliveryRepository;
import kyle.poultry.catalog.api.model.PackageItemView;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kyle.poultry.catalog.api.CatalogApi;
import kyle.poultry.catalog.api.model.PackageView;
import kyle.poultry.catalog.api.model.ProductView;
import kyle.poultry.delivery.internal.model.DeliveryReceipt.ReceiptLine;
import kyle.poultry.delivery.internal.model.DeliveryReceipt.ReceiptProduct;
import kyle.poultry.finance.api.FinanceApi;
import kyle.poultry.shared.error.NotFoundException;
import kyle.poultry.shared.reference.SourceRef;
import kyle.poultry.stock.api.model.MovementType;
import kyle.poultry.stock.api.StockApi;
import kyle.poultry.stock.api.model.MovementView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the owner's {@link DeliveryReceipt}: the delivery as sold, plus the cost of the stock that left the branch for
 * it. The cost is never recomputed: it is read from the delivery's own stock-out movements, which carry the average cost
 * at the moment of the sale, so the receipt always agrees with the cost of goods booked in the ledger.
 *
 * <p>Stock is taken out per product, not per package line, so a line's cost is allocated: each product's cost is split
 * over the lines that used it in proportion to the units they used (package contents are fixed after creation, so the
 * units are quantity x items). The split is in whole centavos by the largest-remainder method, so no line gets a negative
 * cost and the lines always add up to the delivery's cost exactly. Read-only; internal to the delivery module.
 */
@Service
@Transactional(readOnly = true)
public class DeliveryReceiptService {
    private static final String SOURCE_TYPE = "DELIVERY";

    private final DeliveryRepository deliveries;
    private final CatalogApi catalog;
    private final StockApi stock;
    private final FinanceApi finance;

    /**
     * Creates the service.
     *
     * @param deliveries delivery persistence
     * @param catalog    package contents and product names
     * @param stock      the delivery's stock-out movements
     * @param finance    the retained payment of a cancelled delivery
     */
    DeliveryReceiptService(DeliveryRepository deliveries, CatalogApi catalog, StockApi stock, FinanceApi finance) {
        this.deliveries = deliveries; this.catalog = catalog; this.stock = stock; this.finance = finance;
    }

    /**
     * Builds the receipt of one delivery.
     *
     * @param id the delivery
     * @return the receipt
     * @throws NotFoundException when the delivery does not exist
     */
    public DeliveryReceipt receipt(long id) {
        var d = deliveries.findById(id).orElseThrow(() -> new NotFoundException("Delivery", id));
        var source = new SourceRef(SOURCE_TYPE, d.id());
        Map<Long, ProductView> products = catalog.products().stream()
                .collect(Collectors.toMap(ProductView::id, Function.identity()));
        Map<Long, PackageView> packages = catalog.packages().stream()
                .collect(Collectors.toMap(PackageView::id, Function.identity()));

        // The original stock-outs only: reversals of a cancelled delivery are not part of what was sold.
        Map<Long, MovementView> outByProduct = stock.movementsFor(source).stream()
                .filter(m -> m.type() == MovementType.DELIVERY_OUT)
                .collect(Collectors.toMap(MovementView::productId, Function.identity()));

        var productRows = new ArrayList<ReceiptProduct>();
        BigDecimal cost = BigDecimal.ZERO.setScale(2);
        // Cost of each line, filled in by the allocation below, in the order of the delivery's lines.
        var lineCosts = new ArrayList<BigDecimal>();
        for (int i = 0; i < d.lines().size(); i++) lineCosts.add(BigDecimal.ZERO.setScale(2));

        for (var m : outByProduct.values().stream().sorted((a, b) -> Long.compare(a.productId(), b.productId())).toList()) {
            var p = products.get(m.productId());
            BigDecimal productCost = m.totalCost();
            cost = cost.add(productCost);
            productRows.add(new ReceiptProduct(m.productId(), p.name(), p.unit(), -m.quantityDelta(), m.unitCost(), productCost));
            allocate(productCost, m.productId(), d.lines(), packages, lineCosts);
        }

        var lines = new ArrayList<ReceiptLine>();
        for (int i = 0; i < d.lines().size(); i++) {
            var l = d.lines().get(i);
            lines.add(new ReceiptLine(l.packageId(), l.packageName(), l.quantity(), l.unitPrice(), l.lineTotal(),
                    lineCosts.get(i), l.lineTotal().subtract(lineCosts.get(i))));
        }
        BigDecimal profit = d.total().subtract(cost);
        // Margin is "profit out of every 100 pesos of sale"; the total is always positive (a database rule).
        BigDecimal margin = profit.multiply(BigDecimal.valueOf(100)).divide(d.total(), 2, RoundingMode.HALF_UP);
        boolean cancelled = d.isReversed();
        return new DeliveryReceipt(d.id(), d.code(), d.status(), d.customerName(), d.driverName(), d.fromBranchId(),
                d.destination(), d.startedAt(), d.completedAt(), d.paymentMethod(), d.cancelReason(), d.returnReason(),
                cancelled ? finance.retainedPayment(source) : null, !cancelled, d.total(), cost, profit, margin,
                List.copyOf(lines), List.copyOf(productRows));
    }

    /**
     * Splits one product's cost over the delivery lines that used it, in proportion to the units each line used.
     *
     * @param productCost the product's total cost, in centavos
     * @param productId   the product being split
     * @param lines       the delivery's package lines
     * @param packages    package contents by package id
     * @param lineCosts   running cost per line (same order as {@code lines}); this method adds to it
     */
    static void allocate(BigDecimal productCost, long productId, List<DeliveryLine> lines,
                                 Map<Long, PackageView> packages, List<BigDecimal> lineCosts) {
        // Units of this product each line consumed: package quantity x units of the product per package.
        Map<Integer, Long> unitsByLine = new HashMap<>();
        long totalUnits = 0;
        for (int i = 0; i < lines.size(); i++) {
            var pkg = packages.get(lines.get(i).packageId());
            long perPackage = pkg == null ? 0 : pkg.items().stream().filter(it -> it.productId() == productId)
                    .mapToLong(PackageItemView::quantity).sum();
            long units = perPackage * lines.get(i).quantity();
            if (units > 0) { unitsByLine.put(i, units); totalUnits += units; }
        }
        if (totalUnits == 0) return;   // not reachable for a real delivery; the product row still carries the cost
        // Largest-remainder split in whole centavos: every line gets the rounded-down share of its units, then the
        // centavos left over go one each to the lines with the biggest fractional remainder. No share is ever
        // negative, no line is off by more than one centavo, and the shares add up to the product cost exactly.
        BigInteger cents = productCost.movePointRight(2).toBigIntegerExact();
        BigInteger units = BigInteger.valueOf(totalUnits);
        var using = unitsByLine.keySet().stream().sorted().toList();
        BigInteger[] floor = new BigInteger[using.size()];
        BigInteger[] remainder = new BigInteger[using.size()];
        BigInteger assigned = BigInteger.ZERO;
        for (int n = 0; n < using.size(); n++) {
            var qr = cents.multiply(BigInteger.valueOf(unitsByLine.get(using.get(n)))).divideAndRemainder(units);
            floor[n] = qr[0];
            remainder[n] = qr[1];
            assigned = assigned.add(qr[0]);
        }
        int leftover = cents.subtract(assigned).intValueExact();   // fewer than one centavo per line
        // Biggest remainder first; on a tie the later line wins, so an even split gives the odd centavo to the last line.
        var byRemainder = new ArrayList<>(java.util.stream.IntStream.range(0, using.size()).boxed().toList());
        byRemainder.sort(Comparator.<Integer, BigInteger>comparing(n -> remainder[n]).reversed()
                .thenComparing(Comparator.<Integer>naturalOrder().reversed()));
        for (int k = 0; k < leftover; k++) floor[byRemainder.get(k)] = floor[byRemainder.get(k)].add(BigInteger.ONE);
        for (int n = 0; n < using.size(); n++)
            lineCosts.set(using.get(n), lineCosts.get(using.get(n)).add(new BigDecimal(floor[n], 2)));
    }
}
