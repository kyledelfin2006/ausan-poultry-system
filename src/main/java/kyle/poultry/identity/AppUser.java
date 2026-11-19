package kyle.poultry.identity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "app_user")
class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String username;
    @Column(nullable = false) private String passwordHash;
    @Column(nullable = false) private String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Role role;
    private boolean active = true;
    @Column(nullable = false) private Instant createdAt;

    protected AppUser() {}

    AppUser(String username, String passwordHash, String displayName, Role role, Instant now) {
        this.username = username; this.passwordHash = passwordHash;
        this.displayName = displayName; this.role = role; this.createdAt = now;
    }

    Long id() { return id; }
    String username() { return username; }
    String passwordHash() { return passwordHash; }
    String displayName() { return displayName; }
    Role role() { return role; }
    boolean active() { return active; }
}
