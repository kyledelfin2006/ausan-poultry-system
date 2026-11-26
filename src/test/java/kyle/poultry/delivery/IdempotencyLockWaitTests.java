package kyle.poultry.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.util.UUID;
import javax.sql.DataSource;
import kyle.poultry.ApiTestClient;
import kyle.poultry.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CON-3: a repeated create whose first request is stuck must not wait on the advisory lock until the pool times out.
 * It waits a short time, then is answered 409 with {@code Retry-After}; once the first request is gone the same
 * request goes through. A separate connection plays the stuck first request by holding the same lock.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class IdempotencyLockWaitTests {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void aRepeatBehindAStuckRequestIsTold409WithRetryAfterAndLaterSucceeds() throws Exception {
        var api = new ApiTestClient(port);
        String owner = api.owner();
        long ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class,
                TestcontainersConfiguration.OWNER_USERNAME);
        String tag = UUID.randomUUID().toString();
        long branch = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "lw-" + tag);
        long product = jdbc.queryForObject("insert into product (name, unit) values (?, 'head') returning id", Long.class, "lw-" + tag);
        long pkg = jdbc.queryForObject("insert into sales_package (sku, name, price) values (?, ?, 100) returning id", Long.class,
                "LW-" + tag, "Lw " + tag);
        jdbc.update("insert into package_item (package_id, product_id, quantity) values (?, ?, 1)", pkg, product);
        assertThat(api.call("POST", "/api/stock/movements", owner, """
                {"productId":%d,"branchId":%d,"type":"PURCHASE","quantityDelta":10,"unitCost":10.00,"paymentMethod":"CASH","occurredOn":"2021-01-02"}"""
                .formatted(product, branch)).statusCode()).isEqualTo(201);
        String key = "lw-" + tag;
        String body = """
                {"customerName":"Lw","driverName":"Pedro","fromBranchId":%d,"destination":"Davao",
                 "paymentMethod":"CASH","lines":[{"packageId":%d,"quantity":1}]}""".formatted(branch, pkg);

        var hikari = dataSource.unwrap(HikariDataSource.class);
        try (Connection holder = DriverManager.getConnection(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword())) {
            holder.setAutoCommit(false);
            try (var st = holder.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                st.setString(1, ownerId + ":" + key);
                st.execute();
            }

            long t0 = System.nanoTime();
            var blocked = create(owner, body, key);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            assertThat(blocked.statusCode()).as(blocked.body()).isEqualTo(409);
            assertThat(blocked.headers().firstValue("Retry-After")).contains("1");
            assertThat((String) JsonPath.read(blocked.body(), "$.code")).isEqualTo("CONFLICT");
            assertThat(ms).as("gave up after the short wait, not the pool timeout").isBetween(2_000L, 8_000L);
            holder.rollback();
        }

        var retried = create(owner, body, key);
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from delivery_idempotency where idempotency_key = ?", Integer.class, key))
                .isEqualTo(1);
    }

    private HttpResponse<String> create(String token, String body, String key) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/deliveries"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }
}
