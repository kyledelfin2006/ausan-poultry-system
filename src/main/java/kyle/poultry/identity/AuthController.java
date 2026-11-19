package kyle.poultry.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * HTTP endpoints under {@code /api/auth}: login (public) and changing one's own password (any logged-in user).
 * Package-private; reached only over HTTP. Login is protected by {@link LoginThrottle}.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AuthService auth;
    private final LoginThrottle throttle;
    private final UserService users;

    /**
     * Creates the controller.
     *
     * @param auth     verifies credentials and issues tokens
     * @param throttle limits repeated failed logins
     * @param users    changes passwords
     */
    AuthController(AuthService auth, LoginThrottle throttle, UserService users) {
        this.auth = auth; this.throttle = throttle; this.users = users;
    }

    /**
     * Body of {@code POST /api/auth/login}.
     *
     * @param username the login name
     * @param password the plain password; only ever compared against the stored hash
     */
    record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    /**
     * Body of {@code POST /api/auth/password}.
     *
     * @param currentPassword the caller's existing password, required to prove it is really them
     * @param newPassword     the replacement, 10 to 72 characters (72 is BCrypt's input limit)
     */
    record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 10, max = 72) String newPassword) {}

    /**
     * Logs a user in and returns a token.
     *
     * <p>Unknown user, wrong password and inactive user are indistinguishable (all 401) so usernames cannot be probed.
     *
     * @param r    the credentials
     * @param http the request, used for the caller's address (part of the throttle key)
     * @return 200 with the token, 401 for bad credentials, or 429 with {@code Retry-After} while throttled
     */
    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest r, HttpServletRequest http) {
        // Throttle key is username (case-insensitive) + address, so one attacker cannot lock out everyone's account.
        String key = LoginThrottle.key(r.username(), http.getRemoteAddr());
        Duration wait = throttle.blockedFor(key);
        if (wait != null) {
            // Checked before verifying the password, so a blocked client learns nothing about correctness.
            long seconds = Math.max(1, wait.toSeconds() + (wait.toNanosPart() > 0 ? 1 : 0));   // round up
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                            "Too many failed login attempts; try again later"));
        }
        try {
            var response = auth.login(r.username(), r.password());
            // A success clears the failure counter for this key.
            throttle.recordSuccess(key);
            return ResponseEntity.ok(response);
        } catch (AuthenticationException e) {
            // Every kind of failure counts, including unknown usernames, so existence is not revealed.
            throttle.recordFailure(key);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }

    /**
     * Lets the logged-in user change their own password.
     *
     * <p>A wrong current password answers 422, not 401: the token is valid, and clients treat 401 as "session expired".
     *
     * @param r   the current and new passwords
     * @param jwt the authenticated principal
     * @throws kyle.poultry.shared.RuleViolation (422) when the current password is incorrect
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@Valid @RequestBody ChangePasswordRequest r, @AuthenticationPrincipal Jwt jwt) {
        users.changeOwnPassword(Actor.from(jwt).userId(), r.currentPassword(), r.newPassword());
    }
}
