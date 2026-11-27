package kyle.poultry.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.regex.Pattern;
import kyle.poultry.TestcontainersConfiguration;

/**
 * Minimal HTTP client for end-to-end contract tests against the application started on a random port.
 * <p>
 * It exists so contract tests read as "call this, expect that" without repeating request plumbing; it deliberately
 * uses the JDK client (no mocks) so the real security chain and exception handling are exercised.
 */
public final class ApiTestClient {

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

    private final int port;
    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * Creates a client for a running server.
     *
     * @param port the port the application listens on
     */
    public ApiTestClient(int port) { this.port = port; }

    /**
     * Sends one request and returns the raw response.
     *
     * @param method      HTTP verb
     * @param path        path (and query) starting with {@code /}
     * @param token       bearer token, or null for none
     * @param contentType value of the Content-Type header, or null for none
     * @param body        request body, or null for none
     * @param headers     extra request headers (may be empty)
     * @return the response with its body as text
     * @throws Exception when the request cannot be sent
     */
    public HttpResponse<String> send(String method, String path, String token, String contentType, String body,
                                     Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) request.header("Content-Type", contentType);
        if (token != null) request.header("Authorization", "Bearer " + token);
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends a JSON request (Content-Type {@code application/json} when there is a body).
     *
     * @param method HTTP verb
     * @param path   path (and query) starting with {@code /}
     * @param token  bearer token, or null for none
     * @param body   JSON text, or null for none
     * @return the response
     * @throws Exception when the request cannot be sent
     */
    public HttpResponse<String> json(String method, String path, String token, String body) throws Exception {
        return send(method, path, token, body == null ? null : "application/json", body, Map.of());
    }

    /**
     * Logs in and returns the bearer token.
     *
     * @param username login name
     * @param password plain password
     * @return the JWT
     * @throws Exception when login does not answer 200 with a token
     */
    public String login(String username, String password) throws Exception {
        var response = json("POST", "/api/auth/login", null,
                "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password));
        var matcher = TOKEN.matcher(response.body());
        if (response.statusCode() != 200 || !matcher.find())
            throw new IllegalStateException("Login failed: " + response.statusCode() + " " + response.body());
        return matcher.group(1);
    }

    /**
     * Logs in as the bootstrap owner that {@link TestcontainersConfiguration} creates.
     *
     * @return the owner's bearer token
     * @throws Exception when login fails
     */
    public String loginAsOwner() throws Exception {
        return login(TestcontainersConfiguration.OWNER_USERNAME, TestcontainersConfiguration.OWNER_PASSWORD);
    }

    /**
     * Creates an ASSISTANT account as the owner and logs it in.
     *
     * @param ownerToken the owner's token
     * @param username   the new assistant's login name
     * @return the assistant's bearer token
     * @throws Exception when creating or logging in fails
     */
    public String loginAsNewAssistant(String ownerToken, String username) throws Exception {
        var created = json("POST", "/api/users", ownerToken,
                "{\"username\":\"%s\",\"password\":\"assistant-password\",\"displayName\":\"Assistant\",\"role\":\"ASSISTANT\"}"
                        .formatted(username));
        if (created.statusCode() != 201)
            throw new IllegalStateException("Could not create assistant: " + created.statusCode() + " " + created.body());
        return login(username, "assistant-password");
    }
}
