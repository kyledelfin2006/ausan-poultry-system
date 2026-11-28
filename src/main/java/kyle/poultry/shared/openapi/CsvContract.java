package kyle.poultry.shared.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;

/**
 * Describes the CSV download endpoints as what they are: files.
 * <p>
 * The export controllers return the file as raw bytes, which the generator reports as a base64 string in a JSON body.
 * In fact the answer is {@code text/csv; charset=UTF-8} with an attachment {@code Content-Disposition}, so every path
 * ending in {@code .csv} gets that media type and header instead. The column layouts are in
 * {@code docs/api/rules.md}.
 */
final class CsvContract {

    /** Not instantiable: static helpers only. */
    private CsvContract() {}

    /**
     * Rewrites the 200 response of every {@code *.csv} operation.
     *
     * @param openApi the scanned document, modified in place
     */
    static void apply(OpenAPI openApi) {
        openApi.getPaths().forEach((path, item) -> {
            if (!path.endsWith(".csv") || item.getGet() == null) return;
            var ok = item.getGet().getResponses().get("200");
            if (ok == null) return;
            var file = new MediaType().schema(new StringSchema()
                    .description("CSV text, UTF-8 with a byte-order mark, CRLF line endings."));
            ok.setDescription("A CSV file download.");
            ok.setContent(new Content().addMediaType("text/csv", file));
            ok.addHeaderObject("Content-Disposition", new Header()
                    .description("`attachment; filename=\"...\"`: the suggested file name, which includes the period.")
                    .schema(new StringSchema()));
        });
        // A streamed export is scanned as an empty object schema that no response refers to once the content is replaced.
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null)
            openApi.getComponents().getSchemas().remove("StreamingResponseBody");
    }
}
