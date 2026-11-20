package kyle.poultry.identity;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
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
 * HTTP endpoints under {@code /api/auth}: login (public), and for any logged-in user: who am I, log out, and change
 * one's own password. Package-private; reached only over HTTP. Login is protected by {@link LoginThrottle} and counted
 * in the {@code ausan.auth.logins} metric (tag {@code outcome}: success, failure or throttled).
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AuthService auth;
    private final LoginThrottle throttle;
    private final UserService users;
    private final MeterRegistry metrics;

    /**
     * Creates the controller.
     *
     * @param auth     verifies credentials and issues tokens
     * @param throttle limits repeated failed logins
     * @param users    changes passwords, signs users out
     * @param metrics  registry for the login and logout counters
     */
    AuthController(AuthService auth, LoginThrottle throttle, UserService users, MeterRegistry metrics) {
        this.auth = auth; this.throttle = throttle; this.users = users; this.metrics = metrics;
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
            metrics.counter("ausan.auth.logins", "outcome", "throttled").increment();
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                            "Too many failed login attempts; try again later"));
        }
        try {
            var response = auth.login(r.username(), r.password());
            // A success clears the failure counter for this key.
            throttle.recordSuccess(key);
            metrics.counter("ausan.auth.logins", "outcome", "success").increment();
            return ResponseEntity.ok(response);
        } catch (AuthenticationException e) {
            // Every kind of failure counts, including unknown usernames, so existence is not revealed.
            throttle.recordFailure(key);
            metrics.counter("ausan.auth.logins", "outcome", "failure").increment();
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }

    /**
     * Tells the UI who is logged in, from the database (so a role change or deactivation shows up at once).
     *
     * @param jwt the authenticated principal
     * @return the caller's profile and when their token expires
     * @throws kyle.poultry.shared.NotFoundException (404) if the user was removed since the token check (not expected)
     */
    @GetMapping("/me")
    MeView me(@AuthenticationPrincipal Jwt jwt) {
        UserView u = users.get(Actor.from(jwt).userId());
        Instant expires = jwt.getExpiresAt();
        return new MeView(u.id(), u.username(), u.displayName(), u.role(), expires);
    }

    /**
     * Logs the caller out of every device: all tokens issued to them so far stop working at once, including the one
     * used for this call. Returns 204 on success; calling it again with the same token answers 401.
     *
     * @param jwt the authenticated principal
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal Jwt jwt) {
        users.signOut(Actor.from(jwt).userId());
        metrics.counter("ausan.auth.logouts").increment();
    }

    /**
     * Lets the logged-in user change their own password. All their tokens stop working afterwards, so the UI must
     * send them back to the login screen.
     *
     * <p>A wrong current password answers 422, not 401: the token is valid, and clients treat 401 as "session expired".
     *
     * @param r   the current and new passwords
     * @param jwt the authenticated principal
     * @throws kyle.poultry.shared.RuleViolation (422) when the current password is incorrect, the new one equals the
     *                                                  current one, or it breaks the password policy
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@Valid @RequestBody ChangePasswordRequest r, @AuthenticationPrincipal Jwt jwt) {
        users.changeOwnPassword(Actor.from(jwt).userId(), r.currentPassword(), r.newPassword());
    }
}
