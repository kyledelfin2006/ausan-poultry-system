package kyle.poultry.shared.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the whole HTTP API as an OpenAPI 3 document, served by springdoc at {@code GET /v3/api-docs} (JSON) and
 * {@code GET /v3/api-docs.yaml}. The endpoint sits behind the normal bearer-token rule like every other path.
 * <p>
 * springdoc derives paths, parameters and request/response bodies from the controllers. This class holds the document's
 * fixed parts (title, JWT security scheme) and runs, in a fixed order, the helpers that add what the controllers cannot
 * say by themselves: error responses ({@link ErrorResponses}), paging ({@link PagingContract}), the delivery
 * idempotency header ({@link IdempotencyContract}), CSV downloads ({@link CsvContract}), roles ({@link RoleExtensions}) and naming
 * ({@link OperationNormalizer}). A committed snapshot, {@code docs/api/openapi.json}, is compared with the generated
 * document by {@code OpenApiSnapshotTests}, so every change to the contract shows up in review.
 * <p>
 * It knows no module: it must stay free of imports from {@code identity}, {@code catalog} and the other modules.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    /** Name of the bearer-token security scheme in {@code components.securitySchemes}. */
    static final String BEARER = "bearerAuth";

    /**
     * The document's fixed parts: title, version, relative server URL, the bearer scheme and a global requirement that
     * every operation is secured unless it opts out.
     *
     * @return the skeleton springdoc fills with the scanned paths
     */
    @Bean
    OpenAPI ausanOpenApi() {
        var bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                .description("Token from POST /api/auth/login, sent as `Authorization: Bearer <token>`. "
                        + "Tokens last 8 hours; there is no refresh, log in again.");
        return new OpenAPI()
                .info(new Info().title("Ausan Farm System API").version("1")
                        .description("Backend API for Ausan Poultry Farm: stock, deliveries, finance and reports. "
                                + "Money is PHP with 2 decimals; dates are Manila business dates. Errors are RFC 9457 "
                                + "problem details with a stable `code` (see the Problem schema)."))
                // A relative URL keeps the snapshot independent of the host and port it was generated on.
                .servers(List.of(new Server().url("/").description("Same origin as this document")))
                .components(new Components().addSecuritySchemes(BEARER, bearer))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /**
     * Reads {@code @PreAuthorize} owner rules from the controller method behind each operation.
     *
     * @return the per-operation customizer springdoc runs while scanning
     */
    @Bean
    OperationCustomizer ownerOnlyFromAnnotations() {
        return RoleExtensions::fromAnnotation;
    }

    /**
     * Applies the document-wide rewrites after scanning. The order matters: errors are added first, paging then
     * rewrites parameters, roles need the final paths, and naming runs last so it sees every operation as published.
     *
     * @return the whole-document customizer springdoc runs after scanning
     */
    @Bean
    OpenApiCustomizer contractCustomizer() {
        return openApi -> {
            ErrorResponses.apply(openApi);
            PagingContract.apply(openApi);
            IdempotencyContract.apply(openApi);
            CsvContract.apply(openApi);
            RoleExtensions.apply(openApi);
            OperationNormalizer.apply(openApi);
        };
    }
}
