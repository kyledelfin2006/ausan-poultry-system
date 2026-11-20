package kyle.poultry.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import kyle.poultry.ApiTestClient;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.identity.Actor;
import kyle.poultry.identity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * HTTP tests (real PostgreSQL) of {@code GET /api/finance/entries.csv}: content, quoting of hostile descriptions,
 * the type filter, walking more than one internal page, roles and period validation. The data is written to 2015 and
 * 2016 dates that no other test uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class EntriesExportTests {

    @LocalServerPort int port;
    @Autowired FinanceApi finance;
    @Autowired JdbcTemplate jdbc;

    ApiTestClient api;
    Actor owner;

    /** The owner as the acting user and a client for the running server. */
    @BeforeEach
    void setUp() {
        api = new ApiTestClient(port);
        long ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class, TestcontainersConfiguration.OWNER_USERNAME);
        owner = new Actor(ownerId, Role.OWNER);
    }

    /** Records an expense of 10.00 with the given description. */
    void expense(String description, String date) {
        finance.recordExpense(new BigDecimal("10.00"), PaymentMethod.CASH, LocalDate.parse(date), description, owner);
    }

    /** Descriptions are quoted when needed and made safe against spreadsheet formulas; non-ASCII text survives. */
    @Test
    void descriptionsAreQuotedAndFormulasNeutralised() {
        expense("=HYPERLINK(\"http://evil.example\")", "2015-05-10");
        expense("fuel, \"premium\"", "2015-05-11");
        expense("Peña hauling", "2015-05-12");

        var r = api.call("GET", "/api/finance/entries.csv?from=2015-05-01&to=2015-05-31", api.assistant(), null);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(r.headers().firstValue("Content-Disposition").orElse("")).contains("attachment")
                .contains("ledger-entries-2015-05-01-to-2015-05-31.csv");
        assertThat(r.body()).startsWith("﻿id,occurredOn,type,cashDirection,amount,paymentMethod,description,sourceType,sourceId,reversesId,createdBy,createdAt\r\n");
        assertThat(r.body()).contains(",EXPENSE,OUT,10.00,CASH,\"'=HYPERLINK(\"\"http://evil.example\"\")\",")
                .contains(",\"fuel, \"\"premium\"\"\",")
                .contains(",Peña hauling,");
        // Newest first: 12 May before 11 May before 10 May.
        assertThat(r.body().indexOf("2015-05-12")).isLessThan(r.body().indexOf("2015-05-11"));
        assertThat(r.body().indexOf("2015-05-11")).isLessThan(r.body().indexOf("2015-05-10"));
    }

    /** The type filter keeps one type; a type with no entries gives just the header. */
    @Test
    void typeFilterAndEmptyResult() {
        expense("only expense", "2015-06-10");

        var expenses = api.call("GET", "/api/finance/entries.csv?from=2015-06-01&to=2015-06-30&type=EXPENSE", api.owner(), null);
        var refunds = api.call("GET", "/api/finance/entries.csv?from=2015-06-01&to=2015-06-30&type=REFUND", api.owner(), null);

        assertThat(expenses.body()).contains("only expense");
        assertThat(refunds.statusCode()).isEqualTo(200);
        assertThat(refunds.body().lines().count()).isEqualTo(1);
        // Inclusive ends: a one-day period on the day of the entry finds it, the day before does not.
        assertThat(api.call("GET", "/api/finance/entries.csv?from=2015-06-10&to=2015-06-10", api.owner(), null).body()).contains("only expense");
        assertThat(api.call("GET", "/api/finance/entries.csv?from=2015-06-09&to=2015-06-09", api.owner(), null).body()).doesNotContain("only expense");
    }

    /** More entries than one internal page (1,000) are all exported, none twice and none missing. */
    @Test
    void exportsMoreThanOnePage() {
        for (int i = 0; i < 1005; i++) expense("bulk " + i, "2016-02-10");

        var r = api.call("GET", "/api/finance/entries.csv?from=2016-02-10&to=2016-02-10", api.owner(), null);

        assertThat(r.statusCode()).isEqualTo(200);
        var rows = Arrays.stream(r.body().split("\r\n")).filter(l -> l.contains(",bulk ")).toList();
        assertThat(rows).hasSize(1005).doesNotHaveDuplicates();
    }

    /** Roles, authentication and period rules (backwards, over 366 days). */
    @Test
    void rolesAndValidation() {
        assertThat(api.call("GET", "/api/finance/entries.csv?from=2015-05-01&to=2015-05-31", null, null).statusCode()).isEqualTo(401);
        assertThat(api.call("GET", "/api/finance/entries.csv?from=2015-05-31&to=2015-05-01", api.owner(), null).statusCode()).isEqualTo(422);
        // 2016 is a leap year: 366 days is the longest allowed, 367 is not.
        assertThat(api.call("GET", "/api/finance/entries.csv?from=2016-01-01&to=2016-12-31", api.owner(), null).statusCode()).isEqualTo(200);
        assertThat(api.call("GET", "/api/finance/entries.csv?from=2016-01-01&to=2017-01-01", api.owner(), null).statusCode()).isEqualTo(422);
        assertThat(api.call("GET", "/api/finance/entries.csv?to=2015-05-31", api.owner(), null).statusCode()).isEqualTo(400);
        assertThat(api.call("GET", "/api/finance/entries.csv?from=2015-05-01&to=2015-05-31&type=NOPE", api.owner(), null).statusCode()).isEqualTo(400);
    }
}
