package kyle.poultry.identity;

import java.time.Clock;
import kyle.poultry.shared.ConflictException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserService {
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;

    UserService(AppUserRepository users, PasswordEncoder encoder, Clock clock) {
        this.users = users; this.encoder = encoder; this.clock = clock;
    }

    @Transactional
    UserView create(String username, String password, String displayName, Role role) {
        if (users.findByUsername(username).isPresent()) throw new ConflictException("Username already taken");
        var u = users.save(new AppUser(username, encoder.encode(password), displayName, role, clock.instant()));
        return new UserView(u.id(), u.username(), u.displayName(), u.role(), u.active());
    }

    long count() { return users.count(); }
}
