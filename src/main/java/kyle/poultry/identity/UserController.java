package kyle.poultry.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP endpoints under {@code /api/users} for user administration. Every endpoint is restricted to the OWNER role.
 * Package-private; reached only over HTTP.
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('OWNER')")
class UserController {
    private final UserService users;

    /**
     * Creates the controller.
     *
     * @param users the user administration service
     */
    UserController(UserService users) { this.users = users; }

    /**
     * Body of {@code POST /api/users}.
     *
     * @param username    unique login name, up to 50 characters
     * @param password    initial password, 10 to 72 characters (72 is BCrypt's input limit)
     * @param displayName name shown in the UI, up to 100 characters
     * @param role        authorization level
     */
    record CreateUserRequest(@NotBlank @Size(max = 50) String username,
                             @NotBlank @Size(min = 10, max = 72) String password,
                             @NotBlank @Size(max = 100) String displayName,
                             @NotNull Role role) {}

    /**
     * Creates a user.
     *
     * @param r the new user's details
     * @return the created user (201)
     * @throws kyle.poultry.shared.ConflictException (409) when the username is already taken
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UserView create(@Valid @RequestBody CreateUserRequest r) {
        return users.create(r.username(), r.password(), r.displayName(), r.role());
    }

    /**
     * Body of {@code PATCH /api/users/{id}}; a null field means "leave unchanged".
     *
     * @param displayName new display name, up to 100 characters, or null
     * @param role        new role, or null
     * @param active      new active flag, or null
     */
    record UpdateUserRequest(@Size(max = 100) String displayName, Role role, Boolean active) {}

    /**
     * Body of {@code POST /api/users/{id}/password}.
     *
     * @param newPassword the replacement password, 10 to 72 characters
     */
    record ResetPasswordRequest(@NotBlank @Size(min = 10, max = 72) String newPassword) {}

    /**
     * Lists all users.
     *
     * @return every user, oldest first, without password hashes
     */
    @GetMapping
    List<UserView> list() { return users.list(); }

    /**
     * Updates a user's display name, role or active flag.
     *
     * @param id  the user to change
     * @param r   the fields to change
     * @param jwt the authenticated owner making the change
     * @return the updated user
     * @throws kyle.poultry.shared.NotFoundException (404) when the user does not exist
     * @throws kyle.poultry.shared.RuleViolation (422) when it would deactivate oneself, remove the last active owner,
     *                                                  or blank the display name
     */
    @PatchMapping("/{id}")
    UserView update(@PathVariable long id, @Valid @RequestBody UpdateUserRequest r, @AuthenticationPrincipal Jwt jwt) {
        return users.update(id, r.displayName(), r.role(), r.active(), Actor.from(jwt));
    }

    /**
     * Resets another user's password without needing the old one (owner privilege).
     *
     * @param id the user whose password is replaced
     * @param r  the new password
     * @throws kyle.poultry.shared.NotFoundException (404) when the user does not exist
     */
    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@PathVariable long id, @Valid @RequestBody ResetPasswordRequest r) {
        users.resetPassword(id, r.newPassword());
    }
}
