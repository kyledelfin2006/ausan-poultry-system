package kyle.poultry.finance;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage and queries for {@link FinancialEntry}, used only inside the finance module. Beyond plain save and
 * lookup it holds the aggregate queries (sums per source, per period, per type and per method) so that totals are
 * computed by the database and never by loading entries into memory.
 */
interface FinancialEntryRepository extends JpaRepository<FinancialEntry, Long> {

    /**
     * Finds a source's entries of the given types, e.g. its sale and cost rows before they are reversed.
     *
     * @param sourceType kind of source record (e.g. DELIVERY)
     * @param sourceId   id of the source record
     * @param types      entry types to include
     * @return matching entries, possibly empty
     */
    List<FinancialEntry> findBySourceTypeAndSourceIdAndTypeIn(String sourceType, long sourceId, List<EntryType> types);

    /**
     * Loads a source's entries of one type and locks them ({@code SELECT ... FOR UPDATE}), so two concurrent
     * refunds for the same delivery are checked one after the other.
     *
     * @param sourceType kind of source record
     * @param sourceId   id of the source record
     * @param type       entry type to lock, normally PAYMENT_IN
     * @return the locked entries, possibly empty
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<FinancialEntry> findBySourceTypeAndSourceIdAndType(String sourceType, long sourceId, EntryType type);

    /**
     * Tells whether an entry has already been cancelled by a reversal row.
     *
     * @param reversesId id of the original entry
     * @return true if a reversal pointing at it exists
     */
    boolean existsByReversesId(long reversesId);

    /**
     * Adds up the amounts of one entry type for one source; returns 0 when there are none.
     *
     * @param sourceType kind of source record
     * @param sourceId   id of the source record
     * @param type       entry type to add up
     * @return the total, never null
     */
    @Query("""
            select coalesce(sum(e.amount), 0) from FinancialEntry e
             where e.sourceType = :sourceType and e.sourceId = :sourceId and e.type = :type""")
    java.math.BigDecimal sum(@Param("sourceType") String sourceType, @Param("sourceId") long sourceId,
                             @Param("type") EntryType type);

    /** Groups retained payment totals by source for a page of cancelled deliveries. */
    @Query("""
            select e.sourceId, sum(case when e.type = :paid then e.amount else -e.amount end)
              from FinancialEntry e
             where e.sourceType = :sourceType and e.sourceId in :sourceIds and e.type in (:paid, :refund)
             group by e.sourceId""")
    List<Object[]> retainedPaymentsBySources(@Param("sourceType") String sourceType,
                                             @Param("sourceIds") List<Long> sourceIds,
                                             @Param("paid") EntryType paid, @Param("refund") EntryType refund);

    /**
     * Pages through all entries dated inside the inclusive period, newest date first and, within a day, newest id
     * first (so the order is stable).
     *
     * @param from first business date included
     * @param to   last business date included
     * @param page paging and size
     * @return one page of entries
     */
    @Query("""
            select e from FinancialEntry e
             where e.occurredOn between :from and :to
             order by e.occurredOn desc, e.id desc""")
    Page<FinancialEntry> period(@Param("from") LocalDate from, @Param("to") LocalDate to, Pageable page);

    /**
     * Same as {@link #period} but only entries of one type.
     *
     * @param from first business date included
     * @param to   last business date included
     * @param type entry type to keep
     * @param page paging and size
     * @return one page of entries
     */
    @Query("""
            select e from FinancialEntry e
             where e.occurredOn between :from and :to and e.type = :type
             order by e.occurredOn desc, e.id desc""")
    Page<FinancialEntry> periodByType(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                      @Param("type") EntryType type, Pageable page);

    /** Same as {@link #period}, restricted to the entry types visible to an assistant. */
    @Query("""
            select e from FinancialEntry e
             where e.occurredOn between :from and :to and e.type in :types
             order by e.occurredOn desc, e.id desc""")
    Page<FinancialEntry> periodByTypes(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                       @Param("types") List<EntryType> types, Pageable page);

    /**
     * Same rows and order as {@link #period}, delivered as a database cursor for exports. The caller must be inside a
     * transaction, consume and close the stream there, and detach what it has read.
     */
    @QueryHints(@QueryHint(name = "org.hibernate.fetchSize", value = "500"))
    @Query("""
            select e from FinancialEntry e
             where e.occurredOn between :from and :to
             order by e.occurredOn desc, e.id desc""")
    Stream<FinancialEntry> streamPeriod(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Cursor version of {@link #periodByType}; see {@link #streamPeriod}. */
    @QueryHints(@QueryHint(name = "org.hibernate.fetchSize", value = "500"))
    @Query("""
            select e from FinancialEntry e
             where e.occurredOn between :from and :to and e.type = :type
             order by e.occurredOn desc, e.id desc""")
    Stream<FinancialEntry> streamPeriodByType(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                              @Param("type") EntryType type);

    /** Cursor version of {@link #periodByTypes}; see {@link #streamPeriod}. */
    @QueryHints(@QueryHint(name = "org.hibernate.fetchSize", value = "500"))
    @Query("""
            select e from FinancialEntry e
             where e.occurredOn between :from and :to and e.type in :types
             order by e.occurredOn desc, e.id desc""")
    Stream<FinancialEntry> streamPeriodByTypes(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                               @Param("types") List<EntryType> types);

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
     * Adds up the amounts per business date and entry type inside the inclusive period. Each row is
     * {@code [LocalDate, EntryType, BigDecimal]}; combinations with no entries do not appear. Feeds the report
     * time series, which groups the days into days or months in memory (at most 366 days x 8 types).
     */
    @Query("""
            select e.occurredOn, e.type, sum(e.amount) from FinancialEntry e
             where e.occurredOn between :from and :to
             group by e.occurredOn, e.type""")
    List<Object[]> dailyTotalsByType(@Param("from") LocalDate from, @Param("to") LocalDate to);

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
