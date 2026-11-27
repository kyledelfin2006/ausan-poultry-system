package kyle.poultry.shared.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import java.util.List;
import java.util.Map;
import org.springframework.web.method.HandlerMethod;

/**
 * Publishes who may call each operation as the {@code x-roles} extension (a list such as {@code ["OWNER"]}), so the
 * frontend can hide actions a role cannot use instead of discovering a 403.
 * <p>
 * Three sources, in order of precedence: the {@link #SERVICE_ENFORCED} table (owner-only rules enforced inside a
 * service, invisible to annotations), a {@link RoutePolicy} rule (the identity module derives it from its capability policy), and
 * the default "any logged-in role". Rules that apply only to some actions or response fields are explained in
 * {@link #PARTIAL_RULES} and appended to its description.
 */
final class RoleExtensions {

    /** Extension name on each operation. */
    static final String EXTENSION = "x-roles";

    private static final List<String> OWNER = List.of("OWNER");
    private static final List<String> ANY = List.of("OWNER", "ASSISTANT");

    /** Operations whose capability check lives in a service (owner-only today), keyed by {@code "METHOD /path"}. */
    static final Map<String, List<String>> SERVICE_ENFORCED = Map.of(
            "POST /api/deliveries/{id}/cancel", OWNER,
            "POST /api/deliveries/{id}/refund", OWNER,
            "PUT /api/stock/reorder-levels/{productId}", OWNER,
            "DELETE /api/stock/reorder-levels/{productId}", OWNER);

    /** Rules that restrict only some inputs of an operation, keyed by {@code "METHOD /path"}. */
    static final Map<String, String> PARTIAL_RULES = Map.ofEntries(
            Map.entry("POST /api/stock/movements",
                    "Any role may record PURCHASE, DONATION and USAGE movements; OPENING and ADJUSTMENT are OWNER only "
                            + "(403 for an assistant). The purchase response includes the unit cost entered by that caller."),
            Map.entry("GET /api/stock/balances", "Average cost is omitted for assistants; quantities remain visible."),
            Map.entry("GET /api/stock/movements", "Unit cost is omitted for assistants except on PURCHASE rows they recorded; quantities remain visible."),
            Map.entry("GET /api/stock/summary", "Per-branch average cost is omitted for assistants; quantities remain visible."),
            Map.entry("GET /api/finance/entries", "Assistants receive only SALE, SALE_REVERSAL, PAYMENT_IN, and REFUND entries."),
            Map.entry("GET /api/finance/entries.csv", "Assistants receive only SALE, SALE_REVERSAL, PAYMENT_IN, and REFUND entries."),
            Map.entry("GET /api/reports/summary", "Cost and cash-movement fields are omitted for assistants; sales and collection fields remain."),
            Map.entry("GET /api/reports/summary.csv", "Cost and cash-movement rows are omitted for assistants; sales and collection rows remain."),
            Map.entry("GET /api/reports/series", "Cost and cash-movement fields are omitted for assistants; sales and collection fields remain."),
            Map.entry("GET /api/reports/series.csv", "Cost and cash-movement columns are omitted for assistants; sales and collection columns remain."),
            Map.entry("GET /api/reports/dashboard",
                    "Cost, cash-movement, inventory-value, and profit members are omitted for assistants; `netSales`, "
                            + "`netCollected`, `lowStockProducts`, and `retainedOnCancelledDeliveries` remain."));

    /** Not instantiable: static helpers only. */
    private RoleExtensions() {}

    /**
     * Publishes the roles a {@link RoutePolicy} allows for the operation's handler. Run by springdoc once per controller
     * method, before {@link #apply(OpenAPI)}.
     *
     * @param operation the operation being built
     * @param handler   the controller method behind it
     * @param policies  the sources of route rules; the first one with a rule for the handler decides
     * @return the same operation
     */
    static Operation fromPolicies(Operation operation, HandlerMethod handler, List<RoutePolicy> policies) {
        for (RoutePolicy policy : policies) {
            var roles = policy.rolesAllowed(handler);
            if (roles.isPresent()) {
                operation.addExtension(EXTENSION, roles.get());
                break;
            }
        }
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
