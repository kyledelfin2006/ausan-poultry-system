package kyle.poultry.delivery.internal.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import kyle.poultry.finance.api.model.PaymentMethod;

/**
 * The owner's receipt of one delivery: the sale as the customer saw it, plus what the goods cost and what was earned.
 * Internal to the delivery module read model serialized by {@code DeliveryController}. All money is PHP at 2 decimals, except unit costs
 * (4 decimals).
 *
 * <p>The figures describe the sale as it was made. For a CANCELLED or RETURNED delivery the ledger has reversed the sale and its
 * cost, so {@code countedInProfit} is false and the delivery contributes nothing to the profit report.
 *
 * @param id               database id
 * @param code             human-readable code such as {@code DLV-2026-0001}
 * @param status           lifecycle state
 * @param customerName     who the goods are for
 * @param driverName       who delivers them
 * @param fromBranchId     branch whose stock was used
 * @param destination      where the goods go
 * @param startedAt        when the trip started
 * @param completedAt      when it arrived, or null while not done
 * @param paymentMethod    how the customer paid
 * @param cancelReason     why it was cancelled, or null
 * @param returnReason     why the goods came back after arrival, or null
 * @param retainedPayment  for a CANCELLED or RETURNED delivery the money held and not refunded; otherwise null
 * @param countedInProfit  false for a CANCELLED or RETURNED delivery
 * @param total            sum of the package line totals (what the customer paid)
 * @param costOfGoods      what the delivered stock cost, at the average cost at the time of the sale
 * @param grossProfit      total minus cost of goods
 * @param marginPercent    gross profit as a percentage of the total, 2 decimals
 * @param lines            the packages sold, each with its share of the cost
 * @param products         the stock taken out, per product
 */
public record DeliveryReceipt(long id, String code, DeliveryStatus status, String customerName, String driverName,
                              long fromBranchId, String destination, Instant startedAt, Instant completedAt,
                              PaymentMethod paymentMethod, String cancelReason, String returnReason, BigDecimal retainedPayment,
                              boolean countedInProfit, BigDecimal total, BigDecimal costOfGoods, BigDecimal grossProfit,
                              BigDecimal marginPercent, List<ReceiptLine> lines, List<ReceiptProduct> products) {

    /**
     * One package line with its cost and profit.
     *
     * @param packageId   the sales package
     * @param packageName the package's name when sold
     * @param quantity    how many packages
     * @param unitPrice   price per package for this delivery
     * @param lineTotal   {@code unitPrice * quantity}
     * @param costOfGoods the cost of the stock this line used (a product shared by several lines is split by units)
     * @param profit      line total minus cost of goods
     */
    public record ReceiptLine(long packageId, String packageName, long quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                       BigDecimal costOfGoods, BigDecimal profit) {}

    /**
     * Stock of one product taken out for the delivery.
     *
     * @param productId   the product
     * @param productName the product's name
     * @param unit        the product's unit of measure
     * @param quantity    units taken out
     * @param unitCost    weighted average cost per unit at the moment of the sale (4 decimals)
     * @param totalCost   cost of the units taken out, rounded to centavos as booked in the ledger
     */
    public record ReceiptProduct(long productId, String productName, String unit, long quantity, BigDecimal unitCost,
                          BigDecimal totalCost) {}
}
