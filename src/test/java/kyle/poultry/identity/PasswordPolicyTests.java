package kyle.poultry.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kyle.poultry.shared.error.RuleViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure unit tests of {@link PasswordPolicy}: no Spring, no database. */
class PasswordPolicyTests {

    /** Ordinary long passphrases and the passwords the other tests use are accepted. */
    @ParameterizedTest
    @ValueSource(strings = {"a-long-enough-password", "correct horse battery staple", "Tr0ub4dor&3-extra", "0123456789ab", "ünïcödé-pässwörd"})
    void acceptsReasonablePasswords(String password) {
        assertThatCode(() -> PasswordPolicy.check(password, "someone")).doesNotThrowAnyException();
    }

    /** Fewer than 10 characters is refused, counting characters (not bytes). */
    @Test
    void rejectsShortPasswords() {
        assertThatThrownBy(() -> PasswordPolicy.check("short-pw9", "u")).isInstanceOf(RuleViolation.class)
                .hasMessageContaining("at least 10");
        // Nine emoji are nine characters even though they are 36 bytes.
        assertThatThrownBy(() -> PasswordPolicy.check("😀".repeat(9), "u")).isInstanceOf(RuleViolation.class);
    }

    /** BCrypt ignores everything after 72 bytes, so more than 72 bytes is refused; the limit is in bytes, not characters. */
    @Test
    void rejectsMoreThan72Bytes() {
        assertThatCode(() -> PasswordPolicy.check("x-".repeat(36), "u")).doesNotThrowAnyException();   // exactly 72
        assertThatThrownBy(() -> PasswordPolicy.check("x-".repeat(36) + "y", "u")).isInstanceOf(RuleViolation.class)
                .hasMessageContaining("too long");
        // 19 emoji is only 19 characters but 76 bytes.
        assertThatThrownBy(() -> PasswordPolicy.check("😀".repeat(19), "u")).isInstanceOf(RuleViolation.class)
                .hasMessageContaining("too long");
    }

    /** Blank, null and single-character-repeated passwords are refused. */
    @Test
    void rejectsBlankAndRepeatedCharacters() {
        assertThatThrownBy(() -> PasswordPolicy.check(null, "u")).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> PasswordPolicy.check("            ", "u")).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> PasswordPolicy.check("aaaaaaaaaaaa", "u")).isInstanceOf(RuleViolation.class)
                .hasMessageContaining("repeated");
    }

    /** The username (any case, surrounding spaces ignored) cannot double as the password. */
    @Test
    void rejectsPasswordEqualToUsername() {
        assertThatThrownBy(() -> PasswordPolicy.check("Juan-dela-Cruz", " juan-dela-cruz ")).isInstanceOf(RuleViolation.class)
                .hasMessageContaining("username");
    }

    /** Well-known weak passwords are refused even with different case, spaces or separators. */
    @ParameterizedTest
    @ValueSource(strings = {"password123", "PASSWORD123", "Pass word 123", "pass_word-123", "1234567890", "qwertyuiop"})
    void rejectsCommonPasswords(String password) {
        assertThatThrownBy(() -> PasswordPolicy.check(password, "someone")).isInstanceOf(RuleViolation.class)
                .hasMessageContaining("too common");
    }

    /** The error message never contains the password that was rejected. */
    @Test
    void neverEchoesThePassword() {
        assertThatThrownBy(() -> PasswordPolicy.check("password123", "u")).hasMessageNotContaining("password123");
    }
}
