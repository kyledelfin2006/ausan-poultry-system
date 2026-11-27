package kyle.poultry.identity.internal.model;

import kyle.poultry.identity.api.model.Role;
import java.time.Instant;

/**
 * Successful login result returned by {@code POST /api/auth/login}. Internal to the identity module; serialized to JSON by the
 * controller.
 *
 * @param token       the signed JWT to send as {@code Authorization: Bearer ...}
 * @param expiresAt   when the token stops being accepted
 * @param role        the user's role at login time
 * @param displayName the name to show in the UI
 */
public record LoginResponse(String token, Instant expiresAt, Role role, String displayName) {}
