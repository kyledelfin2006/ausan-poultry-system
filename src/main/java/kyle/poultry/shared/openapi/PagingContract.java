package kyle.poultry.shared.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Describes paging the way the API really behaves.
 * <p>
 * Paged endpoints take a Spring Data {@code Pageable}, which the generator describes as one opaque {@code pageable}
 * parameter. The application accepts {@code page}, {@code size} and {@code sort} as ordinary query parameters and
 * serialises pages as {@code { "content": [...], "page": { "size", "number", "totalElements", "totalPages" } }} (see
 * {@code PageSerializationConfig}); this class rewrites the parameter and documents the {@code page} object.
 */
final class PagingContract {

    private static final String PAGEABLE_REF = "#/components/schemas/Pageable";

    /** Not instantiable: static helpers only. */
    private PagingContract() {}

    /**
     * Rewrites paged parameters and responses in place and drops the now unused Spring-internal schemas.
     *
     * @param openApi the scanned document, modified in place
     */
    static void apply(OpenAPI openApi) {
        var schemas = openApi.getComponents().getSchemas();
        schemas.put("PageMetadata", pageMetadata());

        // springdoc already describes the page wrapper as content + page (it sees the DTO serialisation mode); what is left
        // over is the Spring-internal Pageable parameter schema and its helpers.
        schemas.keySet().removeIf(name -> name.equals("Pageable") || name.equals("PageableObject") || name.equals("SortObject"));

        openApi.getPaths().values().forEach(item -> item.readOperations().forEach(operation -> {
            if (operation.getParameters() == null) return;
            var rewritten = new ArrayList<Parameter>();
            for (Parameter p : operation.getParameters()) {
                if (p.getSchema() != null && PAGEABLE_REF.equals(p.getSchema().get$ref())) rewritten.addAll(pagingParameters());
                else rewritten.add(p);
            }
            operation.setParameters(rewritten);
        }));
    }

    /**
     * The counters that describe one page of a larger result.
     *
     * @return the {@code PageMetadata} component schema
     */
    private static Schema<?> pageMetadata() {
        return new ObjectSchema()
                .description("Where this page sits in the whole result.")
                .addProperty("size", new IntegerSchema().description("Requested page size."))
                .addProperty("number", new IntegerSchema().description("Zero-based index of this page."))
                .addProperty("totalElements", new IntegerSchema().format("int64").description("Rows in the whole result."))
                .addProperty("totalPages", new IntegerSchema().description("Number of pages at this size."))
                .required(List.of("size", "number", "totalElements", "totalPages"));
    }

    /**
     * The three query parameters every paged endpoint accepts.
     *
     * @return {@code page}, {@code size} and {@code sort}
     */
    private static List<Parameter> pagingParameters() {
        return List.of(
                new Parameter().name("page").in("query").required(false)
                        .description("Zero-based page index.")
                        .schema(new IntegerSchema()._default(0).minimum(BigDecimal.ZERO)),
                new Parameter().name("size").in("query").required(false)
                        .description("Rows per page.")
                        .schema(new IntegerSchema()._default(20).minimum(BigDecimal.ONE)),
                new Parameter().name("sort").in("query").required(false)
                        .description("`property,asc` or `property,desc`; repeat the parameter for several sort keys.")
                        .schema(new ArraySchema().items(new StringSchema())));
    }
}
