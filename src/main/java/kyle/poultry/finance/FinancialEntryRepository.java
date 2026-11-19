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

    /**
     * Adds up the amounts of all entries dated inside the inclusive period, one row per entry type that has entries.
     * Each row is {@code [EntryType, BigDecimal]}; types with no entries do not appear.
     */
    @Query("""
            select e.type, sum(e.amount) from FinancialEntry e
             where e.occurredOn between :from and :to
             group by e.type""")
    List<Object[]> totalsByType(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Net cash per payment method for the inclusive period. Each row is {@code [PaymentMethod, BigDecimal]}: cash-in
     * entries (direction equal to {@code in}) add, every other cash entry subtracts. Entries without a method
     * (sales, costs and their reversals move no cash) are excluded.
     */
    @Query("""
            select e.method, sum(case when e.cashDirection = :in then e.amount else -e.amount end)
              from FinancialEntry e
             where e.occurredOn between :from and :to and e.method is not null
             group by e.method""")
    List<Object[]> cashByMethod(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                @Param("in") EntryType.CashDirection in);

    /**
     * Money still held for cancelled sources as of a date: payments minus refunds, counting only entries dated on or
     * before {@code asOf}, and only for sources that have a sale reversal dated on or before {@code asOf}. The
     * {@code exists} sub-query is the "this source was cancelled" test; the type arguments are passed in so the query
     * does not depend on enum literals. Returns 0 when no source qualifies.
     */
    @Query("""
            select coalesce(sum(case when e.type = :paid then e.amount else -e.amount end), 0)
              from FinancialEntry e
             where e.type in (:paid, :refund) and e.occurredOn <= :asOf
               and exists (select 1 from FinancialEntry r
                            where r.type = :reversal and r.occurredOn <= :asOf
                              and r.sourceType = e.sourceType and r.sourceId = e.sourceId)""")
    java.math.BigDecimal retainedOnCancelled(@Param("asOf") LocalDate asOf, @Param("paid") EntryType paid,
                                             @Param("refund") EntryType refund, @Param("reversal") EntryType reversal);
}
