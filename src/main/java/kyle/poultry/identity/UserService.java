package kyle.poultry.identity;

import java.time.Clock;
import java.util.List;
import kyle.poultry.shared.ConflictException;
import kyle.poultry.shared.NotFoundException;
import kyle.poultry.shared.RuleViolation;
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

    /**
     * Creates the service.
     *
     * @param users   user persistence
     * @param encoder BCrypt password encoder
     * @param clock   source of "now"
     */
    UserService(AppUserRepository users, PasswordEncoder encoder, Clock clock) {
        this.users = users; this.encoder = encoder; this.clock = clock;
    }

    /**
     * Creates an active user with a hashed password.
     *
     * @param username    unique login name
     * @param password    plain initial password (hashed here, never stored or logged)
     * @param displayName name shown in the UI
     * @param role        authorization level
     * @return the created user
     * @throws ConflictException when the username is already taken
     */
    @Transactional
    UserView create(String username, String password, String displayName, Role role) {
        if (users.findByUsername(username).isPresent()) throw new ConflictException("Username already taken");
        // Only the BCrypt hash is persisted.
        var u = users.save(new AppUser(username, encoder.encode(password), displayName, role, clock.instant()));
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

        if (displayName != null) u.rename(displayName.strip());
        u.changeRole(newRole);
        u.setActive(newActive);
        return view(u);
    }

    /**
     * Replaces a user's password without needing the old one (owner reset).
     *
     * @param id          the user whose password changes
     * @param newPassword plain new password (hashed here, never stored or logged)
     * @throws NotFoundException when no user has that id
     */
    @Transactional
    void resetPassword(long id, String newPassword) {
        var u = users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        u.setPasswordHash(encoder.encode(newPassword));
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
     * @throws RuleViolation     when the current password does not match the stored hash
     */
    @Transactional
    void changeOwnPassword(long userId, String currentPassword, String newPassword) {
        var u = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        // Requiring the current password stops a stolen token from silently taking over the account.
        if (!encoder.matches(currentPassword, u.passwordHash())) throw new RuleViolation("Current password is incorrect");
        u.setPasswordHash(encoder.encode(newPassword));
    }

    /** {@return how many users exist (used to decide whether to bootstrap the first owner)} */
    long count() { return users.count(); }

    /**
     * Maps an entity to its API view, leaving out the password hash.
     *
     * @param u the user entity
     * @return the safe view
     */
    private static UserView view(AppUser u) {
        return new UserView(u.id(), u.username(), u.displayName(), u.role(), u.active());
    }
}
