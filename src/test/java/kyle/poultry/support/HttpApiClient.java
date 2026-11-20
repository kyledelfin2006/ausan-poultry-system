package kyle.poultry.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Pattern;
import kyle.poultry.TestcontainersConfiguration;

/**
 * Minimal real-HTTP client for end-to-end tests against the application started on a random port. It deliberately
 * goes through the full servlet and security stack (no MockMvc), so filters, CORS and headers are exercised exactly as
 * a browser or curl would see them.
 */
public final class HttpApiClient {
    /** The password given to every user these tests create through the API (passes the password policy). */
    public static final String PASSWORD = "a-long-enough-password";

    private final int port;
    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * Creates a client for the running application.
     *
     * @param port the random port injected with {@code @LocalServerPort}
     */
    public HttpApiClient(int port) { this.port = port; }

    /**
     * Sends one request and returns the raw response.
     *
     * @param method  HTTP verb
     * @param path    path (and query) starting with a slash
     * @param token   bearer token, or null for none
     * @param json    JSON body, or null for none
     * @param headers extra headers as alternating name, value pairs
     * @return the response, whatever its status
     * @throws Exception if the connection fails
     */
    public HttpResponse<String> call(String method, String path, String token, String json, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Logs in and returns the token; fails the test unless login answers 200.
     *
     * @param user login name
     * @param pass plain password
     * @return the JWT
     * @throws Exception if the connection fails
     */
    public String login(String user, String pass) throws Exception {
        var r = call("POST", "/api/auth/login", null, "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, pass));
        assertThat(r.statusCode()).as("login of %s: %s", user, r.body()).isEqualTo(200);
        return field(r.body(), "token");
    }

    /**
     * Logs in as the owner account that the test configuration creates at startup.
     *
     * @return the owner's token
     * @throws Exception if the connection fails
     */
    public String ownerToken() throws Exception {
        return login(TestcontainersConfiguration.OWNER_USERNAME, TestcontainersConfiguration.OWNER_PASSWORD);
    }

    /**
     * Creates a user with a random name through the API (as the given owner) and returns the new username.
     *
     * @param ownerToken an owner's token
     * @param role       {@code OWNER} or {@code ASSISTANT}
     * @return the new user's login name; the password is {@link #PASSWORD}
     * @throws Exception if the connection fails
     */
    public String createUser(String ownerToken, String role) throws Exception {
        return createUserWithId(ownerToken, role).username();
    }

    /**
     * A user created by {@link #createUserWithId}.
     *
     * @param id       database id
     * @param username login name
     */
    public record NewUser(long id, String username) {}

    /**
     * Creates a user with a random name through the API and returns both id and name.
     *
     * @param ownerToken an owner's token
     * @param role       {@code OWNER} or {@code ASSISTANT}
     * @return the new user
     * @throws Exception if the connection fails
     */
    public NewUser createUserWithId(String ownerToken, String role) throws Exception {
        String username = role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        var r = call("POST", "/api/users", ownerToken,
                "{\"username\":\"%s\",\"password\":\"%s\",\"displayName\":\"Test\",\"role\":\"%s\"}".formatted(username, PASSWORD, role));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return new NewUser(Long.parseLong(field(r.body(), "id")), username);
    }

    /**
     * Pulls one top-level scalar out of a JSON body without a JSON library (enough for these flat responses).
     *
     * @param json the response body
     * @param name the property name
     * @return the value, without quotes
     */
    public static String field(String json, String name) {
        var m = Pattern.compile("\"" + Pattern.quote(name) + "\":(\"([^\"]*)\"|[^,}\\]]+)").matcher(json);
        assertThat(m.find()).as("field %s in %s", name, json).isTrue();
        return m.group(2) != null ? m.group(2) : m.group(1);
    }
}
