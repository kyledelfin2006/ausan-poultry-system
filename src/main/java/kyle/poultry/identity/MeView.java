package kyle.poultry.identity;

import java.time.Instant;

/**
 * Response of {@code GET /api/auth/me}: who the token belongs to, as the database sees them right now.
 *
 * @param id             database id
 * @param username       login name
 * @param displayName    name shown in the UI
 * @param role           current role (a role change applies at once, even to an existing token)
 * @param tokenExpiresAt when the token used for this call stops being accepted, so the UI can warn before it expires
 */
record MeView(long id, String username, String displayName, Role role, Instant tokenExpiresAt) {}
