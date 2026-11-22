package kyle.poultry.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;

/** Username matching at login, including old accounts whose names differ only by case. */
@ExtendWith(MockitoExtension.class)
class AuthServiceUnitTests {
    static final Instant NOW = Instant.parse("2026-11-22T04:00:00Z");

    @Mock AppUserRepository users;
    @Mock JwtEncoder jwt;
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    AuthService service;

    /** Builds the service with a cheap encoder and a signer that returns a fixed token. */
    @BeforeEach
    void setUp() {
        service = new AuthService(users, encoder, jwt, new SecurityProperties("x".repeat(32), null),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AppUser user(String name, String password, String display) {
        return new AppUser(name, encoder.encode(password), display, Role.ASSISTANT, NOW);
    }

    private void signer() {
        when(jwt.encode(any())).thenReturn(new Jwt("token", NOW, NOW.plusSeconds(60), Map.of("alg", "HS256"), Map.of("sub", "1")));
    }

    /** Two legacy accounts that differ only by case each sign in with their own exact spelling and password. */
    @Test
    void legacyLookAlikeAccountsCanEachSignInWithTheirOwnExactName() {
        var lower = user("legacy", "lower-case-password", "Lower");
        var upper = user("Legacy", "upper-case-password", "Upper");
        when(users.findAllByUsernameIgnoreCaseOrderByIdAsc("legacy")).thenReturn(List.of(lower, upper));
        when(users.findAllByUsernameIgnoreCaseOrderByIdAsc("Legacy")).thenReturn(List.of(lower, upper));
        signer();

        assertThat(service.login("legacy", "lower-case-password").displayName()).isEqualTo("Lower");
        assertThat(service.login("Legacy", "upper-case-password").displayName()).isEqualTo("Upper");
        assertThatThrownBy(() -> service.login("Legacy", "lower-case-password")).isInstanceOf(BadCredentialsException.class);
    }

    /** A spelling that matches several accounts but none exactly is ambiguous and matches nobody. */
    @Test
    void anAmbiguousSpellingMatchesNoAccount() {
        when(users.findAllByUsernameIgnoreCaseOrderByIdAsc("LEGACY"))
                .thenReturn(List.of(user("legacy", "lower-case-password", "Lower"), user("Legacy", "upper-case-password", "Upper")));

        assertThatThrownBy(() -> service.login("LEGACY", "lower-case-password")).isInstanceOf(BadCredentialsException.class);
    }

    /** A single account is found whatever the case or surrounding spaces of the submitted name. */
    @Test
    void aSingleAccountIsFoundIgnoringCaseAndSpaces() {
        when(users.findAllByUsernameIgnoreCaseOrderByIdAsc("MARIA")).thenReturn(List.of(user("maria", "a-long-enough-password", "Maria")));
        signer();

        assertThat(service.login("  MARIA ", "a-long-enough-password").displayName()).isEqualTo("Maria");
    }
}
