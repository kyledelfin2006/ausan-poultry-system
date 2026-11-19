package kyle.poultry.delivery;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@link Delivery}. Package-private: only {@link DeliveryService} uses it.
 */
interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    /**
     * Loads a delivery and takes a row lock ({@code select ... for update}) held until the transaction ends, so a
     * concurrent "done" and "cancel" on the same delivery are decided one after the other.
     *
     * @param id the delivery id
     * @return the locked delivery, or empty when it does not exist
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.id = :id")
    Optional<Delivery> lockById(@Param("id") long id);

    /**
     * Lists deliveries with the given status, most recently started first (ties broken by id).
     *
     * @param status the status to keep
     * @param page   page and size
     * @return one page of matching deliveries
     */
    Page<Delivery> findByStatusOrderByStartedAtDescIdDesc(DeliveryStatus status, Pageable page);

    /**
     * Lists all deliveries, most recently started first (ties broken by id).
     *
     * @param page page and size
     * @return one page of deliveries
     */
    Page<Delivery> findAllByOrderByStartedAtDescIdDesc(Pageable page);

    /**
     * Takes the next number from the database sequence {@code delivery_code_seq}. A sequence never hands out the same
     * number twice, even to concurrent transactions (numbers of rolled-back transactions are simply skipped).
     *
     * @return the next code number
     */
    @Query(nativeQuery = true, value = "select nextval('delivery_code_seq')")
    long nextCodeNumber();
}
