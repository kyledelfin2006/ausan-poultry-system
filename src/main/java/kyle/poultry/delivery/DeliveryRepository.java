package kyle.poultry.delivery;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@link Delivery}. Listing and counting go through {@link JpaSpecificationExecutor} so that any
 * combination of the optional filters (see {@link DeliveryFilter}) is one query. Package-private: only this module's
 * services use it.
 */
interface DeliveryRepository extends JpaRepository<Delivery, Long>, JpaSpecificationExecutor<Delivery> {

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
     * Takes the next number from the database sequence {@code delivery_code_seq}. A sequence never hands out the same
     * number twice, even to concurrent transactions (numbers of rolled-back transactions are simply skipped).
     *
     * @return the next code number
     */
    @Query(nativeQuery = true, value = "select nextval('delivery_code_seq')")
    long nextCodeNumber();
}
