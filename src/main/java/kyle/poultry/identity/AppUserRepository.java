package kyle.poultry.identity;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data access to {@link AppUser}. Package-private: only the identity module reads or writes users, and other
 * modules go through {@link Actor} instead.
 */
interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * Finds a user by exact login name.
     *
     * @param username the login name
     * @return the user, or empty when none has that name
     */
    Optional<AppUser> findByUsername(String username);

    /**
     * Lists every user, oldest first.
     *
     * @return all users ordered by id
     */
    List<AppUser> findAllByOrderByIdAsc();

    /**
     * Selects all active owners and takes a row lock ({@code select ... for update}) on each, ordered by id so that
     * concurrent callers lock in the same order. Holding these locks makes the "last active owner" check safe against
     * two simultaneous demotions: the second caller waits and then sees the first one's result.
     *
     * @return the active users whose role is OWNER
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.role = kyle.poultry.identity.Role.OWNER and u.active = true order by u.id")
    List<AppUser> lockActiveOwners();
}
