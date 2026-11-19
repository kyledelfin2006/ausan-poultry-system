package kyle.poultry.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('OWNER')")
class UserController {
    private final UserService users;

    UserController(UserService users) { this.users = users; }

    record CreateUserRequest(@NotBlank @Size(max = 50) String username,
                             @NotBlank @Size(min = 10, max = 72) String password,
                             @NotBlank @Size(max = 100) String displayName,
                             @NotNull Role role) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UserView create(@Valid @RequestBody CreateUserRequest r) {
        return users.create(r.username(), r.password(), r.displayName(), r.role());
    }
}
