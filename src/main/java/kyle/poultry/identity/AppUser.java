package kyle.poultry.identity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A person who can log in. JPA entity for table {@code app_user}; package-private so other modules never touch it
 * and only {@link UserService} and {@link AuthService} change it. The password is stored only as a BCrypt hash.
 */
@Entity
@Table(name = "app_user")
class AppUser {
    /** Database-generated primary key. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** Unique login name. */
    @Column(nullable = false, unique = true) private String username;
    /** BCrypt hash of the password; never returned by the API or logged. */
    @Column(nullable = false) private String passwordHash;
    /** Name shown in the UI. */
    @Column(nullable = false) private String displayName;
    /** Authorization level. */
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Role role;
    /** False once deactivated; an inactive user can neither log in nor use an existing token. */
    private boolean active = true;
    /** True while a temporary or bootstrap password still needs to be replaced by this user. */
    @Column(name = "must_change_password", nullable = false) private boolean mustChangePassword;
    /** When the account was created. */
    @Column(nullable = false) private Instant createdAt;
    /**
     * Counter copied into every token at login (claim {@code ver}). Raising it makes all older tokens of this user
     * invalid at once: that is how logout and password changes end sessions.
     */
    @Column(nullable = false) private int tokenVersion;

    /** Required by JPA; not for application code. */
    protected AppUser() {}

    /**
     * Creates a new, active user.
     *
     * @param username     unique login name
     * @param passwordHash BCrypt hash (never the plain password)
     * @param displayName  name shown in the UI
     * @param role         authorization level
     * @param now          creation time
     */
    AppUser(String username, String passwordHash, String displayName, Role role, Instant now) {
        this(username, passwordHash, displayName, role, now, false);
    }

    /**
     * Creates a new user whose temporary password may need to be replaced at first login.
     *
     * @param username unique login name
     * @param passwordHash BCrypt hash (never the plain password)
     * @param displayName name shown in the UI
     * @param role authorization level
     * @param now creation time
     * @param mustChangePassword whether this is a temporary password
     */
    AppUser(String username, String passwordHash, String displayName, Role role, Instant now, boolean mustChangePassword) {
        this.username = username; this.passwordHash = passwordHash;
        this.displayName = displayName; this.role = role; this.createdAt = now;
        this.mustChangePassword = mustChangePassword;
    }

    /** {@return the database id} */
    Long id() { return id; }
    /** {@return the login name} */
    String username() { return username; }
    /** {@return the BCrypt password hash} */
    String passwordHash() { return passwordHash; }
    /** {@return the display name} */
    String displayName() { return displayName; }
    /** {@return the role} */
    Role role() { return role; }
    /** {@return whether the account is active} */
    boolean active() { return active; }
    /** {@return whether this user must replace a temporary password before using other API routes} */
    boolean mustChangePassword() { return mustChangePassword; }
    /** {@return the current token version; tokens carrying any other value are refused} */
    int tokenVersion() { return tokenVersion; }

    /** Replaces the display name. */
    void rename(String displayName) { this.displayName = displayName; }
    /** Replaces the role. */
    void changeRole(Role role) { this.role = role; }
    /** Activates or deactivates the account. */
    void setActive(boolean active) { this.active = active; }
    /** Invalidates every token issued so far to this user (sign-out everywhere). */
    void revokeTokens() { this.tokenVersion++; }
    /** Replaces the password hash; the caller must pass a BCrypt hash. */
    void setPasswordHash(String hash) { this.passwordHash = hash; }
    /** Sets whether the user must replace their current password. */
    void requirePasswordChange(boolean required) { this.mustChangePassword = required; }
}
