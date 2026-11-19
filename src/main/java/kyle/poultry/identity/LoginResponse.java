package kyle.poultry.identity;

import java.time.Instant;

/**
 * Successful login result returned by {@code POST /api/auth/login}. Package-private; serialized to JSON by the
 * controller.
 *
 * @param token       the signed JWT to send as {@code Authorization: Bearer ...}
 * @param expiresAt   when the token stops being accepted
 * @param role        the user's role at login time
 * @param displayName the name to show in the UI
 */
record LoginResponse(String token, Instant expiresAt, Role role, String displayName) {}
