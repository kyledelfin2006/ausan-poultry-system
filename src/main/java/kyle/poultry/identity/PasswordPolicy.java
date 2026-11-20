package kyle.poultry.identity;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import kyle.poultry.shared.RuleViolation;

/**
 * The rules a NEW password must satisfy, applied whenever a password is chosen (first owner, new user, owner reset,
 * own change). Stateless and package-private; used by {@link UserService} only.
 *
 * <p>The rules follow current guidance (length and a blocklist rather than character-class rules): at least 10
 * characters; at most 72 UTF-8 bytes (BCrypt silently ignores anything longer, so longer input would give a false
 * sense of strength); not blank; not one repeated character; not the username; not a well-known weak password.
 * Request-body annotations ({@code @Size}) still reject obviously out-of-range input with 400 first; this class adds
 * the rules that need more context and answers 422 with a message the user can act on.
 */
final class PasswordPolicy {
    /** Fewest characters accepted. */
    static final int MIN_LENGTH = 10;
    /** Most UTF-8 bytes accepted (BCrypt's input limit). */
    static final int MAX_BYTES = 72;

    /**
     * Passwords that are common enough to be tried first by an attacker, compared after lower-casing and removing
     * spaces, hyphens and underscores. A short list on purpose: it catches the obvious ones without pretending to be
     * a breach database.
     */
    private static final Set<String> COMMON = Set.of(
            "password", "password1", "password12", "password123", "password1234", "passw0rd123", "p@ssword123",
            "1234567890", "12345678910", "123456789012", "0123456789", "qwertyuiop", "qwerty12345", "qwerty123456",
            "1q2w3e4r5t", "iloveyou123", "letmein1234", "welcome123", "welcome1234", "admin12345", "administrator",
            "changeme123", "changemenow", "abcdefghij", "abcd123456", "asdfghjkl1", "zxcvbnm123",
            "ausan12345", "ausanfarm1", "ausanpoultry", "poultry123", "poultryfarm");

    /** Private: this class only has static methods. */
    private PasswordPolicy() {}

    /**
     * Checks a password and stops at the first rule it breaks.
     *
     * @param password the plain candidate password
     * @param username the login name of the account it is for, so it cannot be used as its own password
     * @throws RuleViolation (422) naming the broken rule, never echoing the password
     */
    static void check(String password, String username) {
        if (password == null || password.isBlank()) throw new RuleViolation("Password cannot be blank");
        if (password.codePointCount(0, password.length()) < MIN_LENGTH)
            throw new RuleViolation("Password must be at least " + MIN_LENGTH + " characters");
        // Bytes, not characters: accented letters and emoji take 2 to 4 bytes each.
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new RuleViolation("Password is too long (at most " + MAX_BYTES + " bytes; non-English characters count as several)");
        if (password.codePoints().distinct().count() == 1)
            throw new RuleViolation("Password cannot be one repeated character");
        if (username != null && password.equalsIgnoreCase(username.strip()))
            throw new RuleViolation("Password cannot be the same as the username");
        String normalised = password.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        if (COMMON.contains(normalised)) throw new RuleViolation("That password is too common; choose another");
    }
}
