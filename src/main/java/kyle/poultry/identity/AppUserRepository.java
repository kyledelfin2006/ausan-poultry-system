package kyle.poultry.identity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
}
