package kyle.poultry.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import kyle.poultry.finance.EntryType;
import kyle.poultry.finance.FinanceApi;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.shared.RuleViolation;
import kyle.poultry.stock.StockApi;
import kyle.poultry.stock.StockApi.BalanceView;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the period financial reports and the inventory valuation. It is the only service of the report module and
 * is used by {@link ReportController}; it talks to other modules solely through {@link FinanceApi} and
 * {@link StockApi} and never writes anything.
 *
 * <p>All figures are PHP with scale 2, for an inclusive period {@code [from, to]} of Manila business dates:
 * <ul>
 *   <li><b>Net sales</b> = SALE - SALE_REVERSAL.</li>
 *   <li><b>Net collected</b> = PAYMENT_IN - REFUND.</li>
 *   <li><b>Retained on cancelled deliveries</b>: as of the period end, for every source that has a SALE_REVERSAL
 *       dated on or before {@code to}, all PAYMENT_IN minus all REFUND dated on or before {@code to} (cumulative,
 *       not just inside the period). It is shown separately and never added to sales.</li>
 *   <li><b>Cash movement</b> = PAYMENT_IN - REFUND - STOCK_PURCHASE - EXPENSE, also split by payment method.</li>
 *   <li><b>Profit</b> = net sales - (COST_OF_GOODS - COGS_REVERSAL) - EXPENSE. STOCK_PURCHASE is deliberately
 *       excluded: it is cash out, and its cost reaches profit only when the stock is delivered (as
 *       COST_OF_GOODS), so counting it too would charge the same stock twice.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)   // every report is a pure read; read-only lets the database skip write bookkeeping
class ReportService {
    /** Longest reportable period in days; one full leap year. */
    static final int MAX_DAYS = 366;

    private final FinanceApi finance;
    private final StockApi stock;

    /**
     * Creates the service.
     *
     * @param finance the finance module's public API, source of all ledger aggregates
     * @param stock   the stock module's public API, source of balances and average costs
     */
    ReportService(FinanceApi finance, StockApi stock) { this.finance = finance; this.stock = stock; }

    /**
     * The inclusive date range a report covers.
     *
     * @param from first business date included
     * @param to   last business date included
     */
    record Period(LocalDate from, LocalDate to) {}

    /**
     * Revenue figures for a period.
     *
     * @param gross     sum of SALE entries
     * @param cancelled sum of SALE_REVERSAL entries (sales undone by cancellations)
     * @param net       gross minus cancelled
     */
    record Sales(BigDecimal gross, BigDecimal cancelled, BigDecimal net) {}

    /**
     * Money received from and returned to customers.
     *
     * @param received                      sum of PAYMENT_IN entries in the period
     * @param refunds                       sum of REFUND entries in the period
     * @param netCollected                  received minus refunds
     * @param retainedOnCancelledDeliveries money held, as of the period end, for cancelled sources and not yet
     *                                      refunded; reported on its own and never part of sales
     */
    record Collections(BigDecimal received, BigDecimal refunds, BigDecimal netCollected,
                       BigDecimal retainedOnCancelledDeliveries) {}

    /**
     * Net cash flow of a period.
     *
     * @param total    payments received - refunds - stock purchases - other expenses
     * @param byMethod the same net figure per payment method; every method is present
     */
    record CashMovement(BigDecimal total, Map<PaymentMethod, BigDecimal> byMethod) {}

    /**
     * The summary report. Assistants receive sales and collection figures; cost and cash-movement components are null.
     *
     * @param period         the period covered
     * @param sales          gross, cancelled and net sales
     * @param collections    payments, refunds and retained payments
     * @param stockPurchases cash paid to suppliers (sum of STOCK_PURCHASE); not a profit figure; null for assistants
     * @param otherExpenses  sum of EXPENSE entries; null for assistants
     * @param cashMovement   net cash flow with its per-method breakdown; null for assistants
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Summary(Period period, Sales sales, Collections collections, BigDecimal stockPurchases,
                   BigDecimal otherExpenses, CashMovement cashMovement) {}

    /**
     * Cost of the stock that was delivered in a period.
     *
     * @param delivered sum of COST_OF_GOODS entries
     * @param reversed  sum of COGS_REVERSAL entries (cost undone by cancellations)
     * @param net       delivered minus reversed
     */
    record CostOfGoods(BigDecimal delivered, BigDecimal reversed, BigDecimal net) {}

    /**
     * The profit report served to the owner only.
     *
     * @param period        the period covered
     * @param netSales      SALE - SALE_REVERSAL
     * @param costOfGoods   cost of the stock delivered
     * @param grossProfit   net sales minus net cost of goods
     * @param otherExpenses sum of EXPENSE entries
     * @param profit        gross profit minus other expenses; stock purchases are never included
     */
    record Profit(Period period, BigDecimal netSales, CostOfGoods costOfGoods, BigDecimal grossProfit,
                  BigDecimal otherExpenses, BigDecimal profit) {}

    /**
     * One product's stock value inside a branch.
     *
     * @param productId   the product
     * @param productName display name of the product
     * @param unit        unit of measure (e.g. head, pc, bag)
     * @param quantity    units on hand
     * @param averageCost weighted average cost per unit (4 decimals)
     * @param value       quantity x average cost, rounded to centavos
     */
    record InventoryLine(long productId, String productName, String unit, long quantity,
                         BigDecimal averageCost, BigDecimal value) {}

    /**
     * Stock value of one branch.
     *
     * @param branchId the branch (names belong to the catalog module, which this module does not use)
     * @param lines    its products with stock, ordered by product name
     * @param total    sum of the line values
     */
    record BranchInventory(long branchId, List<InventoryLine> lines, BigDecimal total) {}

    /**
     * Inventory valuation across all branches, as of now.
     *
     * @param branches   branches that hold stock, ordered by branch id
     * @param grandTotal sum of the branch totals
     */
    record InventoryValue(List<BranchInventory> branches, BigDecimal grandTotal) {}

    /**
     * Builds the summary report: sales, collections, purchases, expenses and cash movement.
     *
     * @param from first business date included
     * @param to   last business date included
     * @return the summary figures; entry types with no entries report 0.00
     * @throws RuleViolation if a date is missing, {@code from} is after {@code to}, or the period exceeds 366 days
     */
    Summary summary(LocalDate from, LocalDate to) {
        // Validate first so a bad request never reaches the database.
        requirePeriod(from, to);
        // One grouped query gives every per-type total at once.
        Map<EntryType, BigDecimal> t = finance.totalsByType(from, to);
        BigDecimal received = t.get(EntryType.PAYMENT_IN), refunds = t.get(EntryType.REFUND);
        BigDecimal purchases = t.get(EntryType.STOCK_PURCHASE), expenses = t.get(EntryType.EXPENSE);
        BigDecimal gross = t.get(EntryType.SALE), cancelled = t.get(EntryType.SALE_REVERSAL);
        // Cash is only what actually moved: sales and costs are not cash, so they are absent here.
        BigDecimal cash = received.subtract(refunds).subtract(purchases).subtract(expenses);
        return new Summary(new Period(from, to),
                new Sales(gross, cancelled, gross.subtract(cancelled)),
                // Retained money is measured as of the period end and kept apart from sales.
                new Collections(received, refunds, received.subtract(refunds), finance.retainedOnCancelledSources(to)),
                purchases, expenses,
                new CashMovement(cash, finance.cashByMethod(from, to)));
    }

    /**
     * Builds the profit report. Stock purchases are not part of it: their cost enters profit only when the stock is
     * delivered, as cost of goods.
     *
     * @param from first business date included
     * @param to   last business date included
     * @return net sales, net cost of goods, gross profit, expenses and profit
     * @throws RuleViolation if a date is missing, {@code from} is after {@code to}, or the period exceeds 366 days
     */
    Profit profit(LocalDate from, LocalDate to) {
        // Validate first so a bad request never reaches the database.
        requirePeriod(from, to);
        Map<EntryType, BigDecimal> t = finance.totalsByType(from, to);
        // Cancelled deliveries drop out of both revenue and cost through their reversal rows.
        BigDecimal netSales = t.get(EntryType.SALE).subtract(t.get(EntryType.SALE_REVERSAL));
        BigDecimal cogs = t.get(EntryType.COST_OF_GOODS).subtract(t.get(EntryType.COGS_REVERSAL));
        BigDecimal expenses = t.get(EntryType.EXPENSE);
        BigDecimal grossProfit = netSales.subtract(cogs);
        // STOCK_PURCHASE is intentionally not read here: subtracting it as well would double count delivered stock.
        return new Profit(new Period(from, to), netSales,
                new CostOfGoods(t.get(EntryType.COST_OF_GOODS), t.get(EntryType.COGS_REVERSAL), cogs),
                grossProfit, expenses, grossProfit.subtract(expenses));
    }

    /**
     * Values the stock on hand now: quantity x weighted average cost per line, rounded to centavos, then summed per
     * branch and overall. Products with zero quantity are left out.
     *
     * @return the valuation grouped by branch
     */
    InventoryValue inventoryValue() {
        // TreeMap keeps branches in id order so the output is stable between calls.
        Map<Long, List<InventoryLine>> byBranch = new TreeMap<>();
        for (BalanceView b : stock.balances(null)) {   // null = all branches
            // Nothing on hand means nothing to value.
            if (b.quantity() == 0) continue;
            // Round each line to centavos first, so the totals add up exactly to the lines a reader sees.
            BigDecimal value = b.averageCost().multiply(BigDecimal.valueOf(b.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            byBranch.computeIfAbsent(b.branchId(), k -> new ArrayList<>())
                    .add(new InventoryLine(b.productId(), b.productName(), b.unit(), b.quantity(),
                            b.averageCost(), value));
        }
        List<BranchInventory> branches = new ArrayList<>();
        BigDecimal grand = BigDecimal.ZERO.setScale(2);
        for (var e : byBranch.entrySet()) {
            // Alphabetical product order within a branch.
            e.getValue().sort(Comparator.comparing(InventoryLine::productName));
            BigDecimal total = e.getValue().stream().map(InventoryLine::value)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            branches.add(new BranchInventory(e.getKey(), e.getValue(), total));
            grand = grand.add(total);
        }
        return new InventoryValue(branches, grand);
    }

    /**
     * Checks that a report period is present, in order and no longer than {@link #MAX_DAYS} days.
     *
     * @param from first date of the period
     * @param to   last date of the period
     * @throws RuleViolation if a date is missing, {@code from} is after {@code to}, or the period exceeds 366 days
     */
    static void requirePeriod(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new RuleViolation("from and to dates are required");
        if (to.isBefore(from)) throw new RuleViolation("The start date must not be after the end date");
        // Both ends are inclusive, hence +1: 2020-01-01..2020-12-31 is 366 days.
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS)
            throw new RuleViolation("A report period cannot exceed " + MAX_DAYS + " days");
    }
}
