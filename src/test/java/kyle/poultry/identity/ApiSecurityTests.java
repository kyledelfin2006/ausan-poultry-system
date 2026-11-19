package kyle.poultry.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import kyle.poultry.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * End-to-end HTTP tests of authentication and role rules, plus stock and finance endpoints seen through the security
 * layer. Runs the whole application on a random port against a real Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ApiSecurityTests {

    @LocalServerPort int port;
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
     * Logs in over HTTP and returns the token.
     *
     * @param user login name
     * @param pass plain password
     * @return the JWT; fails the test unless login answers 200
     */
    String login(String user, String pass) throws Exception {
        var r = call("POST", "/api/auth/login", null, "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, pass));
        assertThat(r.statusCode()).isEqualTo(200);
        var m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(r.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    /** Requests with no token or a garbage token answer 401. */
    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        assertThat(call("GET", "/api/products", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/stock/balances", "garbage", null).statusCode()).isEqualTo(401);
    }

    /** Logging in as an unknown user answers 401. */
    @Test
    void badCredentialsGive401() throws Exception {
        var r = call("POST", "/api/auth/login", null, "{\"username\":\"nobody\",\"password\":\"whatever-long\"}");
        assertThat(r.statusCode()).isEqualTo(401);
    }

    /**
     * The owner reads the catalog and creates an assistant; the assistant can read but not change the catalog or
     * create users (403).
     */
    @Test
    void ownerSeesSeededCatalogAndAssistantCannotChangeIt() throws Exception {
        String owner = login(TestcontainersConfiguration.OWNER_USERNAME, TestcontainersConfiguration.OWNER_PASSWORD);

        var products = call("GET", "/api/products", owner, null);
        assertThat(products.statusCode()).isEqualTo(200);
        assertThat(products.body()).contains("Chicken", "Nipple", "Cage", "Water jug");

        var created = call("POST", "/api/users", owner,
                "{\"username\":\"assistant-it\",\"password\":\"assistant-password\",\"displayName\":\"Assistant\",\"role\":\"ASSISTANT\"}");
        assertThat(created.statusCode()).isEqualTo(201);

        String assistant = login("assistant-it", "assistant-password");
        assertThat(call("GET", "/api/products", assistant, null).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/products", assistant, "{\"name\":\"Feed\",\"unit\":\"kg\"}").statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/users", assistant,
                "{\"username\":\"x\",\"password\":\"long-enough-pass\",\"displayName\":\"X\",\"role\":\"OWNER\"}").statusCode()).isEqualTo(403);
    }

    /**
     * Stock movement endpoints answer 201, 422 or 409 as the business rules require, and purchases show up in the
     * ledger.
     */
    @Test
    void stockEndpointEnforcesRulesOverHttp() throws Exception {
        String owner = login(TestcontainersConfiguration.OWNER_USERNAME, TestcontainersConfiguration.OWNER_PASSWORD);
        String opening = """
                {"productId":2,"branchId":1,"type":"OPENING","quantityDelta":500,"occurredOn":"2026-01-01","reason":"6 weeks old"}""";
        assertThat(call("POST", "/api/stock/movements", owner, opening).statusCode()).isEqualTo(201);

        String badSign = """
                {"productId":2,"branchId":1,"type":"PURCHASE","quantityDelta":-5,"occurredOn":"2026-01-01"}""";
        assertThat(call("POST", "/api/stock/movements", owner, badSign).statusCode()).isEqualTo(422);

        String tooMuch = """
                {"productId":2,"branchId":1,"type":"USAGE","quantityDelta":-100000,"occurredOn":"2026-01-01","reason":"x"}""";
        assertThat(call("POST", "/api/stock/movements", owner, tooMuch).statusCode()).isEqualTo(409);

        String purchase = """
                {"productId":3,"branchId":1,"type":"PURCHASE","quantityDelta":100,"unitCost":20.00,"paymentMethod":"CASH","occurredOn":"2026-01-02"}""";
        assertThat(call("POST", "/api/stock/movements", owner, purchase).statusCode()).isEqualTo(201);
        String noMethod = """
                {"productId":3,"branchId":1,"type":"PURCHASE","quantityDelta":100,"unitCost":20.00,"occurredOn":"2026-01-02"}""";
        assertThat(call("POST", "/api/stock/movements", owner, noMethod).statusCode()).isEqualTo(422);

        var ledger = call("GET", "/api/finance/entries?from=2026-01-02&to=2026-01-02&type=STOCK_PURCHASE", owner, null);
        assertThat(ledger.statusCode()).isEqualTo(200);
        assertThat(ledger.body()).contains("STOCK_PURCHASE", "2000.00");

        var balances = call("GET", "/api/stock/balances?branchId=1", owner, null);
        assertThat(balances.statusCode()).isEqualTo(200);
        assertThat(balances.body()).contains("Chicken");
    }

    /** An assistant can record an expense (and gets 400 for a zero amount) and read the ledger. */
    @Test
    void assistantCanRecordExpensesAndReadTheLedger() throws Exception {
        String owner = login(TestcontainersConfiguration.OWNER_USERNAME, TestcontainersConfiguration.OWNER_PASSWORD);
        call("POST", "/api/users", owner,
                "{\"username\":\"assistant-fin\",\"password\":\"assistant-password\",\"displayName\":\"A\",\"role\":\"ASSISTANT\"}");
        String assistant = login("assistant-fin", "assistant-password");

        var created = call("POST", "/api/finance/expenses", assistant,
                "{\"amount\":150.50,\"method\":\"CASH\",\"occurredOn\":\"2026-02-01\",\"description\":\"Fuel for the van\"}");
        assertThat(created.statusCode()).isEqualTo(201);

        var bad = call("POST", "/api/finance/expenses", assistant,
                "{\"amount\":0,\"method\":\"CASH\",\"occurredOn\":\"2026-02-01\",\"description\":\"x\"}");
        assertThat(bad.statusCode()).isEqualTo(400);

        var list = call("GET", "/api/finance/entries?from=2026-02-01&to=2026-02-01", assistant, null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("Fuel for the van", "150.50");
    }
}
