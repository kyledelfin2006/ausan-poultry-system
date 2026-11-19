package kyle.poultry.identity;

import java.util.HashMap;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Runs after the token signature and expiry are verified. Looks the user up on every request (one primary-key read),
 * rejects missing or inactive users (401) and takes the role from the database, replacing the token's role claim so
 * that {@link Actor#from(Jwt)} and the granted authorities always agree with the current state. Package-private;
 * registered by {@link SecurityConfig}.
 */
final class RevocationAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final AppUserRepository users;

    /**
     * Creates the converter.
     *
     * @param users user lookup, queried once per authenticated request
     */
    RevocationAuthenticationConverter(AppUserRepository users) { this.users = users; }

    /**
     * Turns a verified token into an authentication for the user's CURRENT state.
     *
     * @param jwt the verified token
     * @return an authentication whose principal carries the database role and whose authority is {@code ROLE_<role>}
     * @throws BadCredentialsException when the subject is not a number, the user no longer exists, or the user is
     *                                 inactive; the filter answers 401
     */
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        long id;
        try {
            id = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new BadCredentialsException("Invalid token subject");
        }
        // Revocation: a deactivated or deleted user is refused even though the token itself is still valid.
        var user = users.findById(id).filter(AppUser::active)
                .orElseThrow(() -> new BadCredentialsException("User is inactive or no longer exists"));
        Role role = user.role();
        // Role changes apply at once: the token's own role claim is overwritten with the database value.
        var claims = new HashMap<>(jwt.getClaims());
        claims.put("role", role.name());
        var current = new Jwt(jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), jwt.getHeaders(), claims);
        return new JwtAuthenticationToken(current, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())), String.valueOf(id));
    }
}
