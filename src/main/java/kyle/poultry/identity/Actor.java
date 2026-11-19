package kyle.poultry.identity;

import org.springframework.security.oauth2.jwt.Jwt;

/** Who is performing an action. Passed explicitly into services so domain code is testable without a security context. */
public record Actor(long userId, Role role) {

    public static Actor from(Jwt jwt) {
        return new Actor(Long.parseLong(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString("role")));
    }

    public boolean isOwner() { return role == Role.OWNER; }
}
