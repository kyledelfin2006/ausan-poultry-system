package kyle.poultry.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.finance.api.FinanceApi;
import kyle.poultry.finance.api.model.PaymentMethod;
import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.identity.api.model.Role;
import kyle.poultry.shared.error.RuleViolation;
import kyle.poultry.shared.reference.SourceRef;
import kyle.poultry.stock.api.model.MovementType;
import kyle.poultry.stock.api.StockApi;
import kyle.poultry.stock.api.StockApi.StockCommand;
import kyle.poultry.stock.api.StockApi.StockLine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests of the report module against a real PostgreSQL (Testcontainers), covering every report figure,
 * period boundaries, role rules and validation. The database is shared with other test classes, so the
 * exact-number scenarios live in historic windows (2012, 2019, 2021) that no other test writes to; the delivery test
 * works on deltas of today's figures instead.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReportTests {

    /** Source of unique fake delivery ids so scenarios never share a ledger source. */
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() % 1_000_000_000L * 10);

    @LocalServerPort int port;
    @Autowired ReportService reports;
    @Autowired FinanceApi finance;
    @Autowired StockApi stock;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    final HttpClient http = HttpClient.newHttpClient();
    Actor owner;
    long branchId, chicken, cage;

    /** Creates a fresh branch and two products per test and looks up the owner, so tests do not share stock rows. */
    @BeforeEach
    void freshData() {
        // The bootstrap owner exists in every test database; its id stands in as the acting user.
        long ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class,
                TestcontainersConfiguration.OWNER_USERNAME);
        owner = new Actor(ownerId, Role.OWNER);
        // A random tag keeps names unique across tests that share the database.
        String tag = UUID.randomUUID().toString();
        branchId = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "b-" + tag);
        chicken = jdbc.queryForObject("insert into product (name, unit) values (?, 'head') returning id",
                Long.class, "chicken-" + tag);
        cage = jdbc.queryForObject("insert into product (name, unit) values (?, 'pc') returning id",
                Long.class, "cage-" + tag);
    }

    /** Parses an ISO date such as {@code 2019-05-01}. */
    static LocalDate d(String iso) { return LocalDate.parse(iso); }

    /** Builds an exact PHP amount from text, avoiding binary floating point. */
    static BigDecimal money(String v) { return new BigDecimal(v); }

    /**
     * Buys stock through the stock module, which also writes the STOCK_PURCHASE cash-out to the ledger.
     *
     * @param product  product bought
     * @param qty      units bought
     * @param unitCost price per unit, as text
     * @param method   how the supplier was paid
     * @param on       business date of the purchase
     */
    void buy(long product, long qty, String unitCost, PaymentMethod method, LocalDate on) {
        // The stock module writes the matching STOCK_PURCHASE ledger row in the same transaction.
        stock.record(new StockCommand(product, branchId, MovementType.PURCHASE, qty, money(unitCost), null, on, method),
                owner);
    }

    /**
     * Mimics what a delivery does: stock out at average cost, then sale, cost of goods and the full payment.
     *
     * @param chickens chickens taken out of stock
     * @param price    sale price and payment amount, as text
     * @param method   payment method of the customer's payment
     * @param on       business date of every entry written
     * @return the unique fake delivery source the entries are linked to
     */
    SourceRef deliver(long chickens, String price, PaymentMethod method, LocalDate on) {
        // A unique source id keeps this delivery's entries apart from every other test's.
        var source = new SourceRef("DELIVERY", IDS.incrementAndGet());
        // Stock out first: it fixes the cost at the average cost of that moment.
        var movements = stock.deduct(java.util.List.of(new StockLine(chicken, chickens)), branchId, source, on, owner);
        // Revenue, cost of the delivered stock and the customer's payment, as the delivery module would book them.
        finance.recordSale(money(price), on, source, owner);
        BigDecimal cost = movements.stream().map(StockApi.MovementView::totalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        finance.recordCostOfGoods(cost, on, source, owner);
        finance.recordPayment(money(price), method, on, source, owner);
        return source;
    }

    /**
     * Cancels a delivery the way the delivery module does: stock returns and sale and cost are reversed; the payment
     * stays until refunded.
     *
     * @param source the delivery to cancel
     * @param on     business date of the cancellation
     */
    void cancel(SourceRef source, LocalDate on) {
        stock.reverseAllFor(source, "cancelled", on, owner);
        finance.reverseSaleAndCost(source, on, owner);
    }

    // ---- the full scenario ---------------------------------------------------------------------------------------

    /** True once the 2019 scenario has been written; it must exist exactly once because the figures are exact. */
    static boolean scenarioBuilt;

    /**
     * Writes the shared scenario for period 2019-05-01..2019-05-31 once per class: entries on 04-30 and 06-01 must stay
     * out of the period and those on 05-01 and 05-31 must be in. Later calls do nothing.
     */
    void buildScenario() {
        if (scenarioBuilt) return;
        scenarioBuilt = true;
        // Day before the period: must be excluded.
        finance.recordExpense(money("999.00"), PaymentMethod.CASH, d("2019-04-30"), "day before", owner);
        // Purchase of 1,000 on the first day of the period: must be included.
        buy(chicken, 100, "10.00", PaymentMethod.CASH, d("2019-05-01"));
        // Delivery with sale 70,000 and cost 240 (24 x 10), paid by GCASH.
        deliver(24, "70000.00", PaymentMethod.GCASH, d("2019-05-10"));
        // Delivery with sale 40,000 and cost 100 (10 x 10), paid in cash, then cancelled.
        var cancelled = deliver(10, "40000.00", PaymentMethod.CASH, d("2019-05-12"));
        cancel(cancelled, d("2019-05-15"));
        // Partial refund of the cancelled delivery's payment; 25,000 stays retained.
        finance.recordRefund(money("15000.00"), PaymentMethod.CASH, d("2019-05-20"), "partial", cancelled, owner);
        // Expense of 500 on the last day of the period: must be included.
        finance.recordExpense(money("500.00"), PaymentMethod.GCASH, d("2019-05-31"), "feed hauling", owner);
        // Day after the period: must be excluded.
        finance.recordExpense(money("888.00"), PaymentMethod.CASH, d("2019-06-01"), "day after", owner);
    }

    /** Every summary figure for the scenario, including the per-method cash split adding up to the total. */
    @Test
    void summaryReportsEveryFigureForTheScenario() {
        buildScenario();

        var s = reports.summary(d("2019-05-01"), d("2019-05-31"));

        assertThat(s.sales().gross()).isEqualTo(money("110000.00"));
        assertThat(s.sales().cancelled()).isEqualTo(money("40000.00"));
        assertThat(s.sales().net()).isEqualTo(money("70000.00"));
        assertThat(s.collections().received()).isEqualTo(money("110000.00"));
        assertThat(s.collections().refunds()).isEqualTo(money("15000.00"));
        assertThat(s.collections().netCollected()).isEqualTo(money("95000.00"));
        // 40,000 paid - 15,000 refunded on the cancelled delivery.
        assertThat(s.collections().retainedOnCancelledDeliveries()).isEqualTo(money("25000.00"));
        assertThat(s.stockPurchases()).isEqualTo(money("1000.00"));
        assertThat(s.otherExpenses()).isEqualTo(money("500.00"));
        assertThat(s.cashMovement().total()).isEqualTo(money("93500.00"));   // 110,000 - 15,000 - 1,000 - 500
        assertThat(s.cashMovement().byMethod()).containsOnlyKeys(PaymentMethod.values())
                .containsEntry(PaymentMethod.CASH, money("24000.00"))        // 40,000 - 15,000 - 1,000
                .containsEntry(PaymentMethod.GCASH, money("69500.00"))       // 70,000 - 500
                .containsEntry(PaymentMethod.BANK_TRANSFER, money("0.00"))
                .containsEntry(PaymentMethod.OTHER, money("0.00"));
        assertThat(s.cashMovement().byMethod().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualTo(s.cashMovement().total());
    }

    /** Profit uses net sales and net cost of goods and ignores the 1,000 stock purchase. */
    @Test
    void profitUsesNetSalesAndNetCostOfGoodsAndIgnoresStockPurchases() {
        buildScenario();

        var p = reports.profit(d("2019-05-01"), d("2019-05-31"));

        assertThat(p.netSales()).isEqualTo(money("70000.00"));
        assertThat(p.costOfGoods().delivered()).isEqualTo(money("340.00"));   // 240 + 100
        assertThat(p.costOfGoods().reversed()).isEqualTo(money("100.00"));
        assertThat(p.costOfGoods().net()).isEqualTo(money("240.00"));
        assertThat(p.grossProfit()).isEqualTo(money("69760.00"));
        assertThat(p.otherExpenses()).isEqualTo(money("500.00"));
        assertThat(p.profit()).isEqualTo(money("69260.00"));                  // the 1,000 purchase is not in here
    }

    /** Retained payments depend on the period end: before the cancel, between cancel and refund, and after. */
    @Test
    void retainedPaymentsAreMeasuredAsOfThePeriodEnd() {
        buildScenario();

        // Cancelled on 05-15, refunded on 05-20: nothing is retained before the cancel,
        // the whole payment is retained between the two dates.
        assertThat(reports.summary(d("2019-05-01"), d("2019-05-14")).collections().retainedOnCancelledDeliveries())
                .isEqualTo(money("0.00"));
        assertThat(reports.summary(d("2019-05-01"), d("2019-05-17")).collections().retainedOnCancelledDeliveries())
                .isEqualTo(money("40000.00"));
        // The measure is cumulative: a period that starts after the cancellation still sees the money held.
        assertThat(reports.summary(d("2019-05-25"), d("2019-05-31")).collections().retainedOnCancelledDeliveries())
                .isEqualTo(money("25000.00"));
        // It never leaks into sales.
        assertThat(reports.summary(d("2019-05-25"), d("2019-05-31")).sales().gross()).isEqualTo(money("0.00"));
    }

    /** Entries on {@code from} and {@code to} count; the days just outside do not. */
    @Test
    void boundaryDatesAreInclusiveAndTheDaysAroundThemAreNot() {
        buildScenario();

        // A narrower period drops the 05-01 purchase and the 05-31 expense.
        var inner = reports.summary(d("2019-05-02"), d("2019-05-30"));
        assertThat(inner.stockPurchases()).isEqualTo(money("0.00"));
        assertThat(inner.otherExpenses()).isEqualTo(money("0.00"));

        var single = reports.summary(d("2019-05-31"), d("2019-05-31"));
        assertThat(single.otherExpenses()).isEqualTo(money("500.00"));
        assertThat(reports.summary(d("2019-04-30"), d("2019-04-30")).otherExpenses()).isEqualTo(money("999.00"));
        assertThat(reports.summary(d("2019-06-01"), d("2019-06-01")).otherExpenses()).isEqualTo(money("888.00"));
        assertThat(reports.summary(d("2019-05-01"), d("2019-05-01")).stockPurchases()).isEqualTo(money("1000.00"));
    }

    /** Buying stock moves cash but not profit; only delivered stock reaches profit, once. */
    @Test
    void aPurchaseNeverDoubleCountsInProfit() {
        buy(chicken, 100, "10.00", PaymentMethod.BANK_TRANSFER, d("2021-03-01"));
        var day1 = d("2021-03-01");

        // Stock bought but nothing delivered: cash is out, profit is untouched.
        assertThat(reports.summary(day1, day1).stockPurchases()).isEqualTo(money("1000.00"));
        assertThat(reports.summary(day1, day1).cashMovement().total()).isEqualTo(money("-1000.00"));
        assertThat(reports.profit(day1, day1).profit()).isEqualTo(money("0.00"));

        // 24 chickens delivered: only their 240 cost reaches profit, not the whole 1,000 bought.
        deliver(24, "500.00", PaymentMethod.CASH, d("2021-03-02"));
        var p = reports.profit(d("2021-03-01"), d("2021-03-02"));
        assertThat(p.costOfGoods().net()).isEqualTo(money("240.00"));
        assertThat(p.profit()).isEqualTo(money("260.00"));                                    // 500 - 240
        assertThat(reports.summary(d("2021-03-01"), d("2021-03-02")).cashMovement().total())  // 500 - 1,000
                .isEqualTo(money("-500.00"));
    }

    /** A period with no entries reports 0.00 for every figure, never null. */
    @Test
    void anEmptyPeriodReportsZeroEverywhere() {
        var s = reports.summary(d("2012-01-01"), d("2012-01-31"));
        var zero = money("0.00");

        assertThat(s.sales()).isEqualTo(new ReportService.Sales(zero, zero, zero));
        assertThat(s.collections()).isEqualTo(new ReportService.Collections(zero, zero, zero, zero));
        assertThat(s.stockPurchases()).isEqualTo(zero);
        assertThat(s.otherExpenses()).isEqualTo(zero);
        assertThat(s.cashMovement().total()).isEqualTo(zero);
        assertThat(s.cashMovement().byMethod()).hasSize(PaymentMethod.values().length)
                .allSatisfy((m, v) -> assertThat(v).isEqualTo(zero));
        var p = reports.profit(d("2012-01-01"), d("2012-01-31"));
        assertThat(p.netSales()).isEqualTo(zero);
        assertThat(p.costOfGoods()).isEqualTo(new ReportService.CostOfGoods(zero, zero, zero));
        assertThat(p.profit()).isEqualTo(zero);
    }

    /** Backwards, missing and over-366-day periods are rule violations. */
    @Test
    void invalidPeriodsAreRejected() {
        assertThatThrownBy(() -> reports.summary(d("2019-05-31"), d("2019-05-01"))).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> reports.profit(d("2019-05-31"), d("2019-05-01"))).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> reports.summary(null, d("2019-05-01"))).isInstanceOf(RuleViolation.class);
        // 366 days is the longest allowed period (2020 is a leap year); 367 is not.
        assertThat(reports.summary(d("2020-01-01"), d("2020-12-31")).period().to()).isEqualTo(d("2020-12-31"));
        assertThatThrownBy(() -> reports.summary(d("2020-01-01"), d("2021-01-01"))).isInstanceOf(RuleViolation.class);
    }

    // ---- inventory -----------------------------------------------------------------------------------------------

    /** Inventory value is quantity x weighted average cost per line, branch total and grand total. */
    @Test
    void inventoryValueIsQuantityTimesWeightedAverageCostPerBranchAndInTotal() {
        buy(chicken, 100, "10.00", PaymentMethod.CASH, d("2021-06-01"));
        buy(chicken, 100, "20.00", PaymentMethod.CASH, d("2021-06-02"));   // average now 15.00
        buy(cage, 10, "50.00", PaymentMethod.CASH, d("2021-06-02"));

        var inv = reports.inventoryValue();

        var branch = inv.branches().stream().filter(b -> b.branchId() == branchId).findFirst().orElseThrow();
        assertThat(branch.lines()).hasSize(2);
        var chickenLine = branch.lines().stream().filter(l -> l.productId() == chicken).findFirst().orElseThrow();
        assertThat(chickenLine.quantity()).isEqualTo(200);
        assertThat(chickenLine.averageCost()).isEqualByComparingTo("15.0000");
        assertThat(chickenLine.value()).isEqualTo(money("3000.00"));
        assertThat(branch.lines().stream().filter(l -> l.productId() == cage).findFirst().orElseThrow().value())
                .isEqualTo(money("500.00"));
        assertThat(branch.total()).isEqualTo(money("3500.00"));
        assertThat(inv.grandTotal()).isEqualTo(
                inv.branches().stream().map(ReportService.BranchInventory::total)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertThat(inv.grandTotal()).isGreaterThanOrEqualTo(money("3500.00"));
    }

    // ---- real deliveries over HTTP, on today's date (deltas, because other tests also write today) ---------------

    /** A real delivery, cancel and partial refund over HTTP change today's figures by exactly the expected deltas. */
    @Test
    void realDeliveryCancelAndRefundShowUpAsDeltas() throws Exception {
        String tag = UUID.randomUUID().toString();
        long pkg = jdbc.queryForObject("insert into sales_package (sku, name, price) values (?, ?, 70000) returning id",
                Long.class, "SKU-" + tag, "Small " + tag);
        jdbc.update("insert into package_item (package_id, product_id, quantity) values (?, ?, 24), (?, ?, 2)",
                pkg, chicken, pkg, cage);
        buy(chicken, 100, "10.00", PaymentMethod.CASH, LocalDate.now(clock));
        buy(cage, 10, "50.00", PaymentMethod.CASH, LocalDate.now(clock));
        LocalDate today = LocalDate.now(clock);
        String token = login(TestcontainersConfiguration.OWNER_USERNAME, TestcontainersConfiguration.OWNER_PASSWORD);
        var before = reports.summary(today, today);
        var profitBefore = reports.profit(today, today);

        var created = call("POST", "/api/deliveries", token, """
                {"customerName":"Juan","driverName":"Pedro","fromBranchId":%d,"destination":"Davao",
                 "paymentMethod":"GCASH","lines":[{"packageId":%d,"quantity":1}]}""".formatted(branchId, pkg));
        assertThat(created.statusCode()).isEqualTo(201);
        String id = find("\"id\":(\\d+)", created.body());

        var afterCreate = reports.summary(today, today);
        assertThat(afterCreate.sales().net().subtract(before.sales().net())).isEqualTo(money("70000.00"));
        // Profit rises by the sale minus the delivered cost: 70,000 - 340.
        assertThat(reports.profit(today, today).profit().subtract(profitBefore.profit()))
                .isEqualTo(money("69660.00"));

        // Cancel, then refund 20,000 of the 70,000 paid (owner only actions).
        assertThat(call("POST", "/api/deliveries/" + id + "/cancel", token, "{\"reason\":\"no show\"}").statusCode())
                .isEqualTo(200);
        assertThat(call("POST", "/api/deliveries/" + id + "/refund", token,
                "{\"amount\":20000.00,\"method\":\"CASH\"}").statusCode()).isEqualTo(200);

        var after = reports.summary(today, today);
        assertThat(after.sales().gross().subtract(before.sales().gross())).isEqualTo(money("70000.00"));
        assertThat(after.sales().cancelled().subtract(before.sales().cancelled())).isEqualTo(money("70000.00"));
        assertThat(after.sales().net()).isEqualTo(before.sales().net());
        assertThat(after.collections().received().subtract(before.collections().received()))
                .isEqualTo(money("70000.00"));
        assertThat(after.collections().refunds().subtract(before.collections().refunds())).isEqualTo(money("20000.00"));
        // 70,000 paid - 20,000 refunded stays retained, apart from sales.
        assertThat(after.collections().retainedOnCancelledDeliveries()
                .subtract(before.collections().retainedOnCancelledDeliveries())).isEqualTo(money("50000.00"));
        assertThat(after.stockPurchases().subtract(before.stockPurchases())).isEqualTo(money("0.00"));
        // Cancelled: sale and cost are both undone, so profit is back where it started.
        assertThat(reports.profit(today, today).profit()).isEqualTo(profitBefore.profit());
    }

    // ---- HTTP: roles, validation, shape --------------------------------------------------------------------------

    /** Assistants may read summary and inventory but not profit; bad periods give 422; unauthenticated gives 401. */
    @Test
    void endpointsEnforceRolesAndValidatePeriodsOverHttp() throws Exception {
        buildScenario();
        String ownerToken = login(TestcontainersConfiguration.OWNER_USERNAME,
                TestcontainersConfiguration.OWNER_PASSWORD);
        call("POST", "/api/users", ownerToken,
                "{\"username\":\"assistant-rpt\",\"password\":\"assistant-password\","
                        + "\"displayName\":\"A\",\"role\":\"ASSISTANT\"}");
        String assistant = login("assistant-rpt", "assistant-password");
        String period = "?from=2019-05-01&to=2019-05-31";

        assertThat(call("GET", "/api/reports/summary" + period, null, null).statusCode()).isEqualTo(401);

        var assistantSummary = call("GET", "/api/reports/summary" + period, assistant, null);
        assertThat(assistantSummary.statusCode()).isEqualTo(200);
        assertThat(assistantSummary.body()).contains("\"gross\":110000.00", "\"netCollected\":95000.00")
                .doesNotContain("stockPurchases", "otherExpenses", "cashMovement", "GCASH");

        var summary = call("GET", "/api/reports/summary" + period, ownerToken, null);
        assertThat(summary.statusCode()).isEqualTo(200);
        assertThat(summary.body()).contains("\"gross\":110000.00", "\"net\":70000.00", "\"netCollected\":95000.00",
                "\"retainedOnCancelledDeliveries\":25000.00", "\"stockPurchases\":1000.00", "\"otherExpenses\":500.00",
                "\"from\":\"2019-05-01\"", "\"GCASH\":69500.00");

        assertThat(call("GET", "/api/reports/profit" + period, assistant, null).statusCode()).isEqualTo(403);
        var profit = call("GET", "/api/reports/profit" + period, ownerToken, null);
        assertThat(profit.statusCode()).isEqualTo(200);
        assertThat(profit.body()).contains("\"profit\":69260.00");

        assertThat(call("GET", "/api/reports/inventory-value", assistant, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/reports/inventory-value", ownerToken, null).body()).contains("grandTotal");

        // Backwards period, 367-day period: business rule violations (422); unparsable date: bad request (400).
        assertThat(call("GET", "/api/reports/summary?from=2019-05-31&to=2019-05-01", assistant, null).statusCode())
                .isEqualTo(422);
        assertThat(call("GET", "/api/reports/profit?from=2019-05-31&to=2019-05-01", ownerToken, null).statusCode())
                .isEqualTo(422);
        assertThat(call("GET", "/api/reports/summary?from=2020-01-01&to=2021-01-01", assistant, null).statusCode())
                .isEqualTo(422);
        assertThat(call("GET", "/api/reports/summary?from=nonsense&to=2021-01-01", assistant, null).statusCode())
                .isEqualTo(400);
    }

    /**
     * Sends an HTTP request to the running test server.
     *
     * @param method HTTP method
     * @param path   path and query string
     * @param token  bearer token, or null for an unauthenticated call
     * @param json   request body, or null for none
     * @return the response with its body as text
     * @throws Exception if the request cannot be sent
     */
    HttpResponse<String> call(String method, String path, String token, String json) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Logs in and returns the JWT.
     *
     * @param user username
     * @param pass password
     * @return the bearer token
     * @throws Exception if the request cannot be sent
     */
    String login(String user, String pass) throws Exception {
        var r = call("POST", "/api/auth/login", null,
                "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, pass));
        assertThat(r.statusCode()).isEqualTo(200);
        return find("\"token\":\"([^\"]+)\"", r.body());
    }

    /**
     * Extracts the first capture group of a regex from a body, failing the test if it does not match.
     *
     * @param regex pattern with one capture group
     * @param body  text to search
     * @return the captured text
     */
    static String find(String regex, String body) {
        var m = Pattern.compile(regex).matcher(body);
        assertThat(m.find()).as(body).isTrue();
        return m.group(1);
    }
}
