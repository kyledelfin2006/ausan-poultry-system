package kyle.poultry.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.shared.ConflictException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Proves against a real Postgres that a duplicate username is reported under the constraint name
 * {@code UserService} relies on to turn the insert race into the same 409 as the pre-check.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UsernameConstraintTests {
    @Autowired AppUserRepository users;
    @Autowired UserService service;

    @Test
    void databaseNamesTheUsernameConstraintOnADuplicateInsert() {
        String name = "dup-" + UUID.randomUUID().toString().substring(0, 8);
        users.saveAndFlush(new AppUser(name, "hash", "First", Role.ASSISTANT, Instant.now()));

        assertThatThrownBy(() -> users.saveAndFlush(new AppUser(name, "hash", "Second", Role.ASSISTANT, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(e.getCause()).isInstanceOf(ConstraintViolationException.class)
                        .extracting(c -> ((ConstraintViolationException) c).getConstraintName())
                        .isEqualTo("app_user_username_key"));
    }

    @Test
    void serviceRejectsAnExistingUsernameWithTheConflictError() {
        String name = "dup-" + UUID.randomUUID().toString().substring(0, 8);
        service.create(name, "a-long-enough-password", "First", Role.ASSISTANT);

        assertThatThrownBy(() -> service.create(name, "a-long-enough-password", "Second", Role.ASSISTANT))
                .isInstanceOf(ConflictException.class).hasMessage("Username already taken");
    }
}
