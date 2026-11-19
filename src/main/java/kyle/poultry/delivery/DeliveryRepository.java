package kyle.poultry.delivery;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    /** Row lock: a concurrent "done" and "cancel" on the same delivery are decided one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.id = :id")
    Optional<Delivery> lockById(@Param("id") long id);

    Page<Delivery> findByStatusOrderByStartedAtDescIdDesc(DeliveryStatus status, Pageable page);

    Page<Delivery> findAllByOrderByStartedAtDescIdDesc(Pageable page);

    @Query(nativeQuery = true, value = "select nextval('delivery_code_seq')")
    long nextCodeNumber();
}
