package kyle.poultry.finance;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface FinancialEntryRepository extends JpaRepository<FinancialEntry, Long> {

    List<FinancialEntry> findBySourceTypeAndSourceIdAndTypeIn(String sourceType, long sourceId, List<EntryType> types);

    /** Locks the payment rows so two concurrent refunds for the same delivery are checked one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<FinancialEntry> findBySourceTypeAndSourceIdAndType(String sourceType, long sourceId, EntryType type);

    boolean existsByReversesId(long reversesId);

    @Query("""
            select coalesce(sum(e.amount), 0) from FinancialEntry e
             where e.sourceType = :sourceType and e.sourceId = :sourceId and e.type = :type""")
    java.math.BigDecimal sum(@Param("sourceType") String sourceType, @Param("sourceId") long sourceId,
                             @Param("type") EntryType type);

    @Query("""
            select e from FinancialEntry e
             where e.occurredOn between :from and :to
             order by e.occurredOn desc, e.id desc""")
    Page<FinancialEntry> period(@Param("from") LocalDate from, @Param("to") LocalDate to, Pageable page);

    @Query("""
            select e from FinancialEntry e
             where e.occurredOn between :from and :to and e.type = :type
             order by e.occurredOn desc, e.id desc""")
    Page<FinancialEntry> periodByType(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                      @Param("type") EntryType type, Pageable page);
}
