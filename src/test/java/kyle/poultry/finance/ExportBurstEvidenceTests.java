package kyle.poultry.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import kyle.poultry.ApiTestClient;
import kyle.poultry.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * EVIDENCE HARNESS for the CSV export cap (at most {@code app.exports.max-concurrent} downloads at once). It measures
 * the real thing the unit-level test cannot: many simultaneous slow downloads over HTTP.
 *
 * <p>Questions: with 12 clients that start a large export and then stop reading, do exactly 3 get the file and 9 get
 * {@code 429} with {@code Retry-After}? Does an unrelated request (which needs a pooled connection) stay fast and
 * succeed while three streams hold connections? When the clients disconnect without finishing, are the slots given back,
 * so a later export works?
 *
 * <p>The ledger gets about 150,000 rows on dates no other test uses, so each export is far larger than the socket
 * buffers and a client that stops reading really keeps the server blocked mid-stream. The pool is set to the production
 * values. Switched off unless run with {@code -Devidence=true}:
 * {@code ./mvnw test -Dtest=ExportBurstEvidenceTests -Devidence=true}.
 */
@Tag("integration")
@Tag("evidence")
@EnabledIfSystemProperty(named = "evidence", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=10",
        "spring.datasource.hikari.minimum-idle=2",
        "spring.datasource.hikari.connection-timeout=10000"})
@Import(TestcontainersConfiguration.class)
class ExportBurstEvidenceTests {
    private static final int CLIENTS = 12;
    private static final int ROWS = 150_000;
    private static final String PATH = "/api/finance/entries.csv?from=2013-03-01&to=2013-03-28";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ExportLimiter limiter;

    /** Twelve clients start a large export and stall; three are served, nine are told to retry, the pool stays healthy. */
    @Test
    void slowDownloadsAreCappedAndDoNotStarveOtherRequests() throws Exception {
        var api = new ApiTestClient(port);
        String token = api.owner();
        long userId = jdbc.queryForObject("select id from app_user where username = ?", Long.class,
                TestcontainersConfiguration.OWNER_USERNAME);
        jdbc.update("""
                insert into financial_entry (type, cash_direction, amount, method, occurred_on, description, created_by, created_at)
                select 'EXPENSE', 'OUT', 50.00, 'CASH', date '2013-03-01' + (g %% 28),
                       'burst evidence row %d with a description long enough to make the file big ' || g, ?, now()
                  from generate_series(1, ?) g""".formatted(ROWS), userId, ROWS);
        var hikari = dataSource.unwrap(HikariDataSource.class);
        var pool = hikari.getHikariPoolMXBean();
        var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        var maxActive = new AtomicInteger();
        var stopSampling = new AtomicBoolean();
        Thread sampler = Thread.ofPlatform().daemon().start(() -> {
            while (!stopSampling.get()) {
                maxActive.accumulateAndGet(pool.getActiveConnections(), Math::max);
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
        });

        // The burst: each client reads a little, then stops reading until released.
        var go = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(CLIENTS);
        List<InputStream> opened = Collections.synchronizedList(new ArrayList<>());
        List<String> retryAfter = Collections.synchronizedList(new ArrayList<>());
        ExecutorService clients = Executors.newFixedThreadPool(CLIENTS);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) {
            futures.add(clients.submit(() -> {
                var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                        .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(60)).GET().build();
                go.await();
                HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() == 200) {
                    opened.add(r.body());
                    r.body().readNBytes(1024);
                } else {
                    r.headers().firstValue("Retry-After").ifPresent(retryAfter::add);
                    r.body().close();
                }
                started.countDown();
                if (r.statusCode() == 200) release.await(60, TimeUnit.SECONDS);
                return r.statusCode();
            }));
        }
        go.countDown();
        assertThat(started.await(30, TimeUnit.SECONDS)).as("every client got its first answer").isTrue();
        Thread.sleep(500);   // let the three served streams fill the socket buffers and block on the client

        // While three streams hold connections: unrelated authenticated requests.
        List<Long> probeMs = new ArrayList<>();
        int probeFailures = 0;
        var me = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(30)).GET().build();
        for (int i = 0; i < 40; i++) {
            long t0 = System.nanoTime();
            try {
                if (http.send(me, HttpResponse.BodyHandlers.discarding()).statusCode() != 200) probeFailures++;
            } catch (Exception e) {
                probeFailures++;
            }
            probeMs.add((System.nanoTime() - t0) / 1_000_000);
            Thread.sleep(50);
        }
        int activeWhileHeld = pool.getActiveConnections();

        // The clients walk away mid-download without finishing.
        release.countDown();
        for (InputStream in : opened) in.close();
        Map<Integer, Integer> statuses = new TreeMap<>();
        for (var f : futures) statuses.merge(f.get(60, TimeUnit.SECONDS), 1, Integer::sum);
        clients.shutdown();

        // Are the slots back? Poll until all three can be taken again (the server notices the closed sockets).
        long t0 = System.nanoTime();
        int recovered = 0;
        var taken = new ArrayList<ExportLimiter.Permit>();
        while (System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(30) && recovered < 3) {
            try {
                taken.add(limiter.acquire());
                recovered++;
            } catch (kyle.poultry.shared.error.TooManyRequestsException busy) {
                Thread.sleep(100);
            }
        }
        long recoveryMs = (System.nanoTime() - t0) / 1_000_000;
        taken.forEach(ExportLimiter.Permit::release);
        stopSampling.set(true);
        sampler.join(2_000);
        var after = api.call("GET", PATH, token, null);

        Collections.sort(probeMs);
        System.out.printf("EVIDENCE export-burst clients=%d rows=%d statuses=%s retryAfter=%s probe[n=%d failed=%d p50=%dms p99=%dms max=%dms] "
                        + "pool[activeWhileHeld=%d maxActive=%d of %d] slotsBackAfterAbort=%d/3 in %dms exportAfter=%d bytesAfter=%d%n",
                CLIENTS, ROWS, statuses, retryAfter.stream().distinct().toList(), probeMs.size(), probeFailures,
                probeMs.get(probeMs.size() / 2), probeMs.get(probeMs.size() - 1), probeMs.getLast(),
                activeWhileHeld, maxActive.get(), hikari.getMaximumPoolSize(), recovered, recoveryMs,
                after.statusCode(), after.body().length());

        // The contract being measured: exactly the cap is served, the rest told to retry, nobody else starved, slots recovered.
        assertThat(statuses).containsOnlyKeys(200, 429);
        assertThat(statuses.get(200)).isEqualTo(3);
        assertThat(statuses.get(429)).isEqualTo(CLIENTS - 3);
        assertThat(retryAfter).isNotEmpty().allMatch(v -> v.equals(String.valueOf(ExportLimiter.RETRY_AFTER_SECONDS)));
        assertThat(probeFailures).isZero();
        assertThat(recovered).isEqualTo(3);
        assertThat(after.statusCode()).isEqualTo(200);
    }
}
