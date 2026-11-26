package kyle.poultry.identity;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is performing an action. Passed explicitly into services so domain code is testable without a security context.
 * It is the one identity type other modules may use; build it with {@link #from(Jwt)} in controllers only.
 *
 * @param userId the authenticated user's database id
 * @param role   the user's current role (taken from the database on every request, see
 *               {@link RevocationAuthenticationConverter})
 */
public record Actor(long userId, Role role) {

    /**
     * Builds the actor from the authenticated principal.
     *
     * <p>The {@code role} claim of the principal was already replaced with the database value by
     * {@link RevocationAuthenticationConverter}, so this never trusts the role stored in the token itself.
     *
     * @param jwt the authenticated principal produced by the resource-server filter
     * @return the actor for that user
     */
    public static Actor from(Jwt jwt) {
        // The subject is the user id (set at login); the role claim is the database role, not the signed one.
        return new Actor(Long.parseLong(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString("role")));
    }

    /** {@return true when this actor has the OWNER role; for permissions prefer {@link #can(Capability)}} */
    public boolean isOwner() { return role == Role.OWNER; }

    /**
     * Asks the central policy whether this actor may do something.
     *
     * @param capability the action
     * @return true when the actor's role has the capability
     */
    public boolean can(Capability capability) { return Capability.grantedTo(role).contains(capability); }

    /**
     * Refuses the action unless the actor may do it.
     *
     * @param capability the action
     * @throws org.springframework.security.access.AccessDeniedException (HTTP 403) when the role lacks the capability
     */
    public void require(Capability capability) {
        if (!can(capability))
            throw new org.springframework.security.access.AccessDeniedException("Requires capability " + capability);
    }
}
