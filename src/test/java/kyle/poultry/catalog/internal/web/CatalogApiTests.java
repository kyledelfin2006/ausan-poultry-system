package kyle.poultry.catalog.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Pattern;
import kyle.poultry.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end HTTP tests of the catalog contract: products, branches and sales packages, who may change them, which
 * inputs are refused and with which status, and that a package's contents are fixed once it exists.
 * <p>
 * The application runs on a random port against a real PostgreSQL. Every test uses names made unique with a random tag, so
 * the tests are independent of each other and of the seed data. Two tests are {@link Disabled}: each reproduces a defect
 * listed in {@code docs/quality/findings.md} and will start passing when the defect is fixed.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CatalogApiTests {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final HttpClient http = HttpClient.newHttpClient();

    private String owner;
    private String assistant;

    /** Logs in as the owner and as a freshly created assistant. */
    @BeforeEach
    void logIn() throws Exception {
        owner = login(TestcontainersConfiguration.OWNER_USERNAME, TestcontainersConfiguration.OWNER_PASSWORD);
        String username = "cat-" + UUID.randomUUID().toString().substring(0, 8);
        var created = call("POST", "/api/users", owner,
                "{\"username\":\"%s\",\"password\":\"assistant-password\",\"displayName\":\"A\",\"role\":\"ASSISTANT\"}".formatted(username));
        assertThat(created.statusCode()).isEqualTo(201);
        assistant = login(username, "assistant-password");
    }

    /**
     * Sends one JSON request to the running server.
     *
     * @param method HTTP verb
     * @param path   path starting with /api
     * @param token  bearer token, or null
     * @param json   request body, or null
     * @return the raw response
     */
    private HttpResponse<String> call(String method, String path, String token, String json) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Logs in and returns the token.
     *
     * @param user the login name
     * @param pass the password
     * @return the JWT
     */
    private String login(String user, String pass) throws Exception {
        var r = call("POST", "/api/auth/login", null, "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, pass));
        assertThat(r.statusCode()).isEqualTo(200);
        var m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(r.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    /**
     * Reads the first {@code "id"} number of a JSON body.
     *
     * @param json the response body
     * @return the id
     */
    private static long idOf(String json) {
        var m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        assertThat(m.find()).as("an id in " + json).isTrue();
        return Long.parseLong(m.group(1));
    }

    /**
     * Creates a product as the owner and returns its id.
     *
     * @param name the unique name
     * @return the new product id
     */
    private long newProduct(String name) throws Exception {
        var r = call("POST", "/api/products", owner, "{\"name\":\"%s\",\"unit\":\"pc\"}".formatted(name));
        assertThat(r.statusCode()).isEqualTo(201);
        return idOf(r.body());
    }

    /** @return a name that no other test uses */
    private static String unique() { return "t-" + UUID.randomUUID(); }

    /** Everyone signed in may read the catalog, and nobody else may. */
    @Test
    void readingNeedsASignInButNotTheOwnerRole() throws Exception {
        assertThat(call("GET", "/api/products", null, null).statusCode()).isEqualTo(401);
        for (String path : new String[] {"/api/products", "/api/branches", "/api/packages"}) {
            assertThat(call("GET", path, assistant, null).statusCode()).as(path).isEqualTo(200);
        }
    }

    /** An assistant may not create or change anything in the catalog; the refusal is a 403 with no change made. */
    @Test
    void anAssistantCannotChangeTheCatalog() throws Exception {
        long product = newProduct(unique());

        assertThat(call("POST", "/api/products", assistant, "{\"name\":\"" + unique() + "\",\"unit\":\"pc\"}").statusCode()).isEqualTo(403);
        assertThat(call("PATCH", "/api/products/" + product, assistant, "{\"active\":false}").statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/branches", assistant, "{\"name\":\"" + unique() + "\"}").statusCode()).isEqualTo(403);
        assertThat(call("PATCH", "/api/branches/1", assistant, "{\"active\":false}").statusCode()).isEqualTo(403);
        assertThat(call("PATCH", "/api/packages/1", assistant, "{\"price\":1}").statusCode()).isEqualTo(403);

        assertThat(jdbc.queryForObject("select active from product where id = ?", Boolean.class, product)).isTrue();
    }

    /** A product is created with its price stored at two decimals, listed, partially updated and deactivated. */
    @Test
    void anOwnerCreatesUpdatesAndDeactivatesAProduct() throws Exception {
        String name = unique();
        var created = call("POST", "/api/products", owner, "{\"name\":\"%s\",\"unit\":\"kg\",\"defaultPrice\":12.5}".formatted(name));
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"unit\":\"kg\"", "\"defaultPrice\":12.50", "\"active\":true");
        long id = idOf(created.body());

        var renamed = call("PATCH", "/api/products/" + id, owner, "{\"unit\":\"bag\"}");

        // A partial update changes only what was sent: the name and the price are as before.
        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat(renamed.body()).contains("\"name\":\"" + name + "\"", "\"unit\":\"bag\"", "\"defaultPrice\":12.50");
        var off = call("PATCH", "/api/products/" + id, owner, "{\"active\":false}");
        assertThat(off.body()).contains("\"active\":false");
        assertThat(call("GET", "/api/products", owner, null).body()).contains(name);
    }

    /**
     * Product input rules: a blank name or unit, a negative price and a price with a third decimal are 400; a duplicate
     * name is 409; an unknown product is 404.
     */
    @Test
    void productInputIsValidatedAndDuplicatesAndUnknownIdsAreRefused() throws Exception {
        String name = unique();
        newProduct(name);

        assertThat(call("POST", "/api/products", owner, "{\"name\":\"\",\"unit\":\"pc\"}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/products", owner, "{\"name\":\"" + unique() + "\",\"unit\":\"\"}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/products", owner, "{\"name\":\"" + unique() + "\",\"unit\":\"pc\",\"defaultPrice\":-1}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/products", owner, "{\"name\":\"" + unique() + "\",\"unit\":\"pc\",\"defaultPrice\":1.005}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/products", owner, "{\"name\":\"" + name + "\",\"unit\":\"pc\"}").statusCode()).isEqualTo(409);
        assertThat(call("PATCH", "/api/products/999999999", owner, "{\"active\":false}").statusCode()).isEqualTo(404);
    }

    /** Branches follow the same pattern: create, rename, deactivate, and refuse a duplicate or a blank name. */
    @Test
    void anOwnerManagesBranches() throws Exception {
        String name = unique();
        var created = call("POST", "/api/branches", owner, "{\"name\":\"" + name + "\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        long id = idOf(created.body());

        assertThat(call("PATCH", "/api/branches/" + id, owner, "{\"active\":false}").body()).contains("\"active\":false");
        String renamed = unique();
        assertThat(call("PATCH", "/api/branches/" + id, owner, "{\"name\":\"" + renamed + "\"}").body())
                .contains(renamed, "\"active\":false");
        assertThat(call("POST", "/api/branches", owner, "{\"name\":\"" + renamed + "\"}").statusCode()).isEqualTo(409);
        assertThat(call("POST", "/api/branches", owner, "{\"name\":\"\"}").statusCode()).isEqualTo(400);
        assertThat(call("PATCH", "/api/branches/999999999", owner, "{\"active\":true}").statusCode()).isEqualTo(404);
    }

    /** A package is created with its contents, priced at two decimals, and listed with the product names. */
    @Test
    void anOwnerCreatesAPackageWithItsContents() throws Exception {
        String chicken = unique();
        String cage = unique();
        long chickenId = newProduct(chicken);
        long cageId = newProduct(cage);
        String sku = "SKU-" + UUID.randomUUID();

        var created = call("POST", "/api/packages", owner, """
                {"sku":"%s","name":"%s","price":1234.5,"items":[{"productId":%d,"quantity":24},{"productId":%d,"quantity":2}]}"""
                .formatted(sku, unique(), chickenId, cageId));

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"price\":1234.50", "\"sku\":\"" + sku + "\"", chicken, cage, "\"quantity\":24", "\"quantity\":2");
    }

    /**
     * Package input rules: no items and a zero quantity or price are 400; an unknown product is 404; a deactivated product
     * and a product listed twice are 422; a repeated SKU or name is 409. None of them leaves a package behind.
     */
    @Test
    void packageInputIsValidated() throws Exception {
        long product = newProduct(unique());
        long retired = newProduct(unique());
        call("PATCH", "/api/products/" + retired, owner, "{\"active\":false}");
        long before = jdbc.queryForObject("select count(*) from sales_package", Long.class);
        String item = "{\"productId\":%d,\"quantity\":1}".formatted(product);

        assertThat(post("S1", unique(), "100", "[]").statusCode()).isEqualTo(400);
        assertThat(post("S2", unique(), "100", "[{\"productId\":" + product + ",\"quantity\":0}]").statusCode()).isEqualTo(400);
        assertThat(post("S3", unique(), "0", "[" + item + "]").statusCode()).isEqualTo(400);
        assertThat(post("S4", unique(), "100", "[{\"productId\":999999999,\"quantity\":1}]").statusCode()).isEqualTo(404);
        assertThat(post("S5", unique(), "100", "[{\"productId\":" + retired + ",\"quantity\":1}]").statusCode()).isEqualTo(422);
        assertThat(post("S6", unique(), "100", "[" + item + "," + item + "]").statusCode()).isEqualTo(422);

        assertThat(jdbc.queryForObject("select count(*) from sales_package", Long.class)).isEqualTo(before);
        String sku = "SKU-" + UUID.randomUUID();
        String name = unique();
        assertThat(call("POST", "/api/packages", owner, packageJson(sku, name, "100", "[" + item + "]")).statusCode()).isEqualTo(201);
        assertThat(call("POST", "/api/packages", owner, packageJson(sku, unique(), "100", "[" + item + "]")).statusCode()).isEqualTo(409);
        assertThat(call("POST", "/api/packages", owner, packageJson("SKU-" + UUID.randomUUID(), name, "100", "[" + item + "]")).statusCode()).isEqualTo(409);
    }

    /**
     * Posts a package as the owner.
     *
     * @param sku   the SKU suffix (made unique)
     * @param name  the name
     * @param price the price as JSON text
     * @param items the items as a JSON array
     * @return the response
     */
    private HttpResponse<String> post(String sku, String name, String price, String items) throws Exception {
        return call("POST", "/api/packages", owner, packageJson(sku + "-" + UUID.randomUUID(), name, price, items));
    }

    /**
     * Builds a package request body.
     *
     * @param sku   the SKU
     * @param name  the name
     * @param price the price as JSON text
     * @param items the items as a JSON array
     * @return the JSON body
     */
    private static String packageJson(String sku, String name, String price, String items) {
        return "{\"sku\":\"%s\",\"name\":\"%s\",\"price\":%s,\"items\":%s}".formatted(sku, name, price, items);
    }

    /**
     * What may change on an existing package: name, price and the active flag. Its contents are fixed: an {@code items} field in
     * the update is ignored, and the contents are exactly what they were.
     */
    @Test
    void aPackageKeepsItsContentsWhenUpdated() throws Exception {
        long product = newProduct(unique());
        long other = newProduct(unique());
        var created = call("POST", "/api/packages", owner,
                packageJson("SKU-" + UUID.randomUUID(), unique(), "100", "[{\"productId\":" + product + ",\"quantity\":3}]"));
        long id = idOf(created.body());

        var updated = call("PATCH", "/api/packages/" + id, owner,
                "{\"price\":250.5,\"active\":false,\"items\":[{\"productId\":" + other + ",\"quantity\":99}]}");

        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.body()).contains("\"price\":250.50", "\"active\":false", "\"quantity\":3");
        assertThat(updated.body()).doesNotContain("\"quantity\":99");
        assertThat(jdbc.queryForObject("select count(*) from package_item where package_id = ?", Long.class, id)).isEqualTo(1);
        assertThat(call("PATCH", "/api/packages/999999999", owner, "{\"active\":true}").statusCode()).isEqualTo(404);
    }

    /**
     * DEFECT (see findings): a PATCH may set a name or unit made only of spaces, because the update field is checked for
     * length but not for being blank. The create request rejects the same value with 400. When fixed, this test passes.
     */
    @Disabled("Defect Q-001: PATCH accepts a whitespace-only product name; see docs/quality/findings.md")
    @Test
    void aPatchCannotBlankOutAProductName() throws Exception {
        long id = newProduct(unique());

        var r = call("PATCH", "/api/products/" + id, owner, "{\"name\":\"   \"}");

        assertThat(r.statusCode()).isEqualTo(400);
    }

    /**
     * DEFECT (see findings): names are not trimmed, so "Feed " (with a trailing space) is stored as a separate product next
     * to "Feed". The unique constraint compares the exact text. When fixed, the second create is refused with 409.
     */
    @Disabled("Defect Q-002: names differing only by surrounding spaces are stored as different products; see docs/quality/findings.md")
    @Test
    void namesThatDifferOnlyByTrailingSpacesAreTheSameProduct() throws Exception {
        String name = unique();
        newProduct(name);

        var r = call("POST", "/api/products", owner, "{\"name\":\"" + name + " \",\"unit\":\"pc\"}");

        assertThat(r.statusCode()).isEqualTo(409);
    }
}
