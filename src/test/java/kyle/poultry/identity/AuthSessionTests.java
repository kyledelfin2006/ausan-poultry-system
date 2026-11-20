package kyle.poultry.identity;

import static kyle.poultry.support.HttpApiClient.PASSWORD;
import static kyle.poultry.support.HttpApiClient.field;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.support.HttpApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * End-to-end tests of who-am-I, logout, session revocation on password changes, the password policy over HTTP, and the
 * authentication metrics. Each test creates its own random users so the shared database never causes clashes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuthSessionTests {

    @LocalServerPort int port;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired Clock clock;
    @Autowired MeterRegistry metrics;

    /** {@return a client for the running application} */
    HttpApiClient api() { return new HttpApiClient(port); }

    /** {@code /api/auth/me} reports the caller from the database, and needs a token. */
    @Test
    void meReturnsTheCallersCurrentProfile() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        var user = api.createUserWithId(owner, "ASSISTANT");
        String token = api.login(user.username(), PASSWORD);

        var me = api.call("GET", "/api/auth/me", token, null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(field(me.body(), "username")).isEqualTo(user.username());
        assertThat(field(me.body(), "role")).isEqualTo("ASSISTANT");
        assertThat(field(me.body(), "id")).isEqualTo(String.valueOf(user.id()));
        assertThat(me.body()).contains("tokenExpiresAt").doesNotContain("password").doesNotContain("$2");

        // A role change applies to the same token at once (the database wins over the token's own claim).
        assertThat(api.call("PATCH", "/api/users/" + user.id(), owner, "{\"role\":\"OWNER\"}").statusCode()).isEqualTo(200);
        assertThat(field(api.call("GET", "/api/auth/me", token, null).body(), "role")).isEqualTo("OWNER");

        assertThat(api.call("GET", "/api/auth/me", null, null).statusCode()).isEqualTo(401);
    }

    /** Logout kills every token of that user (all devices), keeps other users signed in, and a fresh login works. */
    @Test
    void logoutEndsAllSessionsOfThatUserOnly() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        String name = api.createUser(owner, "ASSISTANT");
        String otherName = api.createUser(owner, "ASSISTANT");
        String phone = api.login(name, PASSWORD);
        String laptop = api.login(name, PASSWORD);
        String someoneElse = api.login(otherName, PASSWORD);

        assertThat(api.call("POST", "/api/auth/logout", phone, null).statusCode()).isEqualTo(204);

        // Both of this user's tokens are dead, and the protected effect did not survive: a data call is refused too.
        assertThat(api.call("GET", "/api/auth/me", phone, null).statusCode()).isEqualTo(401);
        assertThat(api.call("GET", "/api/auth/me", laptop, null).statusCode()).isEqualTo(401);
        assertThat(api.call("GET", "/api/products", laptop, null).statusCode()).isEqualTo(401);
        // Logging out twice with the dead token is a plain 401, not a server error.
        assertThat(api.call("POST", "/api/auth/logout", phone, null).statusCode()).isEqualTo(401);

        // Another user's session and the owner's session are untouched.
        assertThat(api.call("GET", "/api/auth/me", someoneElse, null).statusCode()).isEqualTo(200);
        assertThat(api.call("GET", "/api/auth/me", owner, null).statusCode()).isEqualTo(200);

        // The same user can log in again and the new token works.
        assertThat(api.call("GET", "/api/auth/me", api.login(name, PASSWORD), null).statusCode()).isEqualTo(200);
        assertThat(api.call("POST", "/api/auth/logout", null, null).statusCode()).isEqualTo(401);
    }

    /** Changing one's own password signs the user out everywhere, and only the new password logs in. */
    @Test
    void changingOwnPasswordRevokesExistingTokens() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        String name = api.createUser(owner, "ASSISTANT");
        String before = api.login(name, PASSWORD);

        var changed = api.call("POST", "/api/auth/password", before,
                "{\"currentPassword\":\"%s\",\"newPassword\":\"brand-new-password-1\"}".formatted(PASSWORD));
        assertThat(changed.statusCode()).isEqualTo(204);

        assertThat(api.call("GET", "/api/products", before, null).statusCode()).isEqualTo(401);
        assertThat(api.call("POST", "/api/auth/login", null,
                "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(name, PASSWORD)).statusCode()).isEqualTo(401);
        assertThat(api.call("GET", "/api/products", api.login(name, "brand-new-password-1"), null).statusCode()).isEqualTo(200);
    }

    /** An owner's password reset signs the target out, not the owner. */
    @Test
    void ownerResetRevokesTheTargetsTokensButNotTheOwners() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        var target = api.createUserWithId(owner, "ASSISTANT");
        String targetToken = api.login(target.username(), PASSWORD);

        assertThat(api.call("POST", "/api/users/" + target.id() + "/password", owner,
                "{\"newPassword\":\"reset-by-the-owner-1\"}").statusCode()).isEqualTo(204);

        assertThat(api.call("GET", "/api/products", targetToken, null).statusCode()).isEqualTo(401);
        assertThat(api.call("GET", "/api/products", owner, null).statusCode()).isEqualTo(200);
        api.login(target.username(), "reset-by-the-owner-1");
    }

    /** The password policy answers 422 on every path that sets a password, and nothing changes when it refuses. */
    @Test
    void passwordPolicyIsEnforcedEverywhereAPasswordIsChosen() throws Exception {
        var api = api();
        String owner = api.ownerToken();

        // Creating a user with a common password, or the username as password, is refused and no account appears.
        String commonName = "common-" + Long.toHexString(System.nanoTime());
        var common = api.call("POST", "/api/users", owner,
                "{\"username\":\"%s\",\"password\":\"password123\",\"displayName\":\"X\",\"role\":\"ASSISTANT\"}".formatted(commonName));
        assertThat(common.statusCode()).isEqualTo(422);
        assertThat(common.body()).doesNotContain("password123");
        assertThat(api.call("POST", "/api/auth/login", null,
                "{\"username\":\"%s\",\"password\":\"password123\"}".formatted(commonName)).statusCode()).isEqualTo(401);
        String selfName = "same-name-" + Long.toHexString(System.nanoTime());
        assertThat(api.call("POST", "/api/users", owner,
                "{\"username\":\"%s\",\"password\":\"%s\",\"displayName\":\"X\",\"role\":\"ASSISTANT\"}".formatted(selfName, selfName)).statusCode())
                .isEqualTo(422);
        // 19 emoji pass the 72-CHARACTER request check but are 76 bytes, over BCrypt's real limit.
        assertThat(api.call("POST", "/api/users", owner,
                "{\"username\":\"emoji-%s\",\"password\":\"%s\",\"displayName\":\"X\",\"role\":\"ASSISTANT\"}"
                        .formatted(Long.toHexString(System.nanoTime()), "😀".repeat(19))).statusCode()).isEqualTo(422);

        // Owner reset with a weak password: refused, and the old password still works.
        var user = api.createUserWithId(owner, "ASSISTANT");
        assertThat(api.call("POST", "/api/users/" + user.id() + "/password", owner, "{\"newPassword\":\"qwertyuiop\"}").statusCode())
                .isEqualTo(422);
        String token = api.login(user.username(), PASSWORD);

        // Own change: same password is refused; weak password is refused; the session survives a refused change.
        assertThat(api.call("POST", "/api/auth/password", token,
                "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, PASSWORD)).statusCode()).isEqualTo(422);
        assertThat(api.call("POST", "/api/auth/password", token,
                "{\"currentPassword\":\"%s\",\"newPassword\":\"1234567890\"}".formatted(PASSWORD)).statusCode()).isEqualTo(422);
        assertThat(api.call("GET", "/api/auth/me", token, null).statusCode()).isEqualTo(200);
    }

    /** Tokens issued before the version claim existed still work; a token with a stale version is refused. */
    @Test
    void tokenVersionClaimIsOptionalForLegacyTokensButMustMatchWhenPresent() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        var user = api.createUserWithId(owner, "ASSISTANT");

        // A legacy token: signed correctly, no "ver" claim (what every token looked like before this change).
        assertThat(api.call("GET", "/api/auth/me", mint(user.id(), user.username(), null), null).statusCode()).isEqualTo(200);
        // Same user, version 0 explicitly: the current version, accepted.
        assertThat(api.call("GET", "/api/auth/me", mint(user.id(), user.username(), 0), null).statusCode()).isEqualTo(200);
        // A version the user is not on (older or newer): refused, and no data is returned.
        var stale = api.call("GET", "/api/products", mint(user.id(), user.username(), 7), null);
        assertThat(stale.statusCode()).isEqualTo(401);
        assertThat(stale.body()).isEmpty();
    }

    /** Login outcomes and logouts are counted, so an operator can alert on a burst of failures. */
    @Test
    void authenticationMetricsCountLoginOutcomesAndLogouts() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        String name = api.createUser(owner, "ASSISTANT");
        double success = count("ausan.auth.logins", "outcome", "success");
        double failure = count("ausan.auth.logins", "outcome", "failure");
        double logouts = count("ausan.auth.logouts");

        String token = api.login(name, PASSWORD);
        api.call("POST", "/api/auth/login", null, "{\"username\":\"%s\",\"password\":\"wrong-password-here\"}".formatted(name));
        api.call("POST", "/api/auth/logout", token, null);

        assertThat(count("ausan.auth.logins", "outcome", "success")).isEqualTo(success + 1);
        assertThat(count("ausan.auth.logins", "outcome", "failure")).isEqualTo(failure + 1);
        assertThat(count("ausan.auth.logouts")).isEqualTo(logouts + 1);
    }

    /**
     * Reads a counter's current value (0 if it has not been created yet).
     *
     * @param name the meter name
     * @param tags alternating tag key, value pairs
     * @return the count
     */
    private double count(String name, String... tags) {
        var c = metrics.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    /**
     * Signs a token the way login does, but with a chosen version claim, to simulate legacy and revoked tokens.
     *
     * @param id       the user id (token subject)
     * @param username the username claim
     * @param version  the {@code ver} claim, or null to leave it out
     * @return the signed token
     */
    private String mint(long id, String username, Integer version) {
        var now = clock.instant();
        var claims = JwtClaimsSet.builder().subject(String.valueOf(id)).claim("role", "ASSISTANT").claim("username", username)
                .issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(5)));
        if (version != null) claims.claim("ver", version);
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }
}
