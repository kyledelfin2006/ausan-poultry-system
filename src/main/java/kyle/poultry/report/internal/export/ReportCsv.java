package kyle.poultry.report.internal.export;

import java.util.Map;
import kyle.poultry.finance.api.model.PaymentMethod;
import kyle.poultry.report.internal.model.InventoryValue;
import kyle.poultry.report.internal.model.Profit;
import kyle.poultry.report.internal.model.Summary;
import kyle.poultry.report.internal.model.ProfitSeries;
import kyle.poultry.report.internal.model.Series;
import kyle.poultry.shared.csv.Csv;

/**
 * Renders the report results as CSV text, with exactly the figures of the JSON reports: flat reports become
 * {@code metric,value} rows, tabular ones get one row per line. Stateless; internal to the report module, used by
 * {@code ReportController}. Quoting and spreadsheet-formula protection come from {@link Csv}.
 */
public final class ReportCsv {
    private ReportCsv() {}

    /**
     * Renders the summary report.
     *
     * @param s the report
     * @return CSV text with a {@code metric,value} header
     */
    public static String summary(Summary s) {
        var csv = Csv.builder().row("metric", "value")
                .row("from", s.period().from()).row("to", s.period().to())
                .row("sales.gross", s.sales().gross()).row("sales.cancelled", s.sales().cancelled())
                .row("sales.net", s.sales().net())
                .row("collections.received", s.collections().received()).row("collections.refunds", s.collections().refunds())
                .row("collections.netCollected", s.collections().netCollected())
                .row("collections.retainedOnCancelledDeliveries", s.collections().retainedOnCancelledDeliveries());
        if (s.stockPurchases() != null) csv.row("stockPurchases", s.stockPurchases());
        if (s.otherExpenses() != null) csv.row("otherExpenses", s.otherExpenses());
        if (s.cashMovement() != null) {
            csv.row("cashMovement.total", s.cashMovement().total());
            // Every payment method is present in the map, so the rows are the same on every export.
            for (Map.Entry<PaymentMethod, java.math.BigDecimal> e : s.cashMovement().byMethod().entrySet())
                csv.row("cashMovement." + e.getKey(), e.getValue());
        }
        return csv.build();
    }

    /**
     * Renders the profit report.
     *
     * @param p the report
     * @return CSV text with a {@code metric,value} header
     */
    public static String profit(Profit p) {
        return Csv.builder().row("metric", "value")
                .row("from", p.period().from()).row("to", p.period().to())
                .row("netSales", p.netSales())
                .row("costOfGoods.delivered", p.costOfGoods().delivered()).row("costOfGoods.reversed", p.costOfGoods().reversed())
                .row("costOfGoods.net", p.costOfGoods().net())
                .row("grossProfit", p.grossProfit()).row("otherExpenses", p.otherExpenses()).row("profit", p.profit())
                .build();
    }

    /**
     * Renders the inventory valuation: one row per product line, then the grand total.
     *
     * @param v the valuation
     * @return CSV text
     */
    public static String inventoryValue(InventoryValue v) {
        var csv = Csv.builder().row("branchId", "productId", "productName", "unit", "quantity", "averageCost", "value");
        v.branches().forEach(b -> b.lines().forEach(l -> csv.row(b.branchId(), l.productId(), l.productName(), l.unit(),
                l.quantity(), l.averageCost(), l.value())));
        // The grand total closes the file so a spreadsheet user does not have to add the column up.
        csv.row(null, null, "TOTAL", null, null, null, v.grandTotal());
        return csv.build();
    }

    /**
     * Renders the summary series: one row per point, then the total.
     *
     * @param s the series
     * @return CSV text
     */
    public static String series(Series s) {
        boolean includeCosts = s.totals().stockPurchases() != null;
        var csv = Csv.builder();
        if (includeCosts)
            csv.row("row", "from", "to", "netSales", "netCollected", "stockPurchases", "otherExpenses", "cashMovement");
        else csv.row("row", "from", "to", "netSales", "netCollected");
        s.points().forEach(p -> {
            if (includeCosts) csv.row("POINT", p.from(), p.to(), p.netSales(), p.netCollected(), p.stockPurchases(),
                    p.otherExpenses(), p.cashMovement());
            else csv.row("POINT", p.from(), p.to(), p.netSales(), p.netCollected());
        });
        var t = s.totals();
        if (includeCosts) csv.row("TOTAL", t.from(), t.to(), t.netSales(), t.netCollected(), t.stockPurchases(), t.otherExpenses(), t.cashMovement());
        else csv.row("TOTAL", t.from(), t.to(), t.netSales(), t.netCollected());
        return csv.build();
    }

    /**
     * Renders the profit series: one row per point, then the total.
     *
     * @param s the series
     * @return CSV text
     */
    public static String profitSeries(ProfitSeries s) {
        var csv = Csv.builder().row("row", "from", "to", "netSales", "costOfGoods", "grossProfit", "otherExpenses", "profit");
        s.points().forEach(p -> csv.row("POINT", p.from(), p.to(), p.netSales(), p.costOfGoods(), p.grossProfit(),
                p.otherExpenses(), p.profit()));
        var t = s.totals();
        csv.row("TOTAL", t.from(), t.to(), t.netSales(), t.costOfGoods(), t.grossProfit(), t.otherExpenses(), t.profit());
        return csv.build();
    }
}
