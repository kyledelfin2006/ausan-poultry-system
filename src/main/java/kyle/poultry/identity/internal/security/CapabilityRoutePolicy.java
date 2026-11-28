package kyle.poultry.identity.internal.security;

import kyle.poultry.identity.api.model.Capability;
import kyle.poultry.identity.api.model.Role;
import kyle.poultry.identity.api.security.RequiresCapability;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kyle.poultry.shared.openapi.RoutePolicy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

/**
 * Reads the capability a controller method requires and turns it into the roles that have it, for the API document.
 *
 * <p>Modules that may depend on identity use {@link RequiresCapability}. The audit module may not (identity depends on
 * audit), so its controller writes the equivalent {@code @PreAuthorize("hasAuthority('CAPABILITY_...')")} expression;
 * both forms are understood here, and the security matrix test checks the result for every route.
 */
@Component
class CapabilityRoutePolicy implements RoutePolicy {

    private static final Pattern AUTHORITY = Pattern.compile("hasAuthority\\('" + Capability.AUTHORITY_PREFIX + "([A-Z_]+)'\\)");

    @Override
    public Optional<List<String>> rolesAllowed(HandlerMethod handler) {
        return requiredBy(handler).map(required -> Arrays.stream(Role.values())
                .filter(role -> Capability.grantedTo(role).contains(required)).map(Role::name).toList());
    }

    /**
     * Finds the capability a handler requires; a method's own rule wins over its class's, as in Spring Security.
     *
     * @param handler the controller method
     * @return the required capability, or empty when the handler declares none
     */
    static Optional<Capability> requiredBy(HandlerMethod handler) {
        return fromRules(handler.getMethodAnnotation(RequiresCapability.class), handler.getMethodAnnotation(PreAuthorize.class))
                .or(() -> fromRules(handler.getBeanType().getAnnotation(RequiresCapability.class),
                        handler.getBeanType().getAnnotation(PreAuthorize.class)));
    }

    private static Optional<Capability> fromRules(RequiresCapability typed, PreAuthorize expression) {
        if (typed != null) return Optional.of(typed.value());
        if (expression == null) return Optional.empty();
        Matcher m = AUTHORITY.matcher(expression.value());
        return m.matches() ? Optional.of(Capability.valueOf(m.group(1))) : Optional.empty();
    }
}
