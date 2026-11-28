package kyle.poultry.identity.internal.persistence;

import kyle.poultry.identity.api.model.Role;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data access to {@link AppUser}. Internal to the identity module: only the identity module reads or writes users, and other
 * modules go through {@code Actor} instead.
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * Finds every user whose login name matches ignoring case. Normally one; old data may hold look-alike names.
     *
     * @param username the login name
     * @return the matching users, lowest id first
     */
    List<AppUser> findAllByUsernameIgnoreCaseOrderByIdAsc(String username);

    /**
     * Tells whether any account already uses this login name, ignoring case.
     *
     * @param username the login name
     * @return true when the name is taken
     */
    boolean existsByUsernameIgnoreCase(String username);

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
    @Query("select u from AppUser u where u.role = kyle.poultry.identity.api.model.Role.OWNER and u.active = true order by u.id")
    List<AppUser> lockActiveOwners();
}
