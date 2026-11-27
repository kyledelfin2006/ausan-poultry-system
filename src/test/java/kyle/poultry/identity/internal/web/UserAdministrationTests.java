package kyle.poultry.identity.internal.web;

import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.identity.api.model.Role;
import kyle.poultry.identity.internal.security.LoginThrottle;
import kyle.poultry.identity.internal.service.UserService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.shared.error.RuleViolation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end HTTP tests of user administration, immediate revocation, password changes and login throttling against a
 * real Postgres. Users get random names so the shared database never causes clashes; tests that need a single owner
 * restore the others in a finally block.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class UserAdministrationTests {

    static final String PASSWORD = "a-long-enough-password";

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
     * Sends a login request and returns the raw response, whatever its status.
     *
     * @param user login name
     * @param pass plain password
     * @return the response
     */
    HttpResponse<String> loginResponse(String user, String pass) throws Exception {
        return call("POST", "/api/auth/login", null, "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, pass));
    }

    /**
     * Logs in over HTTP and returns the token.
     *
     * @param user login name
     * @param pass plain password
     * @return the JWT; fails the test unless login answers 200
     */
    String login(String user, String pass) throws Exception {
        var r = loginResponse(user, pass);
        assertThat(r.statusCode()).isEqualTo(200);
        var m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(r.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    /**
     * Logs in as the bootstrap owner.
     *
     * @return the owner's token
     */
    String ownerToken() throws Exception {
        return login(TestcontainersConfiguration.OWNER_USERNAME, TestcontainersConfiguration.OWNER_PASSWORD);
    }

    /**
     * A user created by a test.
     *
     * @param id database id
     * @param username the random login name
     */
    /**
     * A user created by a test.
     *
     * @param id database id
     * @param username the random login name
     */
    record Created(long id, String username) {}

    /**
     * Creates a user through the API.
     *
     * @param ownerToken token of an owner
     * @param role OWNER or ASSISTANT
     * @return the new user's id and random username
     */
    Created createUser(String ownerToken, String role) throws Exception {
        String username = role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        var r = call("POST", "/api/users", ownerToken,
                "{\"username\":\"%s\",\"password\":\"%s\",\"displayName\":\"Test\",\"role\":\"%s\"}".formatted(username, PASSWORD, role));
        assertThat(r.statusCode()).isEqualTo(201);
        var m = Pattern.compile("\"id\":(\\d+)").matcher(r.body());
        assertThat(m.find()).isTrue();
        return new Created(Long.parseLong(m.group(1)), username);
    }

    /**
     * The owner can list users and the response never contains passwords or hashes; an assistant gets 403 for list and
     * update.
     */
    @Test
    void ownerListsUsersWithoutPasswordHashesAndAssistantCannot() throws Exception {
        String owner = ownerToken();
        var assistant = createUser(owner, "ASSISTANT");

        var list = call("GET", "/api/users", owner, null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(assistant.username(), "\"role\":\"ASSISTANT\"", "\"active\":true")
                // "mustChangePassword" is a legitimate flag; the secret-bearing fields are the hash and the raw password.
                .doesNotContainIgnoringCase("passwordHash").doesNotContain("\"password\"").doesNotContain("$2a$");

        assertThat(call("GET", "/api/users", login(assistant.username(), PASSWORD), null).statusCode()).isEqualTo(403);
        assertThat(call("PATCH", "/api/users/" + assistant.id(), login(assistant.username(), PASSWORD), "{\"active\":false}").statusCode())
                .isEqualTo(403);
    }

    /**
     * PATCH changes only the fields sent, trims the name, and answers 422 for a blank name and 404 for an unknown
     * user.
     */
    @Test
    void patchChangesOnlyTheGivenFields() throws Exception {
        String owner = ownerToken();
        var user = createUser(owner, "ASSISTANT");

        var r = call("PATCH", "/api/users/" + user.id(), owner, "{\"displayName\":\"  New Name \"}");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"displayName\":\"New Name\"", "\"role\":\"ASSISTANT\"", "\"active\":true");

        assertThat(call("PATCH", "/api/users/" + user.id(), owner, "{\"displayName\":\"  \"}").statusCode()).isEqualTo(422);
        assertThat(call("PATCH", "/api/users/99999999", owner, "{\"active\":false}").statusCode()).isEqualTo(404);
    }

    /**
     * Deactivating a user makes their still-valid token answer 401 immediately and blocks login; reactivation lets
     * them log in again but never revives the old token.
     */
    @Test
    void deactivatedUserCannotLogInAndStillValidTokenIsRejectedAtOnce() throws Exception {
        String owner = ownerToken();
        var user = createUser(owner, "ASSISTANT");
        String token = login(user.username(), PASSWORD);
        assertThat(call("GET", "/api/products", token, null).statusCode()).isEqualTo(200);

        assertThat(call("PATCH", "/api/users/" + user.id(), owner, "{\"active\":false}").statusCode()).isEqualTo(200);

        assertThat(call("GET", "/api/products", token, null).statusCode()).isEqualTo(401);
        assertThat(loginResponse(user.username(), PASSWORD).statusCode()).isEqualTo(401);

        assertThat(call("PATCH", "/api/users/" + user.id(), owner, "{\"active\":true}").statusCode()).isEqualTo(200);
        // Deactivation revoked the old token for good: reactivating does not revive it, a fresh login is needed.
        assertThat(call("GET", "/api/products", token, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/products", login(user.username(), PASSWORD), null).statusCode()).isEqualTo(200);
    }

    /** A valid token whose user row was deleted answers 401. */
    @Test
    void tokenOfAUserThatNoLongerExistsIsRejected() throws Exception {
        String owner = ownerToken();
        var user = createUser(owner, "ASSISTANT");
        String token = login(user.username(), PASSWORD);
        jdbc.update("delete from app_user where id = ?", user.id());
        assertThat(call("GET", "/api/products", token, null).statusCode()).isEqualTo(401);
    }

    /** Demoting an owner removes owner endpoints from their existing token at once, and promoting restores them. */
    @Test
    void roleChangeAppliesImmediatelyInBothDirections() throws Exception {
        String owner = ownerToken();
        var other = createUser(owner, "OWNER");
        String otherToken = login(other.username(), PASSWORD);
        assertThat(call("GET", "/api/users", otherToken, null).statusCode()).isEqualTo(200);

        assertThat(call("PATCH", "/api/users/" + other.id(), owner, "{\"role\":\"ASSISTANT\"}").statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/users", otherToken, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/products", otherToken, null).statusCode()).isEqualTo(200);

        assertThat(call("PATCH", "/api/users/" + other.id(), owner, "{\"role\":\"OWNER\"}").statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/users", otherToken, null).statusCode()).isEqualTo(200);
    }

    /**
     * Self-deactivation is refused with 422; the only active owner cannot be demoted, but can step down once another
     * owner exists.
     */
    @Test
    void ownerCannotDeactivateThemselvesAndLastActiveOwnerCannotBeDemoted() throws Exception {
        String owner = ownerToken();
        var me = createUser(owner, "OWNER");
        String myToken = login(me.username(), PASSWORD);

        assertThat(call("PATCH", "/api/users/" + me.id(), myToken, "{\"active\":false}").statusCode()).isEqualTo(422);

        // Make `me` the only active owner for the duration of this test.
        List<Long> others = jdbc.queryForList("select id from app_user where role = 'OWNER' and active and id <> ?", Long.class, me.id());
        others.forEach(id -> jdbc.update("update app_user set active = false where id = ?", id));
        try {
            var demote = call("PATCH", "/api/users/" + me.id(), myToken, "{\"role\":\"ASSISTANT\"}");
            assertThat(demote.statusCode()).isEqualTo(422);
            assertThat(demote.body()).contains("last active owner");
            assertThat(call("PATCH", "/api/users/" + me.id(), myToken, "{\"displayName\":\"Still owner\"}").statusCode()).isEqualTo(200);
        } finally {
            others.forEach(id -> jdbc.update("update app_user set active = true where id = ?", id));
        }

        // With another active owner around, stepping down is allowed.
        assertThat(call("PATCH", "/api/users/" + me.id(), myToken, "{\"role\":\"ASSISTANT\"}").statusCode()).isEqualTo(200);
    }

    @Autowired UserService userService;

    /**
     * Calls the service directly: the last active owner cannot be deactivated or demoted by any other actor either.
     */
    @Test
    void serviceRefusesToDeactivateTheLastActiveOwnerEvenForAnotherActor() throws Exception {
        var second = createUser(ownerToken(), "OWNER");
        List<Long> others = jdbc.queryForList("select id from app_user where role = 'OWNER' and active and id <> ?", Long.class, second.id());
        others.forEach(id -> jdbc.update("update app_user set active = false where id = ?", id));
        try {
            var actor = new Actor(-1, Role.OWNER);
            assertThatThrownBy(() -> userService.update(second.id(), null, null, false, actor))
                    .isInstanceOf(RuleViolation.class).hasMessageContaining("last active owner");
            assertThatThrownBy(() -> userService.update(second.id(), null, Role.ASSISTANT, null, actor))
                    .isInstanceOf(RuleViolation.class).hasMessageContaining("last active owner");
        } finally {
            others.forEach(id -> jdbc.update("update app_user set active = true where id = ?", id));
        }
    }

    /**
     * An owner resets a user's password (validated 10-72 characters, 404 for unknown users); the hash is stored and
     * assistants may not reset.
     */
    @Test
    void ownerResetsAnotherUsersPassword() throws Exception {
        String owner = ownerToken();
        var user = createUser(owner, "ASSISTANT");

        assertThat(call("POST", "/api/users/" + user.id() + "/password", owner, "{\"newPassword\":\"short\"}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/users/" + user.id() + "/password", owner, "{\"newPassword\":\"" + "x".repeat(73) + "\"}").statusCode())
                .isEqualTo(400);
        assertThat(call("POST", "/api/users/99999999/password", owner, "{\"newPassword\":\"brand-new-password\"}").statusCode()).isEqualTo(404);

        var r = call("POST", "/api/users/" + user.id() + "/password", owner, "{\"newPassword\":\"brand-new-password\"}");
        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
        assertThat(loginResponse(user.username(), PASSWORD).statusCode()).isEqualTo(401);
        login(user.username(), "brand-new-password");

        assertThat(jdbc.queryForObject("select password_hash from app_user where id = ?", String.class, user.id()))
                .startsWith("$2").isNotEqualTo("brand-new-password");
        assertThat(call("POST", "/api/users/" + user.id() + "/password", login(user.username(), "brand-new-password"),
                "{\"newPassword\":\"another-new-password\"}").statusCode()).isEqualTo(403);
    }

    /**
     * Changing one's own password needs the correct current password (422 otherwise), a valid new one (400) and a
     * token (401).
     */
    @Test
    void anyUserChangesTheirOwnPasswordButNeedsTheCurrentOne() throws Exception {
        String owner = ownerToken();
        var user = createUser(owner, "ASSISTANT");
        String token = login(user.username(), PASSWORD);

        // Wrong current password: 422 (the token is fine, so 401 would wrongly signal an expired session).
        var wrong = call("POST", "/api/auth/password", token, "{\"currentPassword\":\"not-the-password\",\"newPassword\":\"brand-new-password\"}");
        assertThat(wrong.statusCode()).isEqualTo(422);
        assertThat(wrong.body()).doesNotContain("brand-new-password");
        assertThat(call("POST", "/api/auth/password", token, "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"short\"}").statusCode())
                .isEqualTo(400);
        assertThat(call("POST", "/api/auth/password", null, "{\"currentPassword\":\"x\",\"newPassword\":\"brand-new-password\"}").statusCode())
                .isEqualTo(401);

        var ok = call("POST", "/api/auth/password", token, "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"brand-new-password\"}");
        assertThat(ok.statusCode()).isEqualTo(204);
        assertThat(loginResponse(user.username(), PASSWORD).statusCode()).isEqualTo(401);
        login(user.username(), "brand-new-password");
    }

    /**
     * Five failed logins give 429 with Retry-After, even for the right password, and unknown usernames behave the
     * same, ignoring case.
     */
    @Test
    void repeatedFailedLoginsAreThrottledWith429AndRetryAfter() throws Exception {
        String owner = ownerToken();
        var user = createUser(owner, "ASSISTANT");
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++)
            assertThat(loginResponse(user.username(), "wrong-password-" + i).statusCode()).isEqualTo(401);

        var blocked = loginResponse(user.username(), PASSWORD);   // even the right password is refused while blocked
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
        assertThat(Long.parseLong(blocked.headers().firstValue("Retry-After").get())).isBetween(1L, 900L);

        // Same behaviour for a username that does not exist, and the key is case-insensitive.
        String ghost = "ghost-" + UUID.randomUUID();
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) assertThat(loginResponse(ghost, "whatever-password").statusCode()).isEqualTo(401);
        assertThat(loginResponse(ghost.toUpperCase(), "whatever-password").statusCode()).isEqualTo(429);
    }
}
