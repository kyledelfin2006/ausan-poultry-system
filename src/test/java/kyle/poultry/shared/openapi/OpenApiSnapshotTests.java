package kyle.poultry.shared.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.support.ApiTestClient;
import kyle.poultry.shared.error.ErrorCodes;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the committed API contract honest: the OpenAPI document the running application generates must equal the
 * snapshot in {@code docs/api/openapi.json}. A controller change that alters the contract therefore fails this test
 * until the snapshot is regenerated, which makes every contract change visible in code review.
 * <p>
 * Regenerate with {@code ./mvnw test -Dtest=OpenApiSnapshotTests -Dopenapi.update=true} and commit the file.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OpenApiSnapshotTests {

    /** The committed contract, relative to the project root (Maven's working directory). */
    private static final Path SNAPSHOT = Path.of("docs/api/openapi.json");

    /** The hand-written error catalogue that must mention every code. */
    private static final Path ERROR_DOC = Path.of("docs/api/errors.md");

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @LocalServerPort int port;
    ApiTestClient api;
    String owner;

    /**
     * Creates the client and logs in as the owner; the spec endpoint needs a token like any other path.
     *
     * @throws Exception when login fails
     */
    @BeforeEach
    void setUp() throws Exception {
        api = new ApiTestClient(port);
        owner = api.loginAsOwner();
    }

    /**
     * Fetches the generated document and returns it in a canonical form: parsed, keys sorted at every level, pretty
     * printed, LF line endings. Sorting makes the comparison independent of the order springdoc happens to emit.
     *
     * @return canonical JSON text ending in a newline
     * @throws Exception when the endpoint does not answer 200
     */
    private String generated() throws Exception {
        var response = api.json("GET", "/v3/api-docs", owner, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        // The pretty printer uses the platform line separator (CRLF on Windows); the contract is the same on every OS.
        return JSON.writeValueAsString(sorted(JSON.readValue(response.body(), Object.class))).replace("\r\n", "\n") + "\n";
    }

    /**
     * Recursively copies maps into key-sorted maps; lists keep their order (array order is meaningful in OpenAPI).
     *
     * @param node a parsed JSON value
     * @return the same value with every object's keys sorted
     */
    @SuppressWarnings("unchecked")
    private static Object sorted(Object node) {
        if (node instanceof Map<?, ?> map) {
            var out = new TreeMap<String, Object>();
            map.forEach((k, v) -> out.put((String) k, sorted(v)));
            return out;
        }
        if (node instanceof List<?> list) {
            var out = new ArrayList<Object>();
            list.forEach(v -> out.add(sorted(v)));
            return out;
        }
        return node;
    }

    /**
     * The generated contract equals the committed snapshot. With {@code -Dopenapi.update=true} the snapshot is
     * rewritten instead, so regenerating is one command.
     *
     * @throws Exception when the document cannot be fetched or the snapshot cannot be read or written
     */
    @Test
    void generatedSpecMatchesCommittedSnapshot() throws Exception {
        String actual = generated();
        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(SNAPSHOT).as("snapshot missing: run with -Dopenapi.update=true").exists();
        // Git on Windows may check the file out with CRLF; the content, not the line ending, is the contract.
        String expected = Files.readString(SNAPSHOT, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(actual)
                .as("The API contract changed. If intended, regenerate %s with -Dopenapi.update=true and commit it.", SNAPSHOT)
                .isEqualTo(expected);
    }

    /**
     * Every operation says how it is secured: public ones (login) override the global bearer requirement with an
     * empty list, all others document the 401 and 403 answers. A new endpoint can therefore never be published with
     * its access rule unstated.
     *
     * @throws Exception when the document cannot be fetched
     */
    @Test
    void everyOperationStatesItsSecurityOutcome() throws Exception {
        var paths = (Map<?, ?>) JSON.readValue(api.json("GET", "/v3/api-docs", owner, null).body(), Map.class).get("paths");
        assertThat(paths).isNotEmpty();
        paths.forEach((path, item) -> ((Map<?, ?>) item).forEach((method, op) -> {
            var operation = (Map<?, ?>) op;
            var responses = (Map<?, ?>) operation.get("responses");
            if (ErrorResponses.PUBLIC_PATHS.contains(path)) {
                assertThat(operation.get("security")).as("%s %s", method, path).isEqualTo(List.of());
            } else {
                assertThat(responses.keySet().stream().map(String::valueOf).toList())
                        .as("%s %s", method, path).contains("401", "403");
            }
        }));
    }

    /**
     * The hand-written error catalogue documents every code the API can return, and the published {@code Problem}
     * schema lists exactly the same codes.
     *
     * @throws IOException when the catalogue cannot be read
     * @throws Exception   when the document cannot be fetched
     */
    @Test
    void errorCatalogueAndSchemaCoverEveryCode() throws Exception {
        String catalogue = Files.readString(ERROR_DOC, StandardCharsets.UTF_8);
        assertThat(catalogue).as("docs/api/errors.md").contains(ErrorCodes.ALL.toArray(String[]::new));

        var spec = (Map<?, ?>) JSON.readValue(api.json("GET", "/v3/api-docs", owner, null).body(), Map.class);
        var problem = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) spec.get("components")).get("schemas")).get("Problem");
        var code = (Map<?, ?>) ((Map<?, ?>) problem.get("properties")).get("code");
        assertThat(((List<?>) code.get("enum")).stream().map(String::valueOf).toList())
                .containsExactlyElementsOf(ErrorCodes.ALL);
    }
}
