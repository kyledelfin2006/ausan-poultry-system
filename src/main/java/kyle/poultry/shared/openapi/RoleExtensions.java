package kyle.poultry.shared.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

/**
 * Publishes who may call each operation as the {@code x-roles} extension (a list such as {@code ["OWNER"]}), so the
 * frontend can hide actions a role cannot use instead of discovering a 403.
 * <p>
 * Three sources, in order of precedence: the {@link #SERVICE_ENFORCED} table (owner-only rules enforced inside a
 * service, invisible to annotations), {@code @PreAuthorize("hasRole('OWNER')")} on the controller method or class, and
 * the default "any logged-in role". Rules that apply to only part of an operation are explained in
 * {@link #PARTIAL_RULES} and appended to its description.
 */
final class RoleExtensions {

    /** Extension name on each operation. */
    static final String EXTENSION = "x-roles";

    private static final List<String> OWNER = List.of("OWNER");
    private static final List<String> ANY = List.of("OWNER", "ASSISTANT");

    /** Owner-only operations whose check lives in a service, keyed by {@code "METHOD /path"}. */
    static final Map<String, List<String>> SERVICE_ENFORCED = Map.of(
            "POST /api/deliveries/{id}/cancel", OWNER,
            "POST /api/deliveries/{id}/refund", OWNER,
            "PUT /api/stock/reorder-levels/{productId}", OWNER,
            "DELETE /api/stock/reorder-levels/{productId}", OWNER);

    /** Rules that restrict only some inputs of an operation, keyed by {@code "METHOD /path"}. */
    static final Map<String, String> PARTIAL_RULES = Map.of(
            "POST /api/stock/movements",
            "Any role may record PURCHASE, DONATION and USAGE movements; OPENING and ADJUSTMENT are OWNER only (403 "
                    + "for an assistant).",
            "GET /api/reports/dashboard",
            "The `profit` member is null for an assistant; every other member is the same for both roles.");

    /** Not instantiable: static helpers only. */
    private RoleExtensions() {}

    /**
     * Marks an operation OWNER-only when its handler carries an owner {@code @PreAuthorize} rule. Run by springdoc once
     * per controller method, before {@link #apply(OpenAPI)}.
     *
     * @param operation the operation being built
     * @param handler   the controller method behind it
     * @return the same operation
     */
    static Operation fromAnnotation(Operation operation, HandlerMethod handler) {
        // The method's own annotation wins over the class-level one, as in Spring Security.
        PreAuthorize rule = handler.getMethodAnnotation(PreAuthorize.class);
        if (rule == null) rule = handler.getBeanType().getAnnotation(PreAuthorize.class);
        if (rule != null && rule.value().contains("hasRole('OWNER')")) operation.addExtension(EXTENSION, OWNER);
        return operation;
    }

    /**
     * Fills in the roles of operations that have none yet and appends the partial-rule notes.
     *
     * @param openApi the scanned document, modified in place
     */
    static void apply(OpenAPI openApi) {
        openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            String key = method + " " + path;
            if (SERVICE_ENFORCED.containsKey(key)) operation.addExtension(EXTENSION, SERVICE_ENFORCED.get(key));
            // The login endpoint is public: it has no role list at all.
            else if ((operation.getExtensions() == null || !operation.getExtensions().containsKey(EXTENSION))
                    && !ErrorResponses.PUBLIC_PATHS.contains(path)) operation.addExtension(EXTENSION, ANY);
            String note = PARTIAL_RULES.get(key);
            if (note != null) operation.setDescription(operation.getDescription() == null ? note : operation.getDescription() + " " + note);
        }));
    }
}
