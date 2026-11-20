package kyle.poultry.audit;

import static kyle.poultry.support.HttpApiClient.PASSWORD;
import static kyle.poultry.support.HttpApiClient.field;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.support.HttpApiClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * End-to-end tests of the audit trail over real HTTP and a real Postgres: what is recorded, who is recorded, what is
 * never recorded, the owner-only read endpoint, and the database-level append-only guarantee. Names are random so the
 * shared database never causes clashes.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuditTrailTests {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired AuditApi auditApi;

    /** {@return a client for the running application} */
    HttpApiClient api() { return new HttpApiClient(port); }

    /**
     * Reads the audit rows of one record, oldest first.
     *
     * @param type the entity type name
     * @param id   the record id
     * @return one map per row (action, summary, actor_username, actor_user_id, request_id)
     */
    List<Map<String, Object>> rows(String type, long id) {
        return jdbc.queryForList("select action, summary, actor_username, actor_user_id, request_id from audit_log "
                + "where entity_type = ? and entity_id = ? order by id", type, id);
    }

    /** Product create and update are recorded with the acting owner and only the fields that really changed. */
    @Test
    void productChangesAreRecordedWithTheActorAndOnlyChangedFields() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        String name = "Feed-" + Long.toHexString(System.nanoTime());
        var created = api.call("POST", "/api/products", owner, "{\"name\":\"%s\",\"unit\":\"bag\",\"defaultPrice\":5.00}".formatted(name));
        assertThat(created.statusCode()).isEqualTo(201);
        long id = Long.parseLong(field(created.body(), "id"));

        assertThat(api.call("PATCH", "/api/products/" + id, owner, "{\"defaultPrice\":6.50}").statusCode()).isEqualTo(200);
        // A PATCH that changes nothing (same value, other fields left out) leaves no entry.
        assertThat(api.call("PATCH", "/api/products/" + id, owner, "{\"defaultPrice\":6.5,\"name\":\"%s\"}".formatted(name)).statusCode()).isEqualTo(200);
        assertThat(api.call("PATCH", "/api/products/" + id, owner, "{\"active\":false}").statusCode()).isEqualTo(200);

        var rows = rows("PRODUCT", id);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("action", "CREATED").containsEntry("actor_username", TestcontainersConfiguration.OWNER_USERNAME);
        assertThat((String) rows.get(0).get("summary")).contains(name);
        assertThat(rows.get(1)).containsEntry("action", "UPDATED").containsEntry("summary", "defaultPrice: 5.00 -> 6.50");
        assertThat(rows.get(2)).containsEntry("summary", "active: true -> false");
        assertThat(rows.get(1).get("actor_user_id")).isNotNull();
    }

    /** Branch and package create and update are recorded too. */
    @Test
    void branchAndPackageChangesAreRecorded() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        var branch = api.call("POST", "/api/branches", owner, "{\"name\":\"Branch-%s\"}".formatted(Long.toHexString(System.nanoTime())));
        assertThat(branch.statusCode()).isEqualTo(201);
        long branchId = Long.parseLong(field(branch.body(), "id"));
        assertThat(api.call("PATCH", "/api/branches/" + branchId, owner, "{\"active\":false}").statusCode()).isEqualTo(200);
        assertThat(rows("BRANCH", branchId)).extracting(r -> r.get("action")).containsExactly("CREATED", "UPDATED");
        assertThat(rows("BRANCH", branchId).get(1)).containsEntry("summary", "active: true -> false");

        var product = api.call("POST", "/api/products", owner, "{\"name\":\"P-%s\",\"unit\":\"pc\"}".formatted(Long.toHexString(System.nanoTime())));
        long productId = Long.parseLong(field(product.body(), "id"));
        String sku = "SKU-" + Long.toHexString(System.nanoTime());
        var pkg = api.call("POST", "/api/packages", owner,
                "{\"sku\":\"%s\",\"name\":\"Pkg %s\",\"price\":1000.00,\"items\":[{\"productId\":%d,\"quantity\":2}]}".formatted(sku, sku, productId));
        assertThat(pkg.statusCode()).as(pkg.body()).isEqualTo(201);
        long packageId = Long.parseLong(field(pkg.body(), "id"));
        assertThat(api.call("PATCH", "/api/packages/" + packageId, owner, "{\"price\":1250.50}").statusCode()).isEqualTo(200);

        var rows = rows("PACKAGE", packageId);
        assertThat(rows).extracting(r -> r.get("action")).containsExactly("CREATED", "UPDATED");
        assertThat((String) rows.get(0).get("summary")).contains(sku).contains("1000.00");
        assertThat(rows.get(1)).containsEntry("summary", "price: 1000.00 -> 1250.50");
    }

    /** User administration is recorded, and no entry ever contains a password, a hash or a token. */
    @Test
    void userChangesAreRecordedWithoutAnySecrets() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        var user = api.createUserWithId(owner, "ASSISTANT");
        assertThat(api.call("PATCH", "/api/users/" + user.id(), owner, "{\"role\":\"OWNER\",\"displayName\":\"Promoted\"}").statusCode()).isEqualTo(200);
        assertThat(api.call("POST", "/api/users/" + user.id() + "/password", owner, "{\"newPassword\":\"reset-by-the-owner-2\"}").statusCode()).isEqualTo(204);
        String token = api.login(user.username(), "reset-by-the-owner-2");
        assertThat(api.call("POST", "/api/auth/password", token,
                "{\"currentPassword\":\"reset-by-the-owner-2\",\"newPassword\":\"changed-by-the-user-3\"}").statusCode()).isEqualTo(204);
        api.call("POST", "/api/auth/logout", api.login(user.username(), "changed-by-the-user-3"), null);

        var rows = rows("USER", user.id());
        assertThat(rows).extracting(r -> r.get("action"))
                .containsExactly("CREATED", "UPDATED", "PASSWORD_RESET", "PASSWORD_CHANGED", "SIGNED_OUT");
        assertThat(rows.get(1).get("summary").toString()).contains("displayName: Test -> Promoted", "role: ASSISTANT -> OWNER");
        // The password-changing actions are attributed to the people who did them.
        assertThat(rows.get(2)).containsEntry("actor_username", TestcontainersConfiguration.OWNER_USERNAME);
        assertThat(rows.get(3)).containsEntry("actor_username", user.username());
        // No secret material anywhere in the trail.
        for (var row : rows) {
            assertThat(row.get("summary").toString()).doesNotContain(PASSWORD, "reset-by-the-owner", "changed-by-the-user", "$2", "eyJ");
        }
    }

    /** Changes that fail leave no trace: the entry commits or rolls back together with the change. */
    @Test
    void failedChangesAreNotRecorded() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        int before = jdbc.queryForObject("select count(*) from audit_log", Integer.class);

        // Unknown product inside a package: 404, nothing saved, nothing audited.
        String sku = "BAD-" + Long.toHexString(System.nanoTime());
        assertThat(api.call("POST", "/api/packages", owner,
                "{\"sku\":\"%s\",\"name\":\"%s\",\"price\":10.00,\"items\":[{\"productId\":999999,\"quantity\":1}]}".formatted(sku, sku)).statusCode()).isEqualTo(404);
        // Duplicate product name: 409, nothing audited.
        String name = "Dup-" + Long.toHexString(System.nanoTime());
        assertThat(api.call("POST", "/api/products", owner, "{\"name\":\"%s\",\"unit\":\"kg\"}".formatted(name)).statusCode()).isEqualTo(201);
        int afterFirst = jdbc.queryForObject("select count(*) from audit_log", Integer.class);
        assertThat(afterFirst).isEqualTo(before + 1);
        assertThat(api.call("POST", "/api/products", owner, "{\"name\":\"%s\",\"unit\":\"kg\"}".formatted(name)).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from audit_log", Integer.class)).isEqualTo(afterFirst);
        // A refused password change does not audit either.
        assertThat(api.call("PATCH", "/api/users/99999999", owner, "{\"active\":false}").statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from audit_log", Integer.class)).isEqualTo(afterFirst);
    }

    /** The first owner created at startup is attributed to the system, not to a user. */
    @Test
    void bootstrapOwnerIsRecordedAsTheSystem() {
        Integer n = jdbc.queryForObject("select count(*) from audit_log where actor_username = 'system' and actor_user_id is null "
                + "and entity_type = 'USER' and action = 'CREATED' and summary like ?", Integer.class,
                "username: " + TestcontainersConfiguration.OWNER_USERNAME + "%");
        assertThat(n).isEqualTo(1);
    }

    /** The request id of the HTTP call is stored, so an entry can be matched to the log lines of that call. */
    @Test
    void entriesCarryTheRequestId() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        String requestId = "audit-req-" + Long.toHexString(System.nanoTime());
        var r = api.call("POST", "/api/branches", owner, "{\"name\":\"B-%s\"}".formatted(Long.toHexString(System.nanoTime())), "X-Request-Id", requestId);
        long id = Long.parseLong(field(r.body(), "id"));

        assertThat(rows("BRANCH", id).getFirst()).containsEntry("request_id", requestId);
        var read = api.call("GET", "/api/audit?entityType=BRANCH&entityId=" + id, owner, null);
        assertThat(read.body()).contains(requestId);
    }

    /** Only the owner may read the trail: anonymous is 401, assistant is 403 and sees nothing. */
    @Test
    void onlyTheOwnerCanReadTheTrail() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        String assistant = api.login(api.createUser(owner, "ASSISTANT"), PASSWORD);

        assertThat(api.call("GET", "/api/audit", null, null).statusCode()).isEqualTo(401);
        var denied = api.call("GET", "/api/audit", assistant, null);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).doesNotContain("summary");
        assertThat(api.call("GET", "/api/audit", owner, null).statusCode()).isEqualTo(200);
        // Read-only: there is no way to change or delete an entry over HTTP.
        assertThat(api.call("DELETE", "/api/audit/1", owner, null).statusCode()).isIn(404, 405);
        assertThat(api.call("POST", "/api/audit", owner, "{}").statusCode()).isEqualTo(405);
    }

    /** Filters, newest-first order, paging and bad input behave as documented. */
    @Test
    void searchFiltersOrdersAndValidates() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        String name = "Filter-" + Long.toHexString(System.nanoTime());
        var created = api.call("POST", "/api/products", owner, "{\"name\":\"%s\",\"unit\":\"kg\"}".formatted(name));
        long id = Long.parseLong(field(created.body(), "id"));
        api.call("PATCH", "/api/products/" + id, owner, "{\"unit\":\"box\"}");
        api.call("PATCH", "/api/products/" + id, owner, "{\"unit\":\"crate\"}");
        long ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class, TestcontainersConfiguration.OWNER_USERNAME);

        // Entity filter + newest first: the highest id comes first.
        var page = api.call("GET", "/api/audit?entityType=PRODUCT&entityId=" + id, owner, null);
        assertThat(page.statusCode()).isEqualTo(200);
        List<Long> ids = new ArrayList<>();
        var m = Pattern.compile("\"id\":(\\d+)").matcher(page.body());
        while (m.find()) ids.add(Long.parseLong(m.group(1)));
        assertThat(ids).hasSize(3).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(page.body()).contains("\"totalElements\":3").contains("unit: kg -> box");

        // Other filters narrow the same data; a different entity type or actor matches nothing for this record.
        assertThat(api.call("GET", "/api/audit?entityType=BRANCH&entityId=" + id + "&actorId=" + ownerId, owner, null).body())
                .contains("\"totalElements\":0");
        assertThat(api.call("GET", "/api/audit?entityType=PRODUCT&entityId=" + id + "&actorId=" + ownerId, owner, null).body())
                .contains("\"totalElements\":3");
        assertThat(api.call("GET", "/api/audit?entityType=PRODUCT&entityId=" + id + "&actorId=" + (ownerId + 1000), owner, null).body())
                .contains("\"totalElements\":0");

        // Business-day range (Manila): today includes the entries, an old range does not, the day boundary is inclusive.
        LocalDate today = LocalDate.now(clock);
        assertThat(api.call("GET", "/api/audit?entityId=%d&entityType=PRODUCT&from=%s&to=%s".formatted(id, today, today), owner, null).body())
                .contains("\"totalElements\":3");
        assertThat(api.call("GET", "/api/audit?entityId=%d&entityType=PRODUCT&from=2000-01-01&to=2000-01-02".formatted(id), owner, null).body())
                .contains("\"totalElements\":0");
        assertThat(api.call("GET", "/api/audit?entityId=%d&entityType=PRODUCT&from=%s".formatted(id, today.plusDays(1)), owner, null).body())
                .contains("\"totalElements\":0");

        // Paging.
        var first = api.call("GET", "/api/audit?entityType=PRODUCT&entityId=" + id + "&size=2&page=0", owner, null);
        assertThat(first.body()).contains("\"totalElements\":3", "\"totalPages\":2");

        // Bad input: inverted range 422; bad enum, bad date, size out of range 400.
        assertThat(api.call("GET", "/api/audit?from=2026-02-01&to=2026-01-01", owner, null).statusCode()).isEqualTo(422);
        assertThat(api.call("GET", "/api/audit?entityType=NOPE", owner, null).statusCode()).isEqualTo(400);
        assertThat(api.call("GET", "/api/audit?from=yesterday", owner, null).statusCode()).isEqualTo(400);
        assertThat(api.call("GET", "/api/audit?size=101", owner, null).statusCode()).isEqualTo(400);
        assertThat(api.call("GET", "/api/audit?size=0", owner, null).statusCode()).isEqualTo(400);
        assertThat(api.call("GET", "/api/audit?page=-1", owner, null).statusCode()).isEqualTo(400);
    }

    /** The database itself refuses to change or remove audit rows, whatever the application does. */
    @Test
    void theTableIsAppendOnlyInTheDatabase() {
        assertThat(jdbc.queryForObject("select count(*) from audit_log", Integer.class)).isPositive();
        assertThatThrownBy(() -> jdbc.update("update audit_log set summary = 'tampered'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_log"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("truncate audit_log"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    /** Writing an entry outside a transaction is a programming error and is refused, so no change can skip its entry. */
    @Test
    void recordingRequiresTheCallersTransaction() {
        assertThatThrownBy(() -> auditApi.record(AuditAction.CREATED, AuditEntity.PRODUCT, 1, "x"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
