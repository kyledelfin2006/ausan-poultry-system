package kyle.poultry.identity;

import java.time.Clock;
import java.time.Instant;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
class AuthService {
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;
    private final SecurityProperties props;
    private final Clock clock;
    /** Compared against when the username is unknown, so response time does not reveal which usernames exist. */
    private final String dummyHash;

    AuthService(AppUserRepository users, PasswordEncoder encoder, JwtEncoder jwt, SecurityProperties props, Clock clock) {
        this.users = users; this.encoder = encoder; this.jwt = jwt; this.props = props; this.clock = clock;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    LoginResponse login(String username, String password) {
        var user = users.findByUsername(username).orElse(null);
        boolean ok = encoder.matches(password, user == null ? dummyHash : user.passwordHash());
        if (user == null || !ok || !user.active()) throw new BadCredentialsException("Invalid username or password");

        Instant now = clock.instant();
        Instant expires = now.plus(props.tokenTtl());
        var claims = JwtClaimsSet.builder()
                .subject(String.valueOf(user.id()))
                .claim("role", user.role().name())
                .claim("username", user.username())
                .issuedAt(now).expiresAt(expires).build();
        String token = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, expires, user.role(), user.displayName());
    }
}
