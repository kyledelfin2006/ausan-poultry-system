package kyle.poultry.identity;

import java.time.Clock;
import java.util.List;
import kyle.poultry.audit.api.model.AuditAction;
import kyle.poultry.audit.api.AuditApi;
import kyle.poultry.audit.api.model.AuditChanges;
import kyle.poultry.audit.api.model.AuditEntity;
import kyle.poultry.shared.ConflictException;
import kyle.poultry.shared.NotFoundException;
import kyle.poultry.shared.RuleViolation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration rules: create, list, update and password changes. Package-private; used by
 * {@link UserController}, {@link AuthController} and {@link OwnerBootstrap}. Each public method is one transaction.
 */
@Service
class UserService {
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final AuditApi audit;

    /**
     * Creates the service.
     *
     * @param users   user persistence
     * @param encoder BCrypt password encoder
     * @param clock   source of "now"
     * @param audit   append-only trail of who changed what
     */
    UserService(AppUserRepository users, PasswordEncoder encoder, Clock clock, AuditApi audit) {
        this.users = users; this.encoder = encoder; this.clock = clock; this.audit = audit;
    }

    /**
     * Creates an active user with a hashed password.
     *
     * @param rawUsername login name; stored trimmed and in lower case, and unique ignoring case
     * @param password    plain initial password (hashed here, never stored or logged)
     * @param displayName name shown in the UI
     * @param role        authorization level
     * @return the created user
     * @throws ConflictException when the username is already taken
     * @throws RuleViolation     when the password breaks the {@link PasswordPolicy}
     */
    @Transactional
    UserView create(String rawUsername, String password, String displayName, Role role) {
        return create(rawUsername, password, displayName, role, false);
    }

    /**
     * Creates a user and optionally requires a password change before other API access.
     *
     * @param rawUsername login name
     * @param password    plain initial password
     * @param displayName name shown in the UI
     * @param role        authorization level
     * @param mustChangePassword whether this is a temporary password
     * @return the created user
     */
    @Transactional
    UserView create(String rawUsername, String password, String displayName, Role role, boolean mustChangePassword) {
        // One canonical form (trimmed, lower case) so "Owner", "owner" and " owner " cannot be three different people.
        String username = rawUsername.strip().toLowerCase(java.util.Locale.ROOT);
        PasswordPolicy.check(password, username);
        if (users.existsByUsernameIgnoreCase(username)) throw new ConflictException("Username already taken");
        // Only the BCrypt hash is persisted.
        AppUser u;
        try {
            u = users.saveAndFlush(new AppUser(username, encoder.encode(password), displayName, role, clock.instant(),
                    mustChangePassword));
        } catch (DataIntegrityViolationException e) {
            if (isUsernameConstraint(e)) throw new ConflictException("Username already taken");
            throw e;
        }
        // The summary names the account and role but, like every audit text, never any password material.
        audit.record(AuditAction.CREATED, AuditEntity.USER, u.id(), "username: " + username + "; role: " + role);
        return view(u);
    }

    /**
     * Lists all users.
     *
     * @return every user, oldest first
     */
    @Transactional(readOnly = true)
    List<UserView> list() {
        return users.findAllByOrderByIdAsc().stream().map(UserService::view).toList();
    }

    /**
     * Changes a user's display name, role and/or active flag. Null arguments are left unchanged.
     *
     * @param id          the user to change
     * @param displayName new display name, or null
     * @param role        new role, or null
     * @param active      new active flag, or null
     * @param actor       the owner making the change
     * @return the updated user
     * @throws NotFoundException when no user has that id
     * @throws RuleViolation     when the new display name is blank, when the actor would deactivate themselves, or
     *                           when the change would leave the system without an active owner
     */
    @Transactional
    UserView update(long id, String displayName, Role role, Boolean active, Actor actor) {
        // Lock all active owners first (before reading the target) so concurrent changes are decided one at a time.
        var owners = users.lockActiveOwners();
        var u = users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        if (displayName != null && displayName.isBlank()) throw new RuleViolation("Display name cannot be blank");

        boolean newActive = active == null ? u.active() : active;
        Role newRole = role == null ? u.role() : role;
        // Refuse self-deactivation so an owner cannot lock themselves out by mistake.
        if (u.id() == actor.userId() && u.active() && !newActive)
            throw new RuleViolation("You cannot deactivate your own account");
        boolean isActiveOwner = u.active() && u.role() == Role.OWNER;
        boolean staysActiveOwner = newActive && newRole == Role.OWNER;
        // The system must always keep at least one active owner, otherwise nobody could administer users.
        if (isActiveOwner && !staysActiveOwner && owners.size() <= 1)
            throw new RuleViolation("The last active owner cannot be deactivated or demoted");

        var changes = AuditChanges.of();
        if (displayName != null) {
            changes.field("displayName", u.displayName(), displayName.strip());
            u.rename(displayName.strip());
        }
        changes.field("role", u.role(), newRole).field("active", u.active(), newActive);
        u.changeRole(newRole);
        // Deactivation ends the user's sessions; otherwise reactivating inside the token lifetime would revive them.
        if (u.active() && !newActive) u.revokeTokens();
        u.setActive(newActive);
        // A PATCH that changed nothing leaves no trace, so the trail only shows real changes.
        if (!changes.isEmpty()) audit.record(AuditAction.UPDATED, AuditEntity.USER, u.id(), changes.summary());
        return view(u);
    }

    /**
     * Replaces a user's password without needing the old one (owner reset).
     *
     * @param id          the user whose password changes
     * @param newPassword plain new password (hashed here, never stored or logged)
     * @throws NotFoundException when no user has that id
     * @throws RuleViolation     when the password breaks the {@link PasswordPolicy}
     */
    @Transactional
    void resetPassword(long id, String newPassword) {
        var u = users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        PasswordPolicy.check(newPassword, u.username());
        u.setPasswordHash(encoder.encode(newPassword));
        u.requirePasswordChange(true);
        // A reset is usually done because the old password may be known to someone else, so end that user's sessions.
        u.revokeTokens();
        audit.record(AuditAction.PASSWORD_RESET, AuditEntity.USER, u.id(), "password reset for " + u.username());
    }

    /**
     * Changes the caller's own password after verifying the current one.
     *
     * <p>A wrong current password is a business-rule failure (422), not 401: the caller's token is valid.
     *
     * @param userId          the caller's id
     * @param currentPassword the caller's existing password
     * @param newPassword     the replacement
     * @throws NotFoundException when the user no longer exists
     * @throws RuleViolation     when the current password does not match the stored hash, when the new one is the
     *                           same as the current one, or when it breaks the {@link PasswordPolicy}
     */
    @Transactional
    void changeOwnPassword(long userId, String currentPassword, String newPassword) {
        var u = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        // Requiring the current password stops a stolen token from silently taking over the account.
        if (!encoder.matches(currentPassword, u.passwordHash())) throw new RuleViolation("Current password is incorrect");
        if (currentPassword.equals(newPassword)) throw new RuleViolation("The new password must differ from the current one");
        PasswordPolicy.check(newPassword, u.username());
        u.setPasswordHash(encoder.encode(newPassword));
        u.requirePasswordChange(false);
        // Every existing token (including the one used for this call) stops working: the user logs in again.
        u.revokeTokens();
        audit.record(AuditAction.PASSWORD_CHANGED, AuditEntity.USER, u.id(), "password changed by the user");
    }

    /**
     * Signs the user out everywhere: every token issued to them so far is refused from now on.
     *
     * @param userId the caller's id
     * @throws NotFoundException when the user no longer exists
     */
    @Transactional
    void signOut(long userId) {
        var u = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        u.revokeTokens();
        audit.record(AuditAction.SIGNED_OUT, AuditEntity.USER, u.id(), "signed out of all sessions");
    }

    /**
     * Reads the caller's current profile.
     *
     * @param userId the caller's id
     * @return the user's view
     * @throws NotFoundException when the user no longer exists
     */
    @Transactional(readOnly = true)
    UserView get(long userId) {
        return view(users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId)));
    }

    /** {@return how many users exist (used to decide whether to bootstrap the first owner)} */
    long count() { return users.count(); }

    private static boolean isUsernameConstraint(DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && "app_user_username_key".equals(violation.getConstraintName())) return true;
        return false;
    }

    /**
     * Maps an entity to its API view, leaving out the password hash.
     *
     * @param u the user entity
     * @return the safe view
     */
    private static UserView view(AppUser u) {
        return new UserView(u.id(), u.username(), u.displayName(), u.role(), u.active(), u.mustChangePassword());
    }
}
