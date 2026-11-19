package kyle.poultry.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AuthService auth;

    AuthController(AuthService auth) { this.auth = auth; }

    record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest r) {
        try {
            return auth.login(r.username(), r.password());
        } catch (AuthenticationException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }
}
