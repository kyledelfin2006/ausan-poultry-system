package kyle.poultry.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import kyle.poultry.audit.api.AuditApi;
import kyle.poultry.shared.ConflictException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceUnitTests {
    @Mock AppUserRepository users;
    @Mock PasswordEncoder encoder;
    @Mock AuditApi audit;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-22T04:00:00Z"), ZoneId.of("Asia/Manila"));
    private UserService service;

    @BeforeEach
    void setUp() { service = new UserService(users, encoder, clock, audit); }

    @Test
    void uniqueUsernameRaceUsesTheSameConflictAsThePrecheck() {
        when(users.existsByUsernameIgnoreCase("maria")).thenReturn(false);
        when(encoder.encode("a-long-enough-password")).thenReturn("hash");
        var databaseViolation = new ConstraintViolationException("duplicate username", new SQLException("duplicate"),
                "app_user_username_key");
        when(users.saveAndFlush(any(AppUser.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate username", databaseViolation));

        assertThatThrownBy(() -> service.create("maria", "a-long-enough-password", "Maria", Role.ASSISTANT))
                .isInstanceOf(ConflictException.class).hasMessage("Username already taken");
        verifyNoInteractions(audit);
    }

    @Test
    void unrelatedIntegrityErrorsKeepTheirGenericMapping() {
        when(users.existsByUsernameIgnoreCase("maria")).thenReturn(false);
        when(encoder.encode("a-long-enough-password")).thenReturn("hash");
        var databaseViolation = new ConstraintViolationException("other constraint", new SQLException("duplicate"),
                "app_user_display_name_check");
        var failure = new DataIntegrityViolationException("other constraint", databaseViolation);
        when(users.saveAndFlush(any(AppUser.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.create("maria", "a-long-enough-password", "Maria", Role.ASSISTANT))
                .isSameAs(failure);
        verifyNoInteractions(audit);
    }
}
