package kyle.poultry.shared.openapi;

import kyle.poultry.support.ApiTestClient;
import static org.assertj.core.api.Assertions.assertThat;

import kyle.poultry.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Pins the JSON shape of every paged endpoint to the documented one:
 * {@code { "content": [...], "page": { size, number, totalElements, totalPages } }}, with none of Spring Data's
 * internal fields.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class PagingContractTests {

    @LocalServerPort int port;

    /**
     * All paged endpoints (including the audit trail) answer with {@code content} and a {@code page} object, and expose no Spring internals.
     *
     * @throws Exception when a request fails
     */
    @Test
    void pagedEndpointsUseTheDocumentedShape() throws Exception {
        var api = new ApiTestClient(port);
        String owner = api.loginAsOwner();
        for (String path : new String[] {
                "/api/deliveries?size=5",
                "/api/finance/entries?from=2020-01-01&to=2026-12-31&size=5",
                "/api/stock/movements?productId=1&branchId=1&size=5",
                "/api/audit?size=5"}) {
            var response = api.json("GET", path, owner, null);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path)
                    .contains("\"content\":[")
                    .containsPattern("\"page\":\\{\"size\":5,\"number\":0,\"totalElements\":\\d+,\"totalPages\":\\d+\\}")
                    .doesNotContain("\"pageable\"", "\"numberOfElements\"", "\"first\"");
        }
    }
}
