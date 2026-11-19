package kyle.poultry.identity;

import java.time.Instant;

record LoginResponse(String token, Instant expiresAt, Role role, String displayName) {}
