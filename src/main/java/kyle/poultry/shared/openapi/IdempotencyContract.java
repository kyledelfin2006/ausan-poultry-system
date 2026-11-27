package kyle.poultry.shared.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.responses.ApiResponse;

/**
 * Describes the one operation whose success status depends on the request: creating a delivery.
 * <p>
 * {@code POST /api/deliveries} answers 201 for a new delivery and 200 when the same {@code Idempotency-Key} was already
 * used by the same user (the original delivery is returned, nothing new is created). The controller returns a
 * {@code ResponseEntity} with a dynamic status, so the generator can only see the 200; this class adds the 201 and
 * explains both, and documents the header.
 */
final class IdempotencyContract {

    private static final String KEY = "POST /api/deliveries";

    /** Not instantiable: static helpers only. */
    private IdempotencyContract() {}

    /**
     * Adds the 201 response, rewords the 200 and describes the {@code Idempotency-Key} header.
     *
     * @param openApi the scanned document, modified in place
     */
    static void apply(OpenAPI openApi) {
        var path = openApi.getPaths().get("/api/deliveries");
        if (path == null || path.getPost() == null) return;
        var post = path.getPost();
        var ok = post.getResponses().get("200");
        if (ok != null) {
            // The generated 200 carries the schema; reuse it for the 201 so both stay in sync.
            post.getResponses().addApiResponse("201", new ApiResponse().description("The delivery was created.").content(ok.getContent()));
            ok.setDescription("Repeat of an earlier request with the same `Idempotency-Key` by the same user: the "
                    + "original delivery, nothing new created. The same key with a different request is a 422.");
        }
        var conflict = post.getResponses().get("409");
        if (conflict != null) {
            conflict.setDescription("Clashes with current state (`CONFLICT`, e.g. insufficient stock), or an identical "
                    + "request with the same `Idempotency-Key` is still running (`REQUEST_IN_PROGRESS`, with a "
                    + "`Retry-After` header: wait, then resend the identical request with the same key).");
        }
        if (post.getParameters() != null) {
            post.getParameters().stream().filter(p -> "Idempotency-Key".equals(p.getName())).forEach(p -> p.setDescription(
                    "Optional, 1-100 characters, chosen by the client (a UUID is fine). Send the same key when retrying "
                            + "a request whose answer was lost, so the delivery is not created twice. Keys are per user; a "
                            + "request that failed does not use up its key."));
        }
    }
}
