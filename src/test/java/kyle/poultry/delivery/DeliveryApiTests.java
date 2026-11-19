package kyle.poultry.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Pattern;
import kyle.poultry.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end HTTP tests of the catalog seed data and the delivery lifecycle against a real Postgres. Runs the whole
 * application on a random port; uses fresh branches so it does not depend on other test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DeliveryApiTests {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final HttpClient http = HttpClient.newHttpClient();

    /**
     * Sends one JSON request to the running server and returns the raw response.
     *
     * @param method HTTP verb
     * @param path path starting with /api
     * @param token bearer token or null
     * @param json request body or null
     * @return the response
     */
    HttpResponse<String> call(String method, String path, String token, String json) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Logs in over HTTP and returns the token.
     *
     * @param user login name
     * @param pass plain password
     * @return the JWT; fails the test unless login answers 200
     */
    String login(String user, String pass) throws Exception {
        var r = call("POST", "/api/auth/login", null, "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, pass));
        assertThat(r.statusCode()).isEqualTo(200);
        var m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(r.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    /** Looks up a seeded product's id by name. */
    long productId(String name) { return jdbc.queryForObject("select id from product where name = ?", Long.class, name); }

    /** The three official packages and the Feeders and Feed products exist with the agreed prices and units. */
    @Test
    void theOfficialPackagesAndNewProductsAreSeeded() throws Exception {
        String owner = login(TestcontainersConfiguration.OWNER_USERNAME, TestcontainersConfiguration.OWNER_PASSWORD);

        var packages = call("GET", "/api/packages", owner, null);
        assertThat(packages.statusCode()).isEqualTo(200);
        assertThat(packages.body()).contains("Package Small", "Package Medium", "Package Commercial",
                "PKG-RTL-SML", "70000.00", "160000.00", "700000.00");

        var products = call("GET", "/api/products", owner, null);
        assertThat(products.body()).contains("Feeders", "\"Feed\"", "\"unit\":\"bag\"");
    }

    /**
     * Creates, cancels and refunds a delivery over HTTP, checking stock deduction, owner-only cancel and refund, and
     * the retained payment.
     */
    @Test
    void fullDeliveryLifecycleOverHttp() throws Exception {
        String owner = login(TestcontainersConfiguration.OWNER_USERNAME, TestcontainersConfiguration.OWNER_PASSWORD);
        call("POST", "/api/users", owner,
                "{\"username\":\"assistant-dlv\",\"password\":\"assistant-password\",\"displayName\":\"A\",\"role\":\"ASSISTANT\"}");
        String assistant = login("assistant-dlv", "assistant-password");

        long branch = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "b-" + UUID.randomUUID());
        // Stock for one Package Small: 24 chickens, 2 cages, 8 nipples, 4 feeders, 6 bags of feed.
        for (String[] item : new String[][] {{"Chicken", "100", "10.00"}, {"Cage", "10", "100.00"}, {"Nipple", "50", "20.00"},
                {"Feeders", "20", "30.00"}, {"Feed", "30", "50.00"}}) {
            var r = call("POST", "/api/stock/movements", owner, """
                    {"productId":%d,"branchId":%d,"type":"PURCHASE","quantityDelta":%s,"unitCost":%s,"paymentMethod":"CASH","occurredOn":"2026-01-02"}"""
                    .formatted(productId(item[0]), branch, item[1], item[2]));
            assertThat(r.statusCode()).as(item[0]).isEqualTo(201);
        }
        long smallPackage = jdbc.queryForObject("select id from sales_package where sku = 'PKG-RTL-SML'", Long.class);

        var created = call("POST", "/api/deliveries", assistant, """
                {"customerName":"Juan","driverName":"Pedro","fromBranchId":%d,"destination":"Davao City",
                 "paymentMethod":"CASH","lines":[{"packageId":%d,"quantity":1}]}""".formatted(branch, smallPackage));
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"status\":\"ACTIVE\"", "Package Small", "70000.00", "DLV-");
        var idMatcher = Pattern.compile("\"id\":(\\d+)").matcher(created.body());
        assertThat(idMatcher.find()).isTrue();
        String id = idMatcher.group(1);

        assertThat(jdbc.queryForObject("select quantity from stock_balance where product_id = ? and branch_id = ?", Long.class,
                productId("Chicken"), branch)).isEqualTo(100 - 24);
        assertThat(jdbc.queryForObject("select quantity from stock_balance where product_id = ? and branch_id = ?", Long.class,
                productId("Feed"), branch)).isEqualTo(30 - 6);

        // An assistant cannot cancel; the owner can, and the payment stays visible until refunded.
        assertThat(call("POST", "/api/deliveries/" + id + "/cancel", assistant, "{\"reason\":\"changed mind\"}").statusCode()).isEqualTo(403);
        var cancelled = call("POST", "/api/deliveries/" + id + "/cancel", owner, "{\"reason\":\"changed mind\"}");
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(cancelled.body()).contains("\"status\":\"CANCELLED\"", "\"retainedPayment\":70000.00");
        assertThat(call("POST", "/api/deliveries/" + id + "/done", assistant, "{\"completedAt\":\"2026-01-03T10:00:00Z\"}").statusCode())
                .isEqualTo(409);

        var refund = call("POST", "/api/deliveries/" + id + "/refund", owner, "{\"amount\":70000.00,\"method\":\"CASH\"}");
        assertThat(refund.statusCode()).isEqualTo(200);
        assertThat(refund.body()).contains("\"retainedPayment\":0");

        var list = call("GET", "/api/deliveries?status=CANCELLED", assistant, null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("Juan");
    }
}
