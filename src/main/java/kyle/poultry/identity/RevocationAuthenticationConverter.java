package kyle.poultry.identity;

import java.util.HashMap;
import java.util.List;
import kyle.poultry.shared.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Runs after the token signature and expiry are verified. Looks the user up on every request (one primary-key read),
 * rejects missing or inactive users and tokens whose version is stale (401) and takes the role from the database, replacing the token's role claim so
 * that {@link Actor#from(Jwt)} and the granted authorities always agree with the current state. Package-private;
 * registered by {@link SecurityConfig}.
 */
final class RevocationAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    /** Authority used to limit sessions that still carry a bootstrap or owner-reset password. */
    static final String MUST_CHANGE_PASSWORD = "MUST_CHANGE_PASSWORD";
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
     * @return an authentication whose principal carries the database role and any temporary-password restriction
     * @throws BadCredentialsException when the subject is not a number, the user no longer exists, the user is
     *                                 inactive, or the token was revoked by a sign-out or password change; the
     *                                 filter answers 401
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
        // Sign-out and password changes: a token from an older "version" of the user is refused. Tokens issued before
        // the claim existed have none and count as version 0, which is every existing user's starting value.
        Number issuedVersion = jwt.getClaim("ver");
        if ((issuedVersion == null ? 0 : issuedVersion.intValue()) != user.tokenVersion())
            throw new BadCredentialsException("Token has been revoked");
        // Make the user id appear on every log line of this request (the filter that set the request id clears it).
        MDC.put(RequestIdFilter.MDC_USER_ID, String.valueOf(id));
        Role role = user.role();
        // Role changes apply at once: the token's own role claim is overwritten with the database value.
        var claims = new HashMap<>(jwt.getClaims());
        claims.put("role", role.name());
        var current = new Jwt(jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), jwt.getHeaders(), claims);
        var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        if (user.mustChangePassword()) authorities.add(new SimpleGrantedAuthority(MUST_CHANGE_PASSWORD));
        return new JwtAuthenticationToken(current, authorities, String.valueOf(id));
    }
}
