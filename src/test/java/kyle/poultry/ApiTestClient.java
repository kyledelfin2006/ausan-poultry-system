package kyle.poultry;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

/**
 * A tiny real-HTTP client for the integration tests that start the whole application on a random port: it sends JSON
 * requests, logs in as the bootstrap owner or as a shared assistant account, and returns raw responses so tests can
 * assert on status codes, headers and bodies exactly as a browser client would see them.
 */
public final class ApiTestClient {
    /** Username of the assistant account these tests share; created on first use. */
    public static final String ASSISTANT_USERNAME = "assistant-readapi";
    /** Password of that assistant (a test value). */
    public static final String ASSISTANT_PASSWORD = "assistant-readapi-password";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

    private final int port;

    /**
     * Creates a client for the server on the given port.
     *
     * @param port the random port of the running test application
     */
    public ApiTestClient(int port) { this.port = port; }

    /**
     * Sends one request.
     *
     * @param method HTTP verb
     * @param path   path with query string, starting with {@code /api}
     * @param token  bearer token, or null for an unauthenticated call
     * @param json   request body, or null for none
     * @return the response with a text body
     */
    public HttpResponse<String> call(String method, String path, String token, String json) {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (token != null) b.header("Authorization", "Bearer " + token);
        try {
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("HTTP call failed: " + method + " " + path, e);
        }
    }

    /**
     * Logs in and returns the token; fails the test unless login answers 200.
     *
     * @param user login name
     * @param pass plain password
     * @return the JWT
     */
    public String login(String user, String pass) {
        var r = call("POST", "/api/auth/login", null, "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, pass));
        assertThat(r.statusCode()).isEqualTo(200);
        var m = TOKEN.matcher(r.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    /** {@return a token of the bootstrap owner} */
    public String owner() {
        return login(TestcontainersConfiguration.OWNER_USERNAME, TestcontainersConfiguration.OWNER_PASSWORD);
    }

    /**
     * Returns a token of the shared assistant, creating the account the first time (a repeat create answers 409,
     * which is fine because the account already exists).
     *
     * @return the assistant's JWT
     */
    public String assistant() {
        call("POST", "/api/users", owner(), "{\"username\":\"%s\",\"password\":\"%s\",\"displayName\":\"Assistant\",\"role\":\"ASSISTANT\"}"
                .formatted(ASSISTANT_USERNAME, ASSISTANT_PASSWORD));
        return login(ASSISTANT_USERNAME, ASSISTANT_PASSWORD);
    }
}
