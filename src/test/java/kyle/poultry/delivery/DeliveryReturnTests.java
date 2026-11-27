package kyle.poultry.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import kyle.poultry.ApiTestClient;
import kyle.poultry.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DES-3: a DONE delivery can come back as a whole. Real PostgreSQL over HTTP. The stock goes back, the sale and its cost
 * are reversed, the money is handed back through the normal refund, and every other state is refused.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DeliveryReturnTests {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    ApiTestClient api;
    String owner;
    long branch, product, pkg;

    /** A branch holding 100 head and a package that sells one head for 100.00. */
    @BeforeEach
    void freshData() {
        api = new ApiTestClient(port);
        owner = api.owner();
        String tag = UUID.randomUUID().toString();
        branch = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "ret-" + tag);
        product = jdbc.queryForObject("insert into product (name, unit) values (?, 'head') returning id", Long.class, "ret-" + tag);
        pkg = jdbc.queryForObject("insert into sales_package (sku, name, price) values (?, ?, 100) returning id", Long.class,
                "RET-" + tag, "Ret " + tag);
        jdbc.update("insert into package_item (package_id, product_id, quantity) values (?, ?, 1)", pkg, product);
        assertThat(api.call("POST", "/api/stock/movements", owner, """
                {"productId":%d,"branchId":%d,"type":"PURCHASE","quantityDelta":100,"unitCost":10.00,"paymentMethod":"CASH","occurredOn":"2021-01-02"}"""
                .formatted(product, branch)).statusCode()).isEqualTo(201);
    }

    private long create() {
        var r = api.call("POST", "/api/deliveries", owner, """
                {"customerName":"Ret","driverName":"Pedro","fromBranchId":%d,"destination":"Davao",
                 "paymentMethod":"CASH","lines":[{"packageId":%d,"quantity":1}]}""".formatted(branch, pkg));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return ((Number) JsonPath.read(r.body(), "$.id")).longValue();
    }

    private long createDone() {
        long id = create();
        var done = api.call("POST", "/api/deliveries/" + id + "/done", owner, "{\"completedAt\":\"" + Instant.now() + "\"}");
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
        return id;
    }

    private long stockOnHand() {
        return jdbc.queryForObject("select quantity from stock_balance where product_id = ? and branch_id = ?", Long.class, product, branch);
    }

    private String status(long id) {
        return jdbc.queryForObject("select status from delivery where id = ?", String.class, id);
    }

    private int entries(long id, String type) {
        return jdbc.queryForObject("select count(*) from financial_entry where source_type = 'DELIVERY' and source_id = ? and type = ?",
                Integer.class, id, type);
    }

    private static final String REASON = "{\"reason\":\"customer rejected the goods\"}";

    @Test
    void aDoneDeliveryComesBackWithStockRestoredAndTheSaleReversed() {
        long id = createDone();
        assertThat(stockOnHand()).isEqualTo(99);

        var r = api.call("POST", "/api/deliveries/" + id + "/return", owner, REASON);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(r.body(), "$.status")).isEqualTo("RETURNED");
        assertThat((String) JsonPath.read(r.body(), "$.returnReason")).isEqualTo("customer rejected the goods");
        assertThat(((Number) JsonPath.read(r.body(), "$.retainedPayment")).doubleValue()).isEqualTo(100.0);
        assertThat(status(id)).isEqualTo("RETURNED");
        assertThat(stockOnHand()).as("the goods are sellable again").isEqualTo(100);
        assertThat(entries(id, "SALE_REVERSAL")).isEqualTo(1);
        assertThat(entries(id, "COGS_REVERSAL")).isEqualTo(1);
    }

    @Test
    void theMoneyGoesBackThroughTheNormalRefundAndNeverAboveWhatWasPaid() {
        long id = createDone();
        api.call("POST", "/api/deliveries/" + id + "/return", owner, REASON);

        var tooMuch = api.call("POST", "/api/deliveries/" + id + "/refund", owner, "{\"amount\":100.01,\"method\":\"CASH\"}");
        assertThat(tooMuch.statusCode()).isEqualTo(422);
        var ok = api.call("POST", "/api/deliveries/" + id + "/refund", owner, "{\"amount\":100.00,\"method\":\"CASH\"}");
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(ok.body(), "$.retainedPayment")).doubleValue()).isEqualTo(0.0);
    }

    /** Only DONE comes back: an active, cancelled or already returned delivery is refused and nothing moves. */
    @Test
    void onlyADoneDeliveryCanBeReturnedAndOnlyOnce() {
        long active = create();
        assertThat(api.call("POST", "/api/deliveries/" + active + "/return", owner, REASON).statusCode()).isEqualTo(409);
        assertThat(status(active)).isEqualTo("ACTIVE");

        long cancelled = create();
        api.call("POST", "/api/deliveries/" + cancelled + "/cancel", owner, REASON);
        assertThat(api.call("POST", "/api/deliveries/" + cancelled + "/return", owner, REASON).statusCode()).isEqualTo(409);

        long done = createDone();
        long before = stockOnHand();
        assertThat(api.call("POST", "/api/deliveries/" + done + "/return", owner, REASON).statusCode()).isEqualTo(200);
        assertThat(api.call("POST", "/api/deliveries/" + done + "/return", owner, REASON).statusCode()).isEqualTo(409);
        assertThat(api.call("POST", "/api/deliveries/" + done + "/cancel", owner, REASON).statusCode()).isEqualTo(409);
        assertThat(stockOnHand()).as("restored once, not twice").isEqualTo(before + 1);
        assertThat(entries(done, "SALE_REVERSAL")).isEqualTo(1);
    }

    @Test
    void anAssistantCannotReturnAndANonDoneDeliveryCannotBeRefunded() {
        long id = createDone();

        assertThat(api.call("POST", "/api/deliveries/" + id + "/return", api.assistant(), REASON).statusCode()).isEqualTo(403);
        assertThat(status(id)).isEqualTo("DONE");
        assertThat(stockOnHand()).isEqualTo(99);
        assertThat(entries(id, "SALE_REVERSAL")).isZero();
        assertThat(api.call("POST", "/api/deliveries/" + id + "/refund", owner, "{\"amount\":1.00,\"method\":\"CASH\"}").statusCode())
                .isEqualTo(422);
        assertThat(api.call("POST", "/api/deliveries/" + id + "/return", owner, "{\"reason\":\" \"}").statusCode()).isEqualTo(400);
    }

    @Test
    void countsAndFilterKnowReturnedDeliveries() {
        long id = createDone();
        api.call("POST", "/api/deliveries/" + id + "/return", owner, REASON);

        var list = api.call("GET", "/api/deliveries?status=RETURNED", owner, null);
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat((java.util.List<?>) JsonPath.read(list.body(), "$.content[?(@.id==" + id + ")]")).hasSize(1);
        var counts = api.call("GET", "/api/deliveries/summary", owner, null);
        assertThat(((Number) JsonPath.read(counts.body(), "$.returned")).intValue()).isGreaterThanOrEqualTo(1);
    }
}
