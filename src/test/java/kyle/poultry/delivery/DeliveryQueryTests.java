package kyle.poultry.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import kyle.poultry.ApiTestClient;
import kyle.poultry.TestcontainersConfiguration;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * HTTP tests of the delivery read side against a real PostgreSQL: the list filters (status, branch, customer, date
 * range), the status counts and the owner-only receipt. Each test builds its own branches, products and package, and
 * filters by its own branch, so tests that share the database never see each other's deliveries.
 *
 * <p>Deliveries are created with explicit start times around Manila midnight. Manila is UTC+8, so
 * {@code 2021-07-14T16:00:00Z} is the first instant of 15 July in Manila.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DeliveryQueryTests {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;

    ApiTestClient api;
    String owner, assistant;
    long branchA, branchB, chicken, cage, pkg;

    /**
     * Fresh data per test: two branches stocked with 1,000 chickens (10.00 each) and 100 cages (50.00 each), and a
     * package of 2 chickens and 1 cage priced at 1,000.00.
     */
    @BeforeEach
    void freshData() {
        api = new ApiTestClient(port);
        owner = api.owner();
        assistant = api.assistant();
        String tag = UUID.randomUUID().toString();
        branchA = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "a-" + tag);
        branchB = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "b-" + tag);
        chicken = jdbc.queryForObject("insert into product (name, unit) values (?, 'head') returning id", Long.class, "chicken-" + tag);
        cage = jdbc.queryForObject("insert into product (name, unit) values (?, 'pc') returning id", Long.class, "cage-" + tag);
        pkg = jdbc.queryForObject("insert into sales_package (sku, name, price) values (?, ?, 1000) returning id", Long.class,
                "SKU-" + tag, "Pack " + tag);
        jdbc.update("insert into package_item (package_id, product_id, quantity) values (?, ?, 2), (?, ?, 1)", pkg, chicken, pkg, cage);
        for (long branch : new long[] {branchA, branchB}) {
            buy(chicken, branch, 1000, "10.00");
            buy(cage, branch, 100, "50.00");
        }
    }

    /** Buys stock through the API as the owner. */
    void buy(long product, long branch, int qty, String unitCost) {
        var r = api.call("POST", "/api/stock/movements", owner, """
                {"productId":%d,"branchId":%d,"type":"PURCHASE","quantityDelta":%d,"unitCost":%s,"paymentMethod":"CASH","occurredOn":"2021-01-02"}"""
                .formatted(product, branch, qty, unitCost));
        assertThat(r.statusCode()).isEqualTo(201);
    }

    /**
     * Creates one delivery of one package and returns its id.
     *
     * @param branch   branch the stock comes from
     * @param customer customer name
     * @param startedAt start time as an ISO instant
     * @return the new delivery's id
     */
    long deliver(long branch, String customer, String startedAt) {
        var r = api.call("POST", "/api/deliveries", owner, """
                {"customerName":"%s","driverName":"Pedro","fromBranchId":%d,"destination":"Davao","startedAt":"%s",
                 "paymentMethod":"CASH","lines":[{"packageId":%d,"quantity":1}]}""".formatted(customer, branch, startedAt, pkg));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return ((Number) JsonPath.read(r.body(), "$.id")).longValue();
    }

    /** Reads the customer names of a list response, in the order returned. */
    static List<String> customers(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JsonPath.read(r.body(), "$.content[*].customerName");
    }

    // ---- list filters ---------------------------------------------------------------------------------------------

    /** Both ends of a date range are inclusive in Manila time: the last second of a day and the first of the next. */
    @Test
    void dateRangeIsInclusiveInManilaTime() {
        deliver(branchA, "Before", "2021-07-14T15:59:59Z");   // 14 July 23:59:59 Manila
        deliver(branchA, "First second", "2021-07-14T16:00:00Z");   // 15 July 00:00:00 Manila
        deliver(branchA, "Last second", "2021-07-15T15:59:59Z");    // 15 July 23:59:59 Manila
        deliver(branchA, "After", "2021-07-15T16:00:00Z");          // 16 July 00:00:00 Manila

        var one = api.call("GET", "/api/deliveries?branchId=" + branchA + "&from=2021-07-15&to=2021-07-15", assistant, null);
        // Newest first: the last second of the day comes before the first.
        assertThat(customers(one)).containsExactly("Last second", "First second");

        // Open-ended ranges: only "from", only "to".
        assertThat(customers(api.call("GET", "/api/deliveries?branchId=" + branchA + "&from=2021-07-16", assistant, null)))
                .containsExactly("After");
        assertThat(customers(api.call("GET", "/api/deliveries?branchId=" + branchA + "&to=2021-07-14", assistant, null)))
                .containsExactly("Before");
        // A single day spanning two UTC dates is still one Manila day.
        assertThat(customers(api.call("GET", "/api/deliveries?branchId=" + branchA + "&from=2021-07-14&to=2021-07-16", assistant, null)))
                .hasSize(4);
    }

    /** A backwards range is a business-rule error (422); an unparsable date is a bad request (400). */
    @Test
    void invalidDatesAreRejected() {
        assertThat(api.call("GET", "/api/deliveries?from=2021-07-16&to=2021-07-15", assistant, null).statusCode()).isEqualTo(422);
        assertThat(api.call("GET", "/api/deliveries?from=yesterday", assistant, null).statusCode()).isEqualTo(400);
        assertThat(api.call("GET", "/api/deliveries/summary?from=2021-07-16&to=2021-07-15", assistant, null).statusCode()).isEqualTo(422);
    }

    /** The branch filter keeps only deliveries whose stock came from that branch. */
    @Test
    void branchFilterSeparatesBranches() {
        deliver(branchA, "On A", "2021-08-01T02:00:00Z");
        deliver(branchB, "On B", "2021-08-01T03:00:00Z");

        assertThat(customers(api.call("GET", "/api/deliveries?branchId=" + branchA, assistant, null))).containsExactly("On A");
        assertThat(customers(api.call("GET", "/api/deliveries?branchId=" + branchB, assistant, null))).containsExactly("On B");
    }

    /** Customer search ignores case, matches inside the name, and treats % and _ as plain characters. */
    @Test
    void customerSearchIgnoresCaseAndTreatsWildcardsLiterally() {
        deliver(branchA, "Maria Dela Cruz", "2021-09-01T01:00:00Z");
        deliver(branchA, "Juan_100%", "2021-09-01T02:00:00Z");
        deliver(branchA, "Juan 1000", "2021-09-01T03:00:00Z");

        assertThat(customers(api.call("GET", "/api/deliveries?branchId=" + branchA + "&customer=dela%20CRUZ", assistant, null)))
                .containsExactly("Maria Dela Cruz");
        // "_" would match any single character if it were a wildcard, and "%" any text.
        assertThat(customers(api.call("GET", "/api/deliveries?branchId=" + branchA + "&customer=juan_100%25", assistant, null)))
                .containsExactly("Juan_100%");
        assertThat(customers(api.call("GET", "/api/deliveries?branchId=" + branchA + "&customer=nobody", assistant, null))).isEmpty();
        // A blank search means no search.
        assertThat(customers(api.call("GET", "/api/deliveries?branchId=" + branchA + "&customer=%20%20", assistant, null))).hasSize(3);
        // A search longer than a customer name can be is a bad request.
        assertThat(api.call("GET", "/api/deliveries?customer=" + "x".repeat(121), assistant, null).statusCode()).isEqualTo(400);
    }

    /** Filters combine with AND, including the status filter, and paging still applies. */
    @Test
    void filtersCombineAndPage() {
        long done = deliver(branchA, "Done one", "2021-10-01T01:00:00Z");
        deliver(branchA, "Active one", "2021-10-01T02:00:00Z");
        long cancelled = deliver(branchA, "Cancelled one", "2021-10-01T03:00:00Z");
        assertThat(api.call("POST", "/api/deliveries/" + done + "/done", owner, "{\"completedAt\":\"2021-10-01T05:00:00Z\"}").statusCode())
                .isEqualTo(200);
        assertThat(api.call("POST", "/api/deliveries/" + cancelled + "/cancel", owner, "{\"reason\":\"no show\"}").statusCode()).isEqualTo(200);

        String base = "/api/deliveries?branchId=" + branchA + "&from=2021-10-01&to=2021-10-01";
        assertThat(customers(api.call("GET", base + "&status=DONE", assistant, null))).containsExactly("Done one");
        assertThat(customers(api.call("GET", base + "&status=ACTIVE&customer=one", assistant, null))).containsExactly("Active one");
        assertThat(customers(api.call("GET", base + "&status=CANCELLED&customer=done", assistant, null))).isEmpty();

        var page = api.call("GET", base + "&size=2&page=1", assistant, null);
        assertThat(customers(page)).containsExactly("Done one");
        assertThat((Integer) JsonPath.read(page.body(), "$.page.totalElements")).isEqualTo(3);
        // A client sort cannot reorder the documented newest-first listing, nor break it.
        assertThat(customers(api.call("GET", base + "&sort=customerName,asc", assistant, null)))
                .containsExactly("Cancelled one", "Active one", "Done one");
    }

    /** A requested page larger than the API limit is clamped to 100. */
    @Test
    void deliveryPageSizeIsCappedAtOneHundred() {
        var page = api.call("GET", "/api/deliveries?branchId=" + branchA + "&size=1000", assistant, null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(page.body(), "$.page.size")).isEqualTo(100);
    }

    /** Lines and cancelled retained payments load in batches instead of once per delivery. */
    @Test
    void deliveryListQueryCountStaysBoundedForCancelledRows() {
        for (int i = 0; i < 15; i++) {
            long id = deliver(branchA, "Cancelled " + i, "2021-10-02T01:00:00Z");
            var cancelled = api.call("POST", "/api/deliveries/" + id + "/cancel", owner, "{\"reason\":\"test\"}");
            assertThat(cancelled.statusCode()).isEqualTo(200);
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        var list = api.call("GET", "/api/deliveries?branchId=" + branchA + "&size=50", assistant, null);

        assertThat(list.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(list.body(), "$.content.length()"))
                .isGreaterThanOrEqualTo(15);
        assertThat(statistics.getPrepareStatementCount()).as("Hibernate statements for one delivery page")
                .isLessThanOrEqualTo(8);
    }

    /** The list needs a token. */
    @Test
    void listAndSummaryRequireAuthentication() {
        assertThat(api.call("GET", "/api/deliveries", null, null).statusCode()).isEqualTo(401);
        assertThat(api.call("GET", "/api/deliveries/summary", null, null).statusCode()).isEqualTo(401);
    }

    // ---- summary --------------------------------------------------------------------------------------------------

    /** Counts per status follow the same selection, ignore the status filter, and are open to assistants. */
    @Test
    void summaryCountsEveryStatusForTheSelection() {
        long done = deliver(branchA, "S1", "2021-11-01T01:00:00Z");
        deliver(branchA, "S2", "2021-11-01T02:00:00Z");
        deliver(branchA, "S3", "2021-11-01T03:00:00Z");
        long cancelled = deliver(branchA, "S4", "2021-11-01T04:00:00Z");
        deliver(branchB, "Elsewhere", "2021-11-01T04:00:00Z");
        api.call("POST", "/api/deliveries/" + done + "/done", owner, "{\"completedAt\":\"2021-11-01T06:00:00Z\"}");
        api.call("POST", "/api/deliveries/" + cancelled + "/cancel", owner, "{\"reason\":\"x\"}");

        var r = api.call("GET", "/api/deliveries/summary?branchId=" + branchA + "&from=2021-11-01&to=2021-11-01", assistant, null);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(r.body(), "$.active")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(r.body(), "$.done")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(r.body(), "$.cancelled")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(r.body(), "$.total")).isEqualTo(4);
        // The status filter is not part of the summary: it still counts every status.
        var withStatus = api.call("GET", "/api/deliveries/summary?branchId=" + branchA + "&customer=S1", assistant, null);
        assertThat((Integer) JsonPath.read(withStatus.body(), "$.total")).isEqualTo(1);
        // Another day: nothing.
        var other = api.call("GET", "/api/deliveries/summary?branchId=" + branchA + "&from=2021-11-02", assistant, null);
        assertThat((Integer) JsonPath.read(other.body(), "$.total")).isZero();
    }

    // ---- receipt --------------------------------------------------------------------------------------------------

    /** The owner sees cost and profit per line and per product; they add up to the delivery totals. */
    @Test
    void receiptShowsCostAndProfitPerLineAndProduct() {
        // Raise the average cost of chickens in branch A first: 1,000 at 10.00 plus 1,000 at 30.00 => average 20.0000.
        buy(chicken, branchA, 1000, "30.00");
        long id = deliver(branchA, "Receipt customer", "2021-12-01T01:00:00Z");

        var r = api.call("GET", "/api/deliveries/" + id + "/receipt", owner, null);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        // 2 chickens at 20.00 + 1 cage at 50.00 = 90.00 of cost against a 1,000.00 sale.
        assertThat(((Number) JsonPath.read(r.body(), "$.total")).doubleValue()).isEqualTo(1000.00);
        assertThat(((Number) JsonPath.read(r.body(), "$.costOfGoods")).doubleValue()).isEqualTo(90.00);
        assertThat(((Number) JsonPath.read(r.body(), "$.grossProfit")).doubleValue()).isEqualTo(910.00);
        assertThat(((Number) JsonPath.read(r.body(), "$.marginPercent")).doubleValue()).isEqualTo(91.00);
        assertThat((Boolean) JsonPath.read(r.body(), "$.countedInProfit")).isTrue();
        assertThat((String) JsonPath.read(r.body(), "$.status")).isEqualTo("ACTIVE");
        assertThat(((Number) JsonPath.read(r.body(), "$.lines[0].costOfGoods")).doubleValue()).isEqualTo(90.00);
        assertThat(((Number) JsonPath.read(r.body(), "$.lines[0].profit")).doubleValue()).isEqualTo(910.00);
        List<Number> productCosts = JsonPath.read(r.body(), "$.products[*].totalCost");
        assertThat(productCosts.stream().mapToDouble(Number::doubleValue).sum()).isEqualTo(90.00);
        assertThat((Integer) JsonPath.read(r.body(), "$.products.length()")).isEqualTo(2);
        List<Number> unitCosts = JsonPath.read(r.body(), "$.products[?(@.productId == " + chicken + ")].unitCost");
        assertThat(unitCosts.getFirst().doubleValue()).isEqualTo(20.00);
    }

    /** Two lines sharing a product split its cost by the units each used; the lines add up to the delivery's cost. */
    @Test
    void sharedProductCostIsSplitByUnits() {
        // A second package that also uses chickens (3 per package).
        String tag = UUID.randomUUID().toString();
        long big = jdbc.queryForObject("insert into sales_package (sku, name, price) values (?, ?, 5000) returning id", Long.class,
                "SKU-" + tag, "Big " + tag);
        jdbc.update("insert into package_item (package_id, product_id, quantity) values (?, ?, 3)", big, chicken);
        var created = api.call("POST", "/api/deliveries", owner, """
                {"customerName":"Two lines","driverName":"Pedro","fromBranchId":%d,"destination":"Davao","startedAt":"2022-01-01T01:00:00Z",
                 "paymentMethod":"CASH","lines":[{"packageId":%d,"quantity":1},{"packageId":%d,"quantity":1}]}""".formatted(branchA, pkg, big));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        long id = ((Number) JsonPath.read(created.body(), "$.id")).longValue();

        var r = api.call("GET", "/api/deliveries/" + id + "/receipt", owner, null);

        // 5 chickens at 10.00 = 50.00 and 1 cage at 50.00 = 50.00, so the delivery cost 100.00.
        assertThat(((Number) JsonPath.read(r.body(), "$.costOfGoods")).doubleValue()).isEqualTo(100.00);
        List<Number> lineCosts = JsonPath.read(r.body(), "$.lines[*].costOfGoods");
        // Lines come in request order: the first used the cage and 2 of the 5 chickens, the second 3 of the 5.
        assertThat(lineCosts.get(0).doubleValue()).isEqualTo(70.00);
        assertThat(lineCosts.get(1).doubleValue()).isEqualTo(30.00);
        List<Number> lineProfits = JsonPath.read(r.body(), "$.lines[*].profit");
        assertThat(lineProfits.get(0).doubleValue()).isEqualTo(930.00);
        assertThat(lineProfits.get(1).doubleValue()).isEqualTo(4970.00);
    }

    /** A cancelled delivery is flagged as not counted in profit and shows the payment still held. */
    @Test
    void receiptOfACancelledDeliveryShowsTheRetainedPayment() {
        long id = deliver(branchA, "Cancel me", "2022-02-01T01:00:00Z");
        api.call("POST", "/api/deliveries/" + id + "/cancel", owner, "{\"reason\":\"changed mind\"}");

        var r = api.call("GET", "/api/deliveries/" + id + "/receipt", owner, null);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(r.body(), "$.countedInProfit")).isFalse();
        assertThat(((Number) JsonPath.read(r.body(), "$.retainedPayment")).doubleValue()).isEqualTo(1000.00);
        // The cost is still the cost of the goods as sold, not of the reversal.
        assertThat(((Number) JsonPath.read(r.body(), "$.costOfGoods")).doubleValue()).isEqualTo(70.00);
        assertThat((String) JsonPath.read(r.body(), "$.cancelReason")).isEqualTo("changed mind");
    }

    /** Cost and profit are owner-only: an assistant gets 403, an unknown delivery 404, no token 401. */
    @Test
    void receiptIsOwnerOnly() {
        long id = deliver(branchA, "Owner only", "2022-03-01T01:00:00Z");

        assertThat(api.call("GET", "/api/deliveries/" + id + "/receipt", assistant, null).statusCode()).isEqualTo(403);
        assertThat(api.call("GET", "/api/deliveries/" + id + "/receipt", null, null).statusCode()).isEqualTo(401);
        assertThat(api.call("GET", "/api/deliveries/999999999/receipt", owner, null).statusCode()).isEqualTo(404);
        // The plain delivery view stays free of cost and profit for assistants.
        var plain = api.call("GET", "/api/deliveries/" + id, assistant, null);
        assertThat(plain.statusCode()).isEqualTo(200);
        assertThat(plain.body()).doesNotContain("costOfGoods").doesNotContain("grossProfit");
    }
}
