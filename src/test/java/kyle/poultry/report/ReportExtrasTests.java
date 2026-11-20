package kyle.poultry.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import kyle.poultry.ApiTestClient;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.finance.FinanceApi;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.identity.Actor;
import kyle.poultry.identity.Role;
import kyle.poultry.report.SeriesService.Granularity;
import kyle.poultry.shared.RuleViolation;
import kyle.poultry.shared.SourceRef;
import kyle.poultry.stock.MovementType;
import kyle.poultry.stock.StockApi;
import kyle.poultry.stock.StockApi.StockCommand;
import kyle.poultry.stock.StockApi.StockLine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests (real PostgreSQL) of the report time series, the dashboard and the report CSV downloads. Exact
 * figures come from a scenario in January to March 2023, a window no other test writes to; the dashboard, which always
 * looks at today, is checked as a change in today's figures.
 *
 * <p>The scenario deliberately lies <em>after</em> the 2012, 2019 and 2021 windows of {@code ReportTests}: the retained
 * payment of a cancelled delivery is cumulative from the beginning of time, so a cancellation dated before another test's
 * period end would change that test's exact figure.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReportExtrasTests {

    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() % 1_000_000_000L * 10 + 5);
    /** True once the 2023 scenario has been written; the exact figures need it to exist exactly once. */
    private static boolean built;

    @LocalServerPort int port;
    @Autowired SeriesService series;
    @Autowired ReportService reports;
    @Autowired FinanceApi finance;
    @Autowired StockApi stock;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    ApiTestClient api;
    Actor owner;
    long branchId, chicken;

    /** A fresh branch and chicken product per test, and the owner as the acting user. */
    @BeforeEach
    void freshData() {
        api = new ApiTestClient(port);
        long ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class, TestcontainersConfiguration.OWNER_USERNAME);
        owner = new Actor(ownerId, Role.OWNER);
        String tag = UUID.randomUUID().toString();
        branchId = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "b-" + tag);
        chicken = jdbc.queryForObject("insert into product (name, unit) values (?, 'head') returning id", Long.class, "chicken-" + tag);
    }

    /** Parses an ISO date. */
    static LocalDate d(String iso) { return LocalDate.parse(iso); }

    /** Builds an exact PHP amount. */
    static BigDecimal money(String v) { return new BigDecimal(v); }

    /** Buys chickens for the test branch, which also writes the STOCK_PURCHASE row. */
    void buy(long qty, String unitCost, LocalDate on) {
        stock.record(new StockCommand(chicken, branchId, MovementType.PURCHASE, qty, money(unitCost), null, on, PaymentMethod.CASH), owner);
    }

    /** Books a delivery the way the delivery module does: stock out, sale, cost of goods and the full payment. */
    SourceRef deliver(long qty, String price, PaymentMethod method, LocalDate on) {
        var source = new SourceRef("DELIVERY", IDS.incrementAndGet());
        var movements = stock.deduct(List.of(new StockLine(chicken, qty)), branchId, source, on, owner);
        finance.recordSale(money(price), on, source, owner);
        finance.recordCostOfGoods(movements.stream().map(StockApi.MovementView::totalCost).reduce(BigDecimal.ZERO, BigDecimal::add), on, source, owner);
        finance.recordPayment(money(price), method, on, source, owner);
        return source;
    }

    /**
     * Writes the scenario once: expense 100 on 31 Jan; purchase 1,000 on 1 Feb; sale 5,000 (cost 240) on 10 Feb; expense
     * 50 on 28 Feb; sale 2,000 (cost 100) on 1 Mar, cancelled on 2 Mar and refunded 500 on 3 Mar.
     */
    void buildScenario() {
        if (built) return;
        built = true;
        finance.recordExpense(money("100.00"), PaymentMethod.CASH, d("2023-01-31"), "january", owner);
        buy(100, "10.00", d("2023-02-01"));
        deliver(24, "5000.00", PaymentMethod.GCASH, d("2023-02-10"));
        finance.recordExpense(money("50.00"), PaymentMethod.CASH, d("2023-02-28"), "february", owner);
        var cancelled = deliver(10, "2000.00", PaymentMethod.CASH, d("2023-03-01"));
        stock.reverseAllFor(cancelled, "cancelled", d("2023-03-02"), owner);
        finance.reverseSaleAndCost(cancelled, d("2023-03-02"), owner);
        finance.recordRefund(money("500.00"), PaymentMethod.CASH, d("2023-03-03"), "partial", cancelled, owner);
    }

    // ---- series ---------------------------------------------------------------------------------------------------

    /** Months at the edges are cut to the period, every month has a point and the figures land in the right month. */
    @Test
    void monthSeriesCutsEdgeMonthsAndPlacesEachFigure() {
        buildScenario();

        var s = series.series(d("2023-01-15"), d("2023-03-20"), Granularity.MONTH);

        assertThat(s.points()).hasSize(3);
        var jan = s.points().get(0);
        assertThat(jan.from()).isEqualTo(d("2023-01-15"));   // cut to the period start
        assertThat(jan.to()).isEqualTo(d("2023-01-31"));
        assertThat(jan.otherExpenses()).isEqualTo(money("100.00"));
        assertThat(jan.netSales()).isEqualTo(money("0.00"));
        var feb = s.points().get(1);
        assertThat(feb.from()).isEqualTo(d("2023-02-01"));
        assertThat(feb.to()).isEqualTo(d("2023-02-28"));
        assertThat(feb.netSales()).isEqualTo(money("5000.00"));
        assertThat(feb.netCollected()).isEqualTo(money("5000.00"));
        assertThat(feb.stockPurchases()).isEqualTo(money("1000.00"));
        assertThat(feb.otherExpenses()).isEqualTo(money("50.00"));
        assertThat(feb.cashMovement()).isEqualTo(money("3950.00"));   // 5,000 - 1,000 - 50
        var mar = s.points().get(2);
        assertThat(mar.to()).isEqualTo(d("2023-03-20"));              // cut to the period end
        assertThat(mar.netSales()).isEqualTo(money("0.00"));          // sold 2,000 and cancelled it again
        assertThat(mar.netCollected()).isEqualTo(money("1500.00"));   // 2,000 paid - 500 refunded
        assertThat(mar.cashMovement()).isEqualTo(money("1500.00"));
    }

    /** The totals of a series equal the summary and profit reports for the same period, in every granularity. */
    @Test
    void seriesTotalsEqualTheReports() {
        buildScenario();
        var from = d("2023-01-15");
        var to = d("2023-03-20");
        var summary = reports.summary(from, to);
        var profit = reports.profit(from, to);

        for (var g : Granularity.values()) {
            var s = series.series(from, to, g);
            assertThat(s.totals().netSales()).as(g.name()).isEqualTo(summary.sales().net());
            assertThat(s.totals().netCollected()).isEqualTo(summary.collections().netCollected());
            assertThat(s.totals().stockPurchases()).isEqualTo(summary.stockPurchases());
            assertThat(s.totals().otherExpenses()).isEqualTo(summary.otherExpenses());
            assertThat(s.totals().cashMovement()).isEqualTo(summary.cashMovement().total());
            // The points add up to the total too.
            assertThat(s.points().stream().map(SeriesService.SeriesPoint::cashMovement).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(s.totals().cashMovement());
            var p = series.profitSeries(from, to, g);
            assertThat(p.totals().netSales()).isEqualTo(profit.netSales());
            assertThat(p.totals().costOfGoods()).isEqualTo(profit.costOfGoods().net());
            assertThat(p.totals().grossProfit()).isEqualTo(profit.grossProfit());
            assertThat(p.totals().profit()).isEqualTo(profit.profit());
        }
    }

    /** Profit per month: delivered stock reaches profit once, a cancelled sale cancels out, purchases never count. */
    @Test
    void profitSeriesPerMonth() {
        buildScenario();

        var p = series.profitSeries(d("2023-01-01"), d("2023-03-31"), Granularity.MONTH);

        assertThat(p.points()).extracting(SeriesService.ProfitPoint::profit)
                .containsExactly(money("-100.00"), money("4710.00"), money("0.00"));   // Feb: 5,000 - 240 - 50; the 1,000 purchase is ignored
        assertThat(p.points().get(1).costOfGoods()).isEqualTo(money("240.00"));
        assertThat(p.points().get(2).costOfGoods()).isEqualTo(money("0.00"));          // 100 delivered, 100 reversed
    }

    /** Daily series have a point for every day, zeros included, and the busy day shows the sale. */
    @Test
    void daySeriesHasAPointForEveryDay() {
        buildScenario();

        var s = series.series(d("2023-02-09"), d("2023-02-11"), Granularity.DAY);

        assertThat(s.points()).hasSize(3);
        assertThat(s.points()).extracting(SeriesService.SeriesPoint::from).containsExactly(d("2023-02-09"), d("2023-02-10"), d("2023-02-11"));
        assertThat(s.points()).extracting(SeriesService.SeriesPoint::netSales)
                .containsExactly(money("0.00"), money("5000.00"), money("0.00"));
    }

    /** A month boundary and a year boundary split points exactly at midnight of the business date. */
    @Test
    void periodsAcrossBoundariesSplitOnTheBusinessDate() {
        buildScenario();

        var months = series.series(d("2022-12-31"), d("2023-01-01"), Granularity.MONTH);
        assertThat(months.points()).extracting(SeriesService.SeriesPoint::from).containsExactly(d("2022-12-31"), d("2023-01-01"));
        assertThat(months.points()).extracting(SeriesService.SeriesPoint::to).containsExactly(d("2022-12-31"), d("2023-01-01"));
        // A single day is a single point in either granularity.
        assertThat(series.series(d("2023-01-31"), d("2023-01-31"), Granularity.MONTH).points()).hasSize(1);
        assertThat(series.series(d("2023-01-31"), d("2023-01-31"), Granularity.DAY).points().getFirst().otherExpenses()).isEqualTo(money("100.00"));
        // The leap day belongs to February, and a full leap year is 12 monthly points.
        assertThat(series.series(d("2012-02-28"), d("2012-03-01"), Granularity.DAY).points()).hasSize(3);
        assertThat(series.series(d("2012-01-01"), d("2012-12-31"), Granularity.MONTH).points()).hasSize(12);
    }

    /** The same period rules as the reports: backwards, missing and over 366 days are rule violations. */
    @Test
    void invalidPeriodsAreRejected() {
        assertThatThrownBy(() -> series.series(d("2023-03-01"), d("2023-02-01"), Granularity.DAY)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> series.series(null, d("2023-02-01"), Granularity.DAY)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> series.series(d("2020-01-01"), d("2021-01-01"), Granularity.MONTH)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> series.series(d("2023-01-01"), d("2023-01-02"), null)).isInstanceOf(RuleViolation.class);
        assertThat(series.series(d("2020-01-01"), d("2020-12-31"), Granularity.DAY).points()).hasSize(366);
    }

    // ---- HTTP: series, dashboard, CSV -----------------------------------------------------------------------------------

    /** Series endpoints: both roles read the summary series, only the owner the profit series; bad input is rejected. */
    @Test
    void seriesEndpointsEnforceRolesAndValidation() {
        buildScenario();
        String owner = api.owner(), assistant = api.assistant();
        String q = "?from=2023-01-01&to=2023-03-31";

        var month = api.call("GET", "/api/reports/series" + q + "&granularity=MONTH", assistant, null);
        assertThat(month.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(month.body(), "$.points.length()")).isEqualTo(3);
        assertThat((String) JsonPath.read(month.body(), "$.granularity")).isEqualTo("MONTH");
        // The default granularity is DAY: 31 + 28 + 31 points.
        var day = api.call("GET", "/api/reports/series" + q, assistant, null);
        assertThat((Integer) JsonPath.read(day.body(), "$.points.length()")).isEqualTo(90);

        assertThat(api.call("GET", "/api/reports/profit-series" + q, assistant, null).statusCode()).isEqualTo(403);
        var profit = api.call("GET", "/api/reports/profit-series" + q + "&granularity=MONTH", owner, null);
        assertThat(profit.statusCode()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(profit.body(), "$.points[1].profit")).doubleValue()).isEqualTo(4710.00);

        assertThat(api.call("GET", "/api/reports/series" + q + "&granularity=WEEK", assistant, null).statusCode()).isEqualTo(400);
        assertThat(api.call("GET", "/api/reports/series?from=2023-03-01&to=2023-01-01", assistant, null).statusCode()).isEqualTo(422);
        assertThat(api.call("GET", "/api/reports/series?from=2023-01-01", assistant, null).statusCode()).isEqualTo(400);
        assertThat(api.call("GET", "/api/reports/series" + q, null, null).statusCode()).isEqualTo(401);
    }

    /** Today's dashboard changes by exactly an expense recorded now; assistants never see profit. */
    @Test
    void dashboardFollowsTodaysLedgerAndHidesProfitFromAssistants() {
        String ownerToken = api.owner(), assistant = api.assistant();
        var before = api.call("GET", "/api/reports/dashboard", ownerToken, null);
        assertThat(before.statusCode()).isEqualTo(200);
        String today = LocalDate.now(clock).toString();
        assertThat((String) JsonPath.read(before.body(), "$.asOf")).isEqualTo(today);
        assertThat((String) JsonPath.read(before.body(), "$.today.from")).isEqualTo(today);
        assertThat((String) JsonPath.read(before.body(), "$.monthToDate.from")).isEqualTo(LocalDate.now(clock).withDayOfMonth(1).toString());

        var expense = api.call("POST", "/api/finance/expenses", ownerToken,
                "{\"amount\":123.45,\"method\":\"CASH\",\"occurredOn\":\"" + today + "\",\"description\":\"dashboard test\"}");
        assertThat(expense.statusCode()).isEqualTo(201);

        var after = api.call("GET", "/api/reports/dashboard", ownerToken, null);
        for (String period : new String[] {"today", "monthToDate"}) {
            double was = ((Number) JsonPath.read(before.body(), "$." + period + ".otherExpenses")).doubleValue();
            double now = ((Number) JsonPath.read(after.body(), "$." + period + ".otherExpenses")).doubleValue();
            assertThat(now - was).as(period).isCloseTo(123.45, org.assertj.core.data.Offset.offset(0.001));
            assertThat((Object) JsonPath.read(after.body(), "$." + period + ".profit")).as(period + " profit for owner").isNotNull();
        }

        var forAssistant = api.call("GET", "/api/reports/dashboard", assistant, null);
        assertThat(forAssistant.statusCode()).isEqualTo(200);
        assertThat((Object) JsonPath.read(forAssistant.body(), "$.today.profit")).isNull();
        assertThat((Object) JsonPath.read(forAssistant.body(), "$.monthToDate.profit")).isNull();
        assertThat(api.call("GET", "/api/reports/dashboard", null, null).statusCode()).isEqualTo(401);
    }

    /** The low-stock count on the dashboard rises by one when a product with a level runs low. */
    @Test
    void dashboardCountsLowStockProducts() {
        String ownerToken = api.owner();
        int before = JsonPath.read(api.call("GET", "/api/reports/dashboard", ownerToken, null).body(), "$.lowStockProducts");
        try {
            assertThat(api.call("PUT", "/api/stock/reorder-levels/" + chicken, ownerToken, "{\"level\":5}").statusCode()).isEqualTo(200);

            int after = JsonPath.read(api.call("GET", "/api/reports/dashboard", ownerToken, null).body(), "$.lowStockProducts");

            assertThat(after).isEqualTo(before + 1);
        } finally {
            // Leave no flagged product behind for the other tests sharing this database.
            jdbc.update("delete from stock_reorder_level where product_id = ?", chicken);
        }
    }

    /** Every CSV export carries the report's figures, downloads as an attachment and obeys the same roles. */
    @Test
    void csvDownloadsMatchTheReports() {
        buildScenario();
        String owner = api.owner(), assistant = api.assistant();
        String q = "?from=2023-01-01&to=2023-03-31";

        var summary = api.call("GET", "/api/reports/summary.csv" + q, assistant, null);
        assertThat(summary.statusCode()).isEqualTo(200);
        assertThat(summary.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(summary.headers().firstValue("Content-Disposition").orElse("")).contains("attachment").contains("summary-2023-01-01-to-2023-03-31.csv");
        assertThat(summary.body()).startsWith("﻿metric,value\r\n")
                .contains("sales.gross,7000.00\r\n").contains("sales.net,5000.00\r\n").contains("stockPurchases,1000.00\r\n")
                .contains("cashMovement.total,5350.00\r\n").contains("cashMovement.GCASH,5000.00\r\n");

        assertThat(api.call("GET", "/api/reports/profit.csv" + q, assistant, null).statusCode()).isEqualTo(403);
        var profit = api.call("GET", "/api/reports/profit.csv" + q, owner, null);
        assertThat(profit.statusCode()).isEqualTo(200);
        assertThat(profit.body()).contains("netSales,5000.00\r\n").contains("costOfGoods.net,240.00\r\n").contains("profit,4610.00\r\n");

        var series = api.call("GET", "/api/reports/series.csv" + q + "&granularity=MONTH", assistant, null);
        assertThat(series.body()).startsWith("﻿row,from,to,netSales,netCollected,stockPurchases,otherExpenses,cashMovement\r\n")
                .contains("POINT,2023-02-01,2023-02-28,5000.00,5000.00,1000.00,50.00,3950.00\r\n")
                .contains("TOTAL,2023-01-01,2023-03-31,5000.00,6500.00,1000.00,150.00,5350.00\r\n");
        assertThat(api.call("GET", "/api/reports/profit-series.csv" + q + "&granularity=MONTH", assistant, null).statusCode()).isEqualTo(403);
        var profitSeries = api.call("GET", "/api/reports/profit-series.csv" + q + "&granularity=MONTH", owner, null);
        assertThat(profitSeries.body()).contains("POINT,2023-02-01,2023-02-28,5000.00,240.00,4760.00,50.00,4710.00\r\n");

        var inventory = api.call("GET", "/api/reports/inventory-value.csv", assistant, null);
        assertThat(inventory.statusCode()).isEqualTo(200);
        assertThat(inventory.body()).startsWith("﻿branchId,productId,productName,unit,quantity,averageCost,value\r\n").contains(",TOTAL,");

        assertThat(api.call("GET", "/api/reports/summary.csv?from=2023-03-01&to=2023-01-01", assistant, null).statusCode()).isEqualTo(422);
        assertThat(api.call("GET", "/api/reports/summary.csv" + q, null, null).statusCode()).isEqualTo(401);
    }
}
