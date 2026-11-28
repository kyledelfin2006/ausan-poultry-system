package kyle.poultry.shared.openapi;

import java.util.List;
import java.util.Optional;
import org.springframework.web.method.HandlerMethod;

/**
 * Tells the API documentation which roles may call a controller method, without this module knowing how permissions
 * are defined. The identity module implements it from its capability policy, so the published {@code x-roles} always
 * follows the rules that are actually enforced.
 */
public interface RoutePolicy {

    /**
     * Names the roles allowed to call a handler.
     *
     * @param handler the controller method behind an operation
     * @return the allowed role names, or empty when this policy has no rule for the handler
     */
    Optional<List<String>> rolesAllowed(HandlerMethod handler);
}
