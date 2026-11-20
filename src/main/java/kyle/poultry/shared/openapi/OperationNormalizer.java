package kyle.poultry.shared.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Gives operations the names, groups and media types a client generator and a human reader expect.
 * <ul>
 *   <li><b>Operation ids</b> are the controller method names, which are unique except where two controllers use the
 *       same name (the generator would then emit {@code list_1}); colliding ids are prefixed with the resource, e.g.
 *       {@code deliveriesList}, so the id stays readable and stable.</li>
 *   <li><b>Tags</b> group operations by resource area ({@code Catalog}, {@code Deliveries}, ...) instead of by
 *       controller class name.</li>
 *   <li><b>Media types</b>: responses with no declared {@code produces} are shown as {@code *}{@code /*}; the API only
 *       ever answers JSON, so they are rewritten to {@code application/json}.</li>
 * </ul>
 */
final class OperationNormalizer {

    /** Resource (first path segment after {@code /api}) to the tag it is grouped under. */
    private static final Map<String, String> TAGS = Map.of(
            "products", "Catalog", "branches", "Catalog", "packages", "Catalog");

    /** Not instantiable: static helpers only. */
    private OperationNormalizer() {}

    /**
     * Normalises ids, tags and media types of every operation, and rebuilds the document's tag list.
     *
     * @param openApi the scanned document, modified in place
     */
    static void apply(OpenAPI openApi) {
        // First pass: how often does each base id occur? More than once means a collision.
        var occurrences = new HashMap<String, Integer>();
        openApi.getPaths().values().forEach(item -> item.readOperations().forEach(op ->
                occurrences.merge(baseId(op), 1, Integer::sum)));

        var tags = new TreeMap<String, Tag>();
        openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
            String resource = resource(path);
            String base = baseId(operation);
            operation.setOperationId(occurrences.get(base) > 1 ? resource + capitalize(base) : base);
            String tag = TAGS.getOrDefault(resource, capitalize(resource));
            operation.setTags(List.of(tag));
            tags.putIfAbsent(tag, new Tag().name(tag));
            useJson(operation);
        }));
        // Replace the generator's per-controller tags with one entry per area, sorted for a stable document.
        openApi.setTags(new ArrayList<>(tags.values()));
    }

    /**
     * The generator's id without its collision suffix ({@code list_1} becomes {@code list}).
     *
     * @param operation the operation
     * @return the controller method name
     */
    private static String baseId(Operation operation) {
        return operation.getOperationId().replaceAll("_\\d+$", "");
    }

    /**
     * The resource a path belongs to: the segment after {@code /api/}.
     *
     * @param path the URL template, e.g. {@code /api/deliveries/{id}/done}
     * @return {@code deliveries} for that example
     */
    private static String resource(String path) {
        String rest = path.startsWith("/api/") ? path.substring("/api/".length()) : path.substring(1);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    /**
     * Upper-cases the first letter.
     *
     * @param word a non-empty word
     * @return the word starting with a capital
     */
    private static String capitalize(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    /**
     * Rewrites the wildcard media type of every response of an operation to JSON.
     *
     * @param operation the operation whose responses are fixed
     */
    private static void useJson(Operation operation) {
        operation.getResponses().values().forEach(response -> {
            Content content = response.getContent();
            if (content == null || !content.containsKey("*/*")) return;
            MediaType wildcard = content.remove("*/*");
            content.addMediaType("application/json", wildcard);
        });
    }
}
