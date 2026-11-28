package kyle.poultry.delivery.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import kyle.poultry.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * HTTP tests of the Idempotency-Key header on delivery creation: repeats, mismatched bodies, per-user scoping, failed
 * requests and concurrent double submits. Each test uses a fresh branch with its own stock, so tests do not depend on
 * each other.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DeliveryIdempotencyTests {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final HttpClient http = HttpClient.newHttpClient();

    String owner;
    long branch, smallPackage;

    /**
     * Sends one JSON request, optionally with an Idempotency-Key header.
     *
     * @param method HTTP verb
     * @param path path starting with /api
     * @param token bearer token or null
     * @param json request body or null
     * @param idempotencyKey header value or null
     * @return the response
     */
    HttpResponse<String> call(String method, String path, String token, String json, String idempotencyKey) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey);
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
        var r = call("POST", "/api/auth/login", null, "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, pass), null);
        assertThat(r.statusCode()).isEqualTo(200);
        var m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(r.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    /** Looks up a seeded product's id by name. */
    long productId(String name) { return jdbc.queryForObject("select id from product where name = ?", Long.class, name); }

    /**
     * Extracts the first numeric id from a JSON body.
     *
     * @param body the JSON text
     * @return the id
     */
    long idOf(String body) {
        var m = Pattern.compile("\"id\":(\\d+)").matcher(body);
        assertThat(m.find()).isTrue();
        return Long.parseLong(m.group(1));
    }

    /** Gives every test a fresh branch stocked well above what one package needs, plus an owner token. */
    @BeforeEach
    void setUp() throws Exception {
        owner = login(TestcontainersConfiguration.OWNER_USERNAME, TestcontainersConfiguration.OWNER_PASSWORD);
        branch = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "b-" + UUID.randomUUID());
        for (String[] item : new String[][] {{"Chicken", "1000", "10.00"}, {"Cage", "100", "100.00"}, {"Nipple", "500", "20.00"},
                {"Feeders", "200", "30.00"}, {"Feed", "300", "50.00"}}) {
            var r = call("POST", "/api/stock/movements", owner, """
                    {"productId":%d,"branchId":%d,"type":"PURCHASE","quantityDelta":%s,"unitCost":%s,"paymentMethod":"CASH","occurredOn":"2026-01-02"}"""
                    .formatted(productId(item[0]), branch, item[1], item[2]), null);
            assertThat(r.statusCode()).as(item[0]).isEqualTo(201);
        }
        smallPackage = jdbc.queryForObject("select id from sales_package where sku = 'PKG-RTL-SML'", Long.class);
    }

    /**
     * Builds a create-delivery body for one Package Small line.
     *
     * @param customer customer name, used to find the rows again
     * @param quantity number of packages
     * @return the JSON
     */
    String body(String customer, int quantity) {
        return """
                {"customerName":"%s","driverName":"Pedro","fromBranchId":%d,"destination":"Davao City",
                 "paymentMethod":"CASH","lines":[{"packageId":%d,"quantity":%d}]}""".formatted(customer, branch, smallPackage, quantity);
    }

    /** Counts deliveries for a customer name; names are unique per test so other tests do not interfere. */
    long deliveriesFor(String customer) {
        return jdbc.queryForObject("select count(*) from delivery where customer_name = ?", Long.class, customer);
    }

    /** Returns the chickens left in this test's branch. */
    long chickens() {
        return jdbc.queryForObject("select quantity from stock_balance where product_id = ? and branch_id = ?", Long.class,
                productId("Chicken"), branch);
    }

    /**
     * A repeat with the same key answers 200 with the identical body, creates no second delivery, and deducts stock
     * once.
     */
    @Test
    void repeatWithSameKeyReturnsTheOriginalAndCreatesNothing() throws Exception {
        String customer = "c-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();

        var first = call("POST", "/api/deliveries", owner, body(customer, 1), key);
        assertThat(first.statusCode()).isEqualTo(201);
        var repeat = call("POST", "/api/deliveries", owner, body(customer, 1), key);
        assertThat(repeat.statusCode()).isEqualTo(200);
        assertThat(repeat.body()).isEqualTo(first.body());

        assertThat(deliveriesFor(customer)).isEqualTo(1);
        assertThat(chickens()).isEqualTo(1000 - 24);
        assertThat(jdbc.queryForObject("select delivery_id from delivery_idempotency where idempotency_key = ?", Long.class, key))
                .isEqualTo(idOf(first.body()));
    }

    /** Reusing a key with a different quantity or customer answers 422 and changes nothing. */
    @Test
    void sameKeyWithDifferentBodyIsRejected() throws Exception {
        String customer = "c-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        assertThat(call("POST", "/api/deliveries", owner, body(customer, 1), key).statusCode()).isEqualTo(201);

        var different = call("POST", "/api/deliveries", owner, body(customer, 2), key);
        assertThat(different.statusCode()).isEqualTo(422);
        assertThat(different.body()).contains("Idempotency-Key");
        assertThat(call("POST", "/api/deliveries", owner, body(customer + "-other", 1), key).statusCode()).isEqualTo(422);
        assertThat(deliveriesFor(customer)).isEqualTo(1);
        assertThat(chickens()).isEqualTo(1000 - 24);
    }

    /** Without the header the old behaviour stays: each request creates a delivery. */
    @Test
    void withoutAKeyEveryRequestCreatesADelivery() throws Exception {
        String customer = "c-" + UUID.randomUUID();
        assertThat(call("POST", "/api/deliveries", owner, body(customer, 1), null).statusCode()).isEqualTo(201);
        assertThat(call("POST", "/api/deliveries", owner, body(customer, 1), null).statusCode()).isEqualTo(201);
        assertThat(deliveriesFor(customer)).isEqualTo(2);
    }

    /** Keys must be 1-100 characters, and the same key used by two users creates two deliveries. */
    @Test
    void keyLengthIsValidatedAndKeysAreScopedPerUser() throws Exception {
        assertThat(call("POST", "/api/deliveries", owner, body("x", 1), "k".repeat(101)).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/deliveries", owner, body("x", 1), "").statusCode()).isEqualTo(400);

        String name = "assistant-idem-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(call("POST", "/api/users", owner,
                "{\"username\":\"%s\",\"password\":\"assistant-password\",\"displayName\":\"A\",\"role\":\"ASSISTANT\"}".formatted(name), null)
                .statusCode()).isEqualTo(201);
        String assistant = login(name, "assistant-password");

        String customer = "c-" + UUID.randomUUID();
        String key = "shared-" + UUID.randomUUID();
        assertThat(call("POST", "/api/deliveries", owner, body(customer, 1), key).statusCode()).isEqualTo(201);
        assertThat(call("POST", "/api/deliveries", assistant, body(customer, 1), key).statusCode()).isEqualTo(201);
        assertThat(deliveriesFor(customer)).isEqualTo(2);
    }

    /** A request that fails (not enough stock) rolls back, including the key row, so the key can be reused. */
    @Test
    void failedRequestDoesNotConsumeTheKey() throws Exception {
        String customer = "c-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        assertThat(call("POST", "/api/deliveries", owner, body(customer, 1_000_000), key).statusCode()).isEqualTo(409);
        // (the quantity above is far more than the branch holds)
        assertThat(jdbc.queryForObject("select count(*) from delivery_idempotency where idempotency_key = ?", Long.class, key)).isZero();
        assertThat(call("POST", "/api/deliveries", owner, body(customer, 1), key).statusCode()).isEqualTo(201);
    }

    /**
     * Eight simultaneous requests with one key produce exactly one 201, seven 200s, one delivery and one stock
     * deduction.
     */
    @Test
    void concurrentSubmitsWithTheSameKeyCreateExactlyOneDelivery() throws Exception {
        String customer = "c-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        int n = 8;
        var start = new CountDownLatch(1);
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return call("POST", "/api/deliveries", owner, body(customer, 1), key);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        start.countDown();
        var responses = futures.stream().map(CompletableFuture::join).toList();

        assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.statusCode() == 200)).hasSize(n - 1);
        assertThat(responses.stream().map(r -> idOf(r.body())).distinct()).hasSize(1);
        assertThat(deliveriesFor(customer)).isEqualTo(1);
        assertThat(chickens()).isEqualTo(1000 - 24);
    }
}
