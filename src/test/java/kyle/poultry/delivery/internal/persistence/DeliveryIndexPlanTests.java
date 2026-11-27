package kyle.poultry.delivery.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kyle.poultry.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL query plans keep the delivery list indexes useful at a representative table size. */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeliveryIndexPlanTests {
    private static final int SAMPLE_DELIVERIES = 100_000;

    @Autowired JdbcTemplate jdbc;

    /** The unfiltered and branch-filtered list shapes use their matching indexes with 100,000 deliveries. */
    @Test
    @Transactional
    @Rollback
    void deliveryListPlansUseIndexesAtOneHundredThousandRows() {
        List<Long> branches = jdbc.queryForList("select id from branch order by id limit 2", Long.class);
        assertThat(branches).hasSize(2);
        long ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class,
                TestcontainersConfiguration.OWNER_USERNAME);

        jdbc.update("""
                insert into delivery (code, status, customer_name, driver_name, from_branch_id, destination,
                                      started_at, total, payment_method, created_by, created_at)
                select 'PLAN-' || lpad(n::text, 6, '0'), 'ACTIVE', 'Plan customer', 'Plan driver',
                       case when n % 100 = 0 then ? else ? end, 'Plan destination',
                       timestamptz '2025-01-01 00:00:00+00' + n * interval '1 second',
                       100.00, 'CASH', ?, now()
                  from generate_series(1, 100000) as sample(n)
                """, branches.get(0), branches.get(1), ownerId);
        assertThat(jdbc.queryForObject("select count(*) from delivery where code like 'PLAN-%'", Long.class))
                .isEqualTo((long) SAMPLE_DELIVERIES);
        jdbc.execute("analyze delivery");

        String defaultPlan = explain("select id from delivery order by started_at desc, id desc limit 50");
        // A 1% branch makes the branch-specific index compete with scanning the global order and filtering.
        String branchPlan = explain("select id from delivery where from_branch_id = ? "
                + "order by started_at desc, id desc limit 50", branches.get(0));

        assertThat(defaultPlan).contains("ix_delivery_started_id");
        assertThat(branchPlan).contains("ix_delivery_branch_started");
    }

    /** Runs the actual PostgreSQL plan with execution and buffer details for one delivery-list query shape. */
    private String explain(String sql, Object... parameters) {
        return String.join("\n", jdbc.query("explain (analyze, buffers) " + sql,
                (row, index) -> row.getString(1), parameters));
    }
}
