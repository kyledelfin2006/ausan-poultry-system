package kyle.poultry.report;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import kyle.poultry.ApiTestClient;
import kyle.poultry.TestcontainersConfiguration;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * EVIDENCE HARNESS for audit item PERF-3 (dashboard query fan-out and the unbounded history scan in
 * {@code retainedOnCancelled}). It measures; it does not fix.
 *
 * <p>It seeds a large ledger (about 1,000,000 rows by default: for each of 300,000 deliveries a sale, a cost of goods
 * and a payment, 10 % of them cancelled with reversals, plus 40,000 expenses), runs {@code ANALYZE}, and reports:
 * <ol>
 *   <li>how many SQL statements, and which queries, one {@code GET /api/reports/dashboard} runs (owner and assistant);</li>
 *   <li>the dashboard's response time (p50, p95, max over 30 calls after warm-up);</li>
 *   <li>{@code EXPLAIN (ANALYZE, BUFFERS)} of the ledger queries the dashboard depends on, written as the same SQL the
 *       repository produces.</li>
 * </ol>
 *
 * <p>Switched off unless run with {@code -Devidence=true} (optionally {@code -Devidence.sources=N}):
 * {@code ./mvnw test -Dtest=DashboardEvidenceTests -Devidence=true}. It adds rows to the test database only, which is
 * discarded with the test container.
 */
@Tag("integration")
@Tag("evidence")
@EnabledIfSystemProperty(named = "evidence", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DashboardEvidenceTests {
    private static final String DASHBOARD = "/api/reports/dashboard";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void measureDashboardOnALargeLedger() {
        int sources = Integer.getInteger("evidence.sources", 300_000);
        long ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class,
                TestcontainersConfiguration.OWNER_USERNAME);
        seed(sources, ownerId);
        long rows = jdbc.queryForObject("select count(*) from financial_entry", Long.class);
        System.out.println("EVIDENCE3 ledger rows=" + rows + " (deliveries=" + sources + ")");

        var api = new ApiTestClient(port);
        String owner = api.owner();
        String assistant = api.assistant();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        // Warm the JVM and the database cache, then count statements for one call each.
        for (int i = 0; i < 3; i++) api.call("GET", DASHBOARD, owner, null);
        statementReport("owner", statistics, owner);
        statementReport("assistant", statistics, assistant);

        timingReport("owner", owner);
        timingReport("assistant", assistant);

        explain("totalsByType, month to date", """
                select type, sum(amount) from financial_entry
                 where occurred_on between date_trunc('month', current_date)::date and current_date group by type""");
        explain("totalsByType, today", """
                select type, sum(amount) from financial_entry
                 where occurred_on between current_date and current_date group by type""");
        explain("retainedOnCancelled as of today", """
                select coalesce(sum(case when e.type = 'PAYMENT_IN' then e.amount else -e.amount end), 0)
                  from financial_entry e
                 where e.type in ('PAYMENT_IN', 'REFUND') and e.occurred_on <= current_date
                   and exists (select 1 from financial_entry r
                                where r.type = 'SALE_REVERSAL' and r.occurred_on <= current_date
                                  and r.source_type = e.source_type and r.source_id = e.source_id)""");

        // PERF-3 fix 3: the rewritten query must give exactly the old query's figure, and use the partial index.
        String newRetained = """
                select coalesce(sum(case when e.type = 'PAYMENT_IN' then e.amount else -e.amount end), 0)
                  from (select distinct source_type, source_id from financial_entry
                         where type = 'SALE_REVERSAL' and occurred_on <= current_date) r
                  join financial_entry e on e.source_type = r.source_type and e.source_id = r.source_id
                 where e.type in ('PAYMENT_IN', 'REFUND') and e.occurred_on <= current_date""";
        explain("retainedOnCancelled rewritten", newRetained);
        var oldValue = jdbc.queryForObject("""
                select coalesce(sum(case when e.type = 'PAYMENT_IN' then e.amount else -e.amount end), 0)
                  from financial_entry e
                 where e.type in ('PAYMENT_IN', 'REFUND') and e.occurred_on <= current_date
                   and exists (select 1 from financial_entry r
                                where r.type = 'SALE_REVERSAL' and r.occurred_on <= current_date
                                  and r.source_type = e.source_type and r.source_id = e.source_id)""", java.math.BigDecimal.class);
        var newValue = jdbc.queryForObject(newRetained, java.math.BigDecimal.class);
        System.out.println("EVIDENCE3 retained old=" + oldValue + " new=" + newValue);
        assertThat(newValue).isEqualByComparingTo(oldValue);

        // Sanity only: the endpoint answers for both roles.
        assertThat(api.call("GET", DASHBOARD, owner, null).statusCode()).isEqualTo(200);
        assertThat(api.call("GET", DASHBOARD, assistant, null).statusCode()).isEqualTo(200);
    }

    /** Builds the ledger with plain SQL: sources are far above any real id so other data is not disturbed. */
    private void seed(int sources, long userId) {
        long base = 1_000_000_000L;
        jdbc.update("""
                insert into financial_entry (type, cash_direction, amount, method, occurred_on, source_type, source_id, created_by, created_at)
                select t.type, t.dir, 100.00, t.method, current_date - (g %% 1100), 'DELIVERY', g + %d, ?, now()
                  from generate_series(1, ?) g
                 cross join (values ('SALE', 'NONE', null::varchar), ('COST_OF_GOODS', 'NONE', null::varchar),
                                    ('PAYMENT_IN', 'IN', 'CASH')) as t(type, dir, method)""".formatted(base), userId, sources);
        // Every tenth delivery is cancelled: reverse its sale and its cost.
        for (String[] pair : new String[][] {{"SALE", "SALE_REVERSAL"}, {"COST_OF_GOODS", "COGS_REVERSAL"}}) {
            jdbc.update("""
                    insert into financial_entry (type, cash_direction, amount, method, occurred_on, source_type, source_id, reverses_id, created_by, created_at)
                    select '%s', 'NONE', amount, null, occurred_on, source_type, source_id, id, ?, now()
                      from financial_entry where type = '%s' and source_id > %d and source_id %% %d = 0"""
                    .formatted(pair[1], pair[0], base, Integer.getInteger("evidence.cancelEvery", 10)), userId);
        }
        jdbc.update("""
                insert into financial_entry (type, cash_direction, amount, method, occurred_on, created_by, created_at)
                select 'EXPENSE', 'OUT', 50.00, 'CASH', current_date - (g %% 1100), ?, now() from generate_series(1, 40000) g"""
                .formatted(), userId);
        jdbc.execute("analyze financial_entry");
    }

    /** Statements and queries behind a single dashboard call. */
    private void statementReport(String role, Statistics statistics, String token) {
        var api = new ApiTestClient(port);
        statistics.clear();
        long t0 = System.nanoTime();
        var r = api.call("GET", DASHBOARD, token, null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.statusCode()).isEqualTo(200);
        System.out.println("EVIDENCE3 " + role + " one call: statements=" + statistics.getPrepareStatementCount()
                + " queryExecutions=" + statistics.getQueryExecutionCount() + " entityLoads=" + statistics.getEntityLoadCount()
                + " ms=" + ms);
        record Q(String text, long count, long totalMs) {}
        var queries = new ArrayList<Q>();
        for (String q : statistics.getQueries()) {
            var qs = statistics.getQueryStatistics(q);
            queries.add(new Q(q.replaceAll("\\s+", " "), qs.getExecutionCount(), qs.getExecutionTotalTime()));
        }
        queries.sort(Comparator.comparingLong(Q::totalMs).reversed());
        for (Q q : queries)
            System.out.println("EVIDENCE3   " + role + " query x" + q.count() + " totalMs=" + q.totalMs() + " :: "
                    + q.text().substring(0, Math.min(110, q.text().length())));
    }

    /** Response-time distribution over repeated calls. */
    private void timingReport(String role, String token) {
        var api = new ApiTestClient(port);
        List<Long> ms = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long t0 = System.nanoTime();
            api.call("GET", DASHBOARD, token, null);
            ms.add((System.nanoTime() - t0) / 1_000_000);
        }
        Collections.sort(ms);
        System.out.println("EVIDENCE3 " + role + " dashboard latency over 30 calls: p50=" + ms.get(14) + "ms p95="
                + ms.get(28) + "ms max=" + ms.get(29) + "ms");
    }

    /** Prints the planner's summary lines for one query. */
    private void explain(String label, String sql) {
        List<String> plan = jdbc.queryForList("explain (analyze, buffers) " + sql, String.class);
        System.out.println("EVIDENCE3 explain [" + label + "]");
        plan.stream().limit(Integer.getInteger("evidence.planLines", 18)).forEach(l -> System.out.println("EVIDENCE3   | " + l));
        plan.stream().filter(l -> l.startsWith("Execution Time") || l.startsWith("Planning Time"))
                .forEach(l -> System.out.println("EVIDENCE3   > " + l));
    }
}
