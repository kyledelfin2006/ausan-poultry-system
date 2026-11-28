package kyle.poultry.report.internal.service;

import kyle.poultry.report.internal.model.BranchInventory;
import kyle.poultry.report.internal.model.CashMovement;
import kyle.poultry.report.internal.model.Collections;
import kyle.poultry.report.internal.model.CostOfGoods;
import kyle.poultry.report.internal.model.InventoryLine;
import kyle.poultry.report.internal.model.InventoryValue;
import kyle.poultry.report.internal.model.Period;
import kyle.poultry.report.internal.model.Profit;
import kyle.poultry.report.internal.model.Sales;
import kyle.poultry.report.internal.model.Summary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import kyle.poultry.finance.api.model.EntryType;
import kyle.poultry.finance.api.FinanceApi;
import kyle.poultry.shared.error.RuleViolation;
import kyle.poultry.stock.api.StockApi;
import kyle.poultry.stock.api.model.BalanceView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the period financial reports and the inventory valuation. It is the only service of the report module and
 * is used by {@code ReportController}; it talks to other modules solely through {@link FinanceApi} and
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
public class ReportService {
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
     * Builds the summary report: sales, collections, purchases, expenses and cash movement.
     *
     * @param from first business date included
     * @param to   last business date included
     * @return the summary figures; entry types with no entries report 0.00
     * @throws RuleViolation if a date is missing, {@code from} is after {@code to}, or the period exceeds 366 days
     */
    public Summary summary(LocalDate from, LocalDate to) {
        // Validate first so a bad request never reaches the database.
        requirePeriod(from, to);
        return summary(from, to, finance.totalsByType(from, to), finance.retainedOnCancelledSources(to));
    }

    /**
     * Reads the per-type totals of a period with one grouped query, so a caller that needs both the summary and the
     * profit of the same period (the dashboard) queries once and shares the result.
     *
     * @param from first business date included
     * @param to   last business date included
     * @return the total of every entry type, 0.00 for types with no entries
     * @throws RuleViolation if a date is missing, {@code from} is after {@code to}, or the period exceeds 366 days
     */
    Map<EntryType, BigDecimal> totals(LocalDate from, LocalDate to) {
        requirePeriod(from, to);
        return finance.totalsByType(from, to);
    }

    /**
     * Money still held for cancelled sources as of a date. It reads the whole ledger up to that date, so callers that
     * need it for several periods ending on the same day should call it once.
     *
     * @param asOf the date the amount is measured at
     * @return the retained amount
     */
    BigDecimal retainedOnCancelled(LocalDate asOf) {
        return finance.retainedOnCancelledSources(asOf);
    }

    /**
     * Builds the summary from figures already read, so no further ledger query is needed for the totals.
     *
     * @param from     first business date included (already validated)
     * @param to       last business date included
     * @param t        the period's totals, from {@link #totals}
     * @param retained the retained amount as of {@code to}, from {@link #retainedOnCancelled}
     * @return the summary
     */
    public Summary summary(LocalDate from, LocalDate to, Map<EntryType, BigDecimal> t, BigDecimal retained) {
        BigDecimal received = t.get(EntryType.PAYMENT_IN), refunds = t.get(EntryType.REFUND);
        BigDecimal purchases = t.get(EntryType.STOCK_PURCHASE), expenses = t.get(EntryType.EXPENSE);
        BigDecimal gross = t.get(EntryType.SALE), cancelled = t.get(EntryType.SALE_REVERSAL);
        // Cash is only what actually moved: sales and costs are not cash, so they are absent here.
        BigDecimal cash = received.subtract(refunds).subtract(purchases).subtract(expenses);
        return new Summary(new Period(from, to),
                new Sales(gross, cancelled, gross.subtract(cancelled)),
                // Retained money is measured as of the period end and kept apart from sales.
                new Collections(received, refunds, received.subtract(refunds), retained),
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
    public Profit profit(LocalDate from, LocalDate to) {
        // Validate first so a bad request never reaches the database.
        requirePeriod(from, to);
        return profit(from, to, finance.totalsByType(from, to));
    }

    /**
     * Builds the profit report from totals already read.
     *
     * @param from first business date included (already validated)
     * @param to   last business date included
     * @param t    the period's totals, from {@link #totals}
     * @return net sales, net cost of goods, gross profit, expenses and profit
     */
    public Profit profit(LocalDate from, LocalDate to, Map<EntryType, BigDecimal> t) {
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
    public InventoryValue inventoryValue() {
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
