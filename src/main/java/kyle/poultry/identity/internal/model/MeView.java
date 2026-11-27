package kyle.poultry.identity.internal.model;

import kyle.poultry.identity.api.model.Capability;
import kyle.poultry.identity.api.model.Role;
import java.time.Instant;

/**
 * Response of {@code GET /api/auth/me}: who the token belongs to, as the database sees them right now.
 *
 * @param id             database id
 * @param username       login name
 * @param displayName    name shown in the UI
 * @param role           current role (a role change applies at once, even to an existing token)
 * @param capabilities   what this user may do, from the central policy ({@link Capability}); show or hide actions with this, not the role
 * @param mustChangePassword true when the token is limited to profile, logout and password change (login and health stay public)
 * @param tokenExpiresAt when the token used for this call stops being accepted, so the UI can warn before it expires
 */
public record MeView(long id, String username, String displayName, Role role, java.util.List<Capability> capabilities,
              boolean mustChangePassword, Instant tokenExpiresAt) {}
