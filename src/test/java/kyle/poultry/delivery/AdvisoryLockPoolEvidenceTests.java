package kyle.poultry.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
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
 * EVIDENCE HARNESS for audit item CON-3 (advisory-lock waiters hold pooled connections). It measures; it does not fix.
 *
 * <p>Question: when many requests with the same Idempotency-Key arrive together, do the waiters on
 * {@code pg_advisory_xact_lock} use up the connection pool and starve unrelated requests (every authenticated request
 * needs a connection for the user lookup)? The pool is set to the production values (10 connections, 10 s wait).
 *
 * <p>Three scenarios differ only in how long the first request holds the lock: not at all (a normal, fast create), 3 s
 * (a slow create) and 12 s (longer than the 10 s connection wait). A separate connection takes the lock for the slow
 * cases, which simulates a slow first request without changing production code.
 *
 * <p>Each run prints one {@code EVIDENCE} line with: create statuses and latencies, latency and failures of an unrelated
 * {@code GET /api/auth/me} sent every 100 ms during the burst, the highest pool usage, the highest number of sessions
 * waiting on an advisory lock in PostgreSQL, and a thread-dump count taken mid-burst.
 *
 * <p>Switched off unless run with {@code -Devidence=true}, so it never slows the normal build:
 * {@code ./mvnw test -Dtest=AdvisoryLockPoolEvidenceTests -Devidence=true}.
 */
@Tag("integration")
@Tag("evidence")
@EnabledIfSystemProperty(named = "evidence", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=10",
        "spring.datasource.hikari.minimum-idle=2",
        "spring.datasource.hikari.connection-timeout=10000"})
@Import(TestcontainersConfiguration.class)
class AdvisoryLockPoolEvidenceTests {
    private static final int PARALLEL_CREATES = 20;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    /** A normal create: the lock is taken and released within one fast transaction. */
    @Test
    void fastFirstRequest() throws Exception {
        run("fast", 0);
    }

    /** The first request is slow (3 s), so waiters pile up but the pool wait (10 s) is not exceeded. */
    @Test
    void slowFirstRequest() throws Exception {
        run("slow-3s", 3_000);
    }

    /** The first request outlasts the 10 s pool wait: the worst case. */
    @Test
    void firstRequestLongerThanThePoolWait() throws Exception {
        run("stuck-12s", 12_000);
    }

    private void run(String scenario, long holdMs) throws Exception {
        var api = new ApiTestClient(port);
        String token = api.owner();
        long ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class,
                TestcontainersConfiguration.OWNER_USERNAME);
        long pkg = fixture(api, token);
        String key = "evidence-" + UUID.randomUUID();
        var hikari = dataSource.unwrap(HikariDataSource.class);
        var pool = hikari.getHikariPoolMXBean();
        var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        // Sampler: pool usage and PostgreSQL's own view of who waits on an advisory lock, on a connection outside the pool.
        var stopSampling = new AtomicBoolean();
        var maxActive = new AtomicInteger();
        var maxAwaiting = new AtomicInteger();
        var maxUngranted = new AtomicInteger();
        var maxWaitingSessions = new AtomicInteger();
        Thread sampler = Thread.ofPlatform().daemon().start(() -> {
            try (Connection c = DriverManager.getConnection(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword())) {
                while (!stopSampling.get()) {
                    maxActive.accumulateAndGet(pool.getActiveConnections(), Math::max);
                    maxAwaiting.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                    maxUngranted.accumulateAndGet(count(c,
                            "select count(*) from pg_locks where locktype = 'advisory' and not granted"), Math::max);
                    maxWaitingSessions.accumulateAndGet(count(c,
                            "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and wait_event = 'advisory'"),
                            Math::max);
                    Thread.sleep(20);
                }
            } catch (Exception e) {
                System.out.println("EVIDENCE sampler stopped: " + e);
            }
        });

        // Simulated slow first request: another connection holds the same advisory lock for holdMs.
        Connection holder = null;
        if (holdMs > 0) {
            holder = DriverManager.getConnection(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
            holder.setAutoCommit(false);
            try (var st = holder.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                st.setString(1, ownerId + ":" + key);
                st.execute();
            }
        }
        Connection heldBy = holder;
        Thread releaser = holdMs == 0 ? null : Thread.ofPlatform().daemon().start(() -> {
            try {
                Thread.sleep(holdMs);
                heldBy.rollback();   // ends the transaction, which releases the lock
                heldBy.close();
            } catch (Exception e) {
                System.out.println("EVIDENCE releaser failed: " + e);
            }
        });

        // Unrelated traffic: any authenticated call needs a pooled connection for the user lookup.
        var stopProbe = new AtomicBoolean();
        List<Long> probeMs = Collections.synchronizedList(new ArrayList<>());
        var probeFailures = new AtomicInteger();
        Thread probe = Thread.ofPlatform().daemon().start(() -> {
            var me = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
                    .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(30)).GET().build();
            while (!stopProbe.get()) {
                long t0 = System.nanoTime();
                try {
                    var r = http.send(me, HttpResponse.BodyHandlers.discarding());
                    if (r.statusCode() != 200) probeFailures.incrementAndGet();
                } catch (Exception e) {
                    probeFailures.incrementAndGet();
                }
                probeMs.add((System.nanoTime() - t0) / 1_000_000);
                try { Thread.sleep(100); } catch (InterruptedException e) { return; }
            }
        });

        // The burst: identical creates released together.
        String body = """
                {"customerName":"Evidence","driverName":"Pedro","fromBranchId":%d,"destination":"Davao",
                 "paymentMethod":"CASH","lines":[{"packageId":%d,"quantity":1}]}""".formatted(branchOf(pkg), pkg);
        ExecutorService burst = Executors.newFixedThreadPool(PARALLEL_CREATES);
        var go = new CountDownLatch(1);
        List<Future<long[]>> futures = new ArrayList<>();
        for (int i = 0; i < PARALLEL_CREATES; i++) {
            futures.add(burst.submit(() -> {
                var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/deliveries"))
                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                        .header("Idempotency-Key", key).timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                go.await();
                long t0 = System.nanoTime();
                int status;
                try {
                    status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                } catch (Exception e) {
                    status = -1;
                }
                return new long[] {status, (System.nanoTime() - t0) / 1_000_000};
            }));
        }
        go.countDown();

        // Thread dump in the middle of the hold (or shortly after the start for the fast case).
        Thread.sleep(holdMs == 0 ? 150 : Math.min(holdMs / 2, 1_500));
        int blockedInCreate = 0;
        int waitingForPool = 0;
        for (var info : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)) {
            String stack = String.join("\n", java.util.Arrays.stream(info.getStackTrace()).map(StackTraceElement::toString).toList());
            if (stack.contains("DeliveryService.create") && stack.contains("org.postgresql")) blockedInCreate++;
            if (stack.contains("HikariPool.getConnection")) waitingForPool++;
        }

        Map<Long, Integer> statuses = new TreeMap<>();
        List<Long> createMs = new ArrayList<>();
        for (var f : futures) {
            long[] r = f.get(90, TimeUnit.SECONDS);
            statuses.merge(r[0], 1, Integer::sum);
            createMs.add(r[1]);
        }
        burst.shutdown();
        if (releaser != null) releaser.join(15_000);
        Thread.sleep(300);   // let the probe record the recovery
        stopProbe.set(true);
        probe.join(35_000);
        stopSampling.set(true);
        sampler.join(2_000);

        Collections.sort(createMs);
        List<Long> probes = new ArrayList<>(probeMs);
        Collections.sort(probes);
        System.out.printf("EVIDENCE scenario=%s hold=%dms poolMax=%d creates=%s createMs[min=%d p50=%d max=%d] "
                        + "probe[n=%d failed=%d p50=%dms p99=%dms max=%dms] pool[maxActive=%d maxAwaitingConnection=%d] "
                        + "postgres[maxUngrantedAdvisoryLocks=%d maxSessionsWaitingOnAdvisory=%d] "
                        + "threadDump[blockedInDeliveryCreate=%d waitingForPoolConnection=%d]%n",
                scenario, holdMs, hikari.getMaximumPoolSize(), statuses, createMs.getFirst(),
                createMs.get(createMs.size() / 2), createMs.getLast(), probes.size(), probeFailures.get(),
                percentile(probes, 50), percentile(probes, 99), probes.getLast(), maxActive.get(), maxAwaiting.get(),
                maxUngranted.get(), maxWaitingSessions.get(), blockedInCreate, waitingForPool);

        // Sanity only (this is a measurement). The key never produces more than one delivery; it produces none when the
        // simulated holder never creates one and every waiter was told to retry (409). Each create got an answer that the
        // lock-wait bound allows: a delivery or a retryable 409, never a 500, a 503 or a client timeout. Unrelated
        // requests were all served (slowly at worst).
        assertThat(jdbc.queryForObject("select count(*) from delivery_idempotency where idempotency_key = ?", Integer.class, key))
                .isLessThanOrEqualTo(1);
        assertThat(statuses.keySet()).as("create statuses").isSubsetOf(200L, 201L, 409L);
        assertThat(probeFailures.get()).as("unrelated requests that failed").isZero();
    }

    private static int count(Connection c, String sql) throws Exception {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static long percentile(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }

    /** A branch with stock and a one-line package; returns the package id. */
    private long fixture(ApiTestClient api, String token) {
        String tag = UUID.randomUUID().toString();
        long branch = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "ev-" + tag);
        long product = jdbc.queryForObject("insert into product (name, unit) values (?, 'head') returning id", Long.class, "ev-" + tag);
        long pkg = jdbc.queryForObject("insert into sales_package (sku, name, price) values (?, ?, 100) returning id", Long.class,
                "EV-" + tag, "Ev " + tag);
        jdbc.update("insert into package_item (package_id, product_id, quantity) values (?, ?, 1)", pkg, product);
        var r = api.call("POST", "/api/stock/movements", token, """
                {"productId":%d,"branchId":%d,"type":"PURCHASE","quantityDelta":1000,"unitCost":10.00,"paymentMethod":"CASH","occurredOn":"2021-01-02"}"""
                .formatted(product, branch));
        assertThat(r.statusCode()).isEqualTo(201);
        branchByPackage.put(pkg, branch);
        return pkg;
    }

    private final Map<Long, Long> branchByPackage = new java.util.concurrent.ConcurrentHashMap<>();

    private long branchOf(long pkg) {
        return branchByPackage.get(pkg);
    }
}
