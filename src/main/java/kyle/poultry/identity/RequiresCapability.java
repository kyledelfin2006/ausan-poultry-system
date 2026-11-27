package kyle.poultry.identity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Restricts a controller class or method to callers whose role has the given {@link Capability}.
 *
 * <p>This replaces {@code @PreAuthorize("hasRole('OWNER')")}: the route rule now names the action and the answer comes
 * from {@link Capability#grantedTo}, the same place the services ask. The published {@code x-roles} of each operation
 * is derived from it too, so granting an assistant a capability is one edit in {@link Capability}.
 *
 * <p>Checked against the {@code CAPABILITY_*} authorities that {@link RevocationAuthenticationConverter} derives from
 * the user's current database role, so a role change applies at once.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Capability.AUTHORITY_PREFIX + "{value}')")
public @interface RequiresCapability {
    /** {@return the capability the caller must have} */
    Capability value();
}
