package kyle.poultry.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import kyle.poultry.ApiTestClient;
import kyle.poultry.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * End-to-end checks of the pre-UI audit hardening against a real server and Postgres: token revocation on
 * deactivation, username normalisation, owner-only areas answering 403 before the body is read, login input limits and
 * the password-change throttle. Users get random names so tests never clash on the shared database.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuditHardeningTests {
    static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort int port;
    ApiTestClient api;
    String owner;

    /** A client for the running server and an owner token. */
    @BeforeEach
    void setUp() {
        api = new ApiTestClient(port);
        owner = api.owner();
    }

    /**
     * Creates an assistant with a random name through the API.
     *
     * @param username the login name to submit
     * @return the response of the create call
     */
    private java.net.http.HttpResponse<String> createAssistant(String username) {
        return api.call("POST", "/api/users", owner,
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"T\",\"role\":\"ASSISTANT\"}");
    }

    /** SEC-4: deactivating ends the user's tokens for good, so reactivating inside the token lifetime does not revive them. */
    @Test
    void deactivationRevokesTokensEvenIfTheUserIsReactivated() {
        String name = "hard-" + UUID.randomUUID().toString().substring(0, 8);
        int id = JsonPath.read(createAssistant(name).body(), "$.id");
        String token = api.login(name, PASSWORD);
        assertThat(api.call("GET", "/api/auth/me", token, null).statusCode()).isEqualTo(200);

        assertThat(api.call("PATCH", "/api/users/" + id, owner, "{\"active\":false}").statusCode()).isEqualTo(200);
        assertThat(api.call("PATCH", "/api/users/" + id, owner, "{\"active\":true}").statusCode()).isEqualTo(200);

        assertThat(api.call("GET", "/api/auth/me", token, null).statusCode()).isEqualTo(401);
        assertThat(api.call("GET", "/api/auth/me", api.login(name, PASSWORD), null).statusCode()).isEqualTo(200);
    }

    /** SEC-6: usernames are stored trimmed and lower-case, log in ignoring case, and cannot be duplicated by case. */
    @Test
    void usernamesAreNormalised() {
        String name = "mixed-" + UUID.randomUUID().toString().substring(0, 8);
        var created = createAssistant("  " + name.toUpperCase() + " ");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(created.body(), "$.username")).isEqualTo(name);

        assertThat(api.call("GET", "/api/auth/me", api.login(name.toUpperCase(), PASSWORD), null).statusCode()).isEqualTo(200);
        var duplicate = createAssistant(name);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat((String) JsonPath.read(duplicate.body(), "$.code")).isEqualTo("CONFLICT");
    }

    /** SEC-5: an assistant gets 403, not 400, when sending an invalid body to a wholly owner-only area. */
    @Test
    void assistantIsRefusedBeforeTheBodyIsValidated() {
        String assistant = api.assistant();

        assertThat(api.call("POST", "/api/users", assistant, "{}").statusCode()).isEqualTo(403);
        assertThat(api.call("PATCH", "/api/users/1", assistant, "{\"displayName\":\"" + "x".repeat(500) + "\"}").statusCode()).isEqualTo(403);
        assertThat(api.call("GET", "/api/audit", assistant, null).statusCode()).isEqualTo(403);
        assertThat(api.call("POST", "/api/users", null, "{}").statusCode()).isEqualTo(401);
    }

    /** SEC-2: oversized login fields are a plain 400, never reaching the throttle or the password check. */
    @Test
    void oversizedLoginFieldsAreRejected() {
        var longUser = api.call("POST", "/api/auth/login", null,
                "{\"username\":\"" + "u".repeat(51) + "\",\"password\":\"x\"}");
        var longPassword = api.call("POST", "/api/auth/login", null,
                "{\"username\":\"someone\",\"password\":\"" + "p".repeat(129) + "\"}");

        assertThat(longUser.statusCode()).isEqualTo(400);
        assertThat(longPassword.statusCode()).isEqualTo(400);
    }

    /** SEC-7: repeated wrong current passwords are answered 429 with Retry-After, like login. */
    @Test
    void passwordChangeIsThrottledAfterRepeatedWrongCurrentPasswords() {
        String name = "pw-" + UUID.randomUUID().toString().substring(0, 8);
        createAssistant(name);
        String token = api.login(name, PASSWORD);
        String wrong = "{\"currentPassword\":\"not-the-password\",\"newPassword\":\"another-long-password\"}";

        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++)
            assertThat(api.call("POST", "/api/auth/password", token, wrong).statusCode()).isEqualTo(422);
        var blocked = api.call("POST", "/api/auth/password", token, wrong);

        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
        // Even the right password is refused while blocked, so the oracle is closed.
        var right = "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"another-long-password\"}";
        assertThat(api.call("POST", "/api/auth/password", token, right).statusCode()).isEqualTo(429);
    }
}
