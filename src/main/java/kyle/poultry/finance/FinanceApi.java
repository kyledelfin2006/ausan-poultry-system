package kyle.poultry.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kyle.poultry.finance.EntryType.CashDirection;
import kyle.poultry.identity.Actor;
import kyle.poultry.shared.reference.SourceRef;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Public face of the finance module: an append-only ledger of everything that is sold, paid, spent or refunded.
 * Other modules (stock, delivery, report) use only this interface, never the entity or repository.
 *
 * <p>Every method joins the caller's transaction, so a delivery and its sale/payment/cost commit or fail together.
 * Amounts must be positive with at most 2 decimals (PHP); the entry type, not the sign, gives the direction.
 */
public interface FinanceApi {

    /**
     * Records the cash paid to a supplier for stock.
     *
     * @param amount      cash paid, positive, at most 2 decimals
     * @param method      how it was paid
     * @param on          business date of the payment
     * @param description optional note
     * @param source      the record that caused it (e.g. the stock movement)
     * @param actor       who is recording it
     * @return the stored entry
     * @throws kyle.poultry.shared.error.RuleViolation if the amount is not a positive peso value, the method is missing,
     *         or the business date is outside the actor's allowed range
     */
    EntryView recordStockPurchase(BigDecimal amount, PaymentMethod method, LocalDate on, String description,
                                  SourceRef source, Actor actor);

    /**
     * Records an operating expense (fuel, wages, repairs...).
     *
     * @param amount      cash paid, positive, at most 2 decimals
     * @param method      how it was paid
     * @param on          business date; assistants may backdate at most 7 days, owners may use any past date
     * @param description what the money was for; required
     * @param actor       who is recording it
     * @return the stored entry
     * @throws kyle.poultry.shared.error.RuleViolation if the description is blank, the date is outside the actor's allowed
     *                                           range, or the amount is invalid
     */
    EntryView recordExpense(BigDecimal amount, PaymentMethod method, LocalDate on, String description, Actor actor);

    /**
     * Records revenue for a source (no cash moves; the payment is recorded separately).
     *
     * @param amount sale value, positive, at most 2 decimals
     * @param on     business date of the sale
     * @param source the record that was sold (e.g. the delivery); required
     * @param actor  who is recording it
     * @return the stored entry
     * @throws kyle.poultry.shared.error.RuleViolation if the source is missing or the amount is invalid
     */
    EntryView recordSale(BigDecimal amount, LocalDate on, SourceRef source, Actor actor);

    /**
     * Records the cost of the stock delivered for a source. Zero (e.g. donated stock) records nothing.
     *
     * @param amount cost of goods, at most 2 decimals; zero is ignored
     * @param on     business date of the delivery
     * @param source the record that consumed the stock; required
     * @param actor  who is recording it
     * @throws kyle.poultry.shared.error.RuleViolation if the source is missing or the amount is negative or invalid
     */
    void recordCostOfGoods(BigDecimal amount, LocalDate on, SourceRef source, Actor actor);

    /**
     * Records money received from a customer for a source.
     *
     * @param amount money received, positive, at most 2 decimals
     * @param method how it was received
     * @param on     business date of the payment
     * @param source the record it pays for (e.g. the delivery); required
     * @param actor  who is recording it
     * @return the stored entry
     * @throws kyle.poultry.shared.error.RuleViolation if the source or method is missing, the date is outside the actor's
     *         allowed range, or the amount is invalid
     */
    EntryView recordPayment(BigDecimal amount, PaymentMethod method, LocalDate on, SourceRef source, Actor actor);

    /**
     * Cancels the sale and its cost for the source by appending reversal rows; the originals stay.
     * Payments are untouched (money is only returned by a refund). Safe to call twice.
     *
     * @param source the cancelled record
     * @param on     business date of the cancellation
     * @param actor  who is cancelling
     */
    void reverseSaleAndCost(SourceRef source, LocalDate on, Actor actor);

    /**
     * Money actually handed back. Owner only. Cannot exceed what was received and not yet refunded for the source.
     *
     * @param amount      money returned, positive, at most 2 decimals
     * @param method      how it was returned
     * @param on          business date, not in the future
     * @param description optional note
     * @param source      the record being refunded; required
     * @param actor       who is refunding; must be the owner
     * @return the stored entry
     * @throws kyle.poultry.shared.error.RuleViolation if the amount exceeds the payment still held, the source is missing,
     *                                           or the date is outside the actor's allowed range
     * @throws org.springframework.security.access.AccessDeniedException if the actor is not the owner
     */
    EntryView recordRefund(BigDecimal amount, PaymentMethod method, LocalDate on, String description,
                           SourceRef source, Actor actor);

    /**
     * Payments received minus refunds for the source: money still held for, e.g., a cancelled delivery.
     *
     * @param source the record to look up
     * @return the amount still held, zero if nothing
     */
    BigDecimal retainedPayment(SourceRef source);

    /**
     * Returns retained payment totals for multiple sources in one grouped query.
     *
     * @param sourceType kind of record
     * @param sourceIds  ids to include; an empty list returns an empty map
     * @return retained amount by source id, scale 2
     */
    Map<Long, BigDecimal> retainedPayments(String sourceType, List<Long> sourceIds);

    /**
     * Sum of amounts per entry type for the inclusive business-date period. Every {@link EntryType} is present;
     * types with no entries are 0.00. Aggregated in the database.
     *
     * @param from first business date included
     * @param to   last business date included
     * @return totals keyed by type, scale 2
     * @throws kyle.poultry.shared.error.RuleViolation if a date is missing or {@code to} is before {@code from}
     */
    Map<EntryType, BigDecimal> totalsByType(LocalDate from, LocalDate to);

    /**
     * Sum of amounts per business date and entry type for the inclusive period, aggregated in the database. Only
     * dates that have at least one entry appear (the result is sparse); for those dates every {@link EntryType}
     * is present, with 0.00 for the types that have no entry that day. The map is ordered by date.
     *
     * @param from first business date included
     * @param to   last business date included
     * @return per-date totals keyed by type, scale 2, ordered by date ascending
     * @throws kyle.poultry.shared.error.RuleViolation if a date is missing or {@code to} is before {@code from}
     */
    Map<LocalDate, Map<EntryType, BigDecimal>> dailyTotalsByType(LocalDate from, LocalDate to);

    /**
     * Net cash per payment method for the inclusive period (cash in minus cash out). Every {@link PaymentMethod}
     * is present; unused methods are 0.00. Aggregated in the database.
     *
     * @param from first business date included
     * @param to   last business date included
     * @return net cash keyed by method, scale 2
     * @throws kyle.poultry.shared.error.RuleViolation if a date is missing or {@code to} is before {@code from}
     */
    Map<PaymentMethod, BigDecimal> cashByMethod(LocalDate from, LocalDate to);

    /**
     * Money still held for cancelled sources as of {@code asOf}: for every source that has a SALE_REVERSAL dated on or
     * before {@code asOf}, payments received minus refunds paid, both counted up to and including {@code asOf}
     * (cumulative, not limited to a reporting period). Sources with nothing held contribute nothing.
     *
     * @param asOf the date to measure at
     * @return the total retained amount, scale 2
     * @throws kyle.poultry.shared.error.RuleViolation if {@code asOf} is missing
     */
    BigDecimal retainedOnCancelledSources(LocalDate asOf);

    /**
     * Lists ledger entries for an inclusive period, newest first, optionally of one type. This is the drill-down
     * behind every report figure. Assistants can read only sales, sale reversals, payments, and refunds; asking for
     * a hidden type returns an empty page.
     *
     * @param from first business date included
     * @param to   last business date included
     * @param type only this type, or null for all
     * @param page paging and size
     * @param actor the caller; assistants see sales, sale reversals, payments, and refunds only
     * @return a page of entries
     * @throws kyle.poultry.shared.error.RuleViolation if a date is missing or {@code to} is before {@code from}
     */
    Page<EntryView> entries(LocalDate from, LocalDate to, EntryType type, Pageable page, Actor actor);

    /**
     * Read-only copy of one ledger entry, safe to hand to other modules and to serialise.
     *
     * @param id            entry id
     * @param type          what the entry means
     * @param cashDirection whether cash came in, went out or did not move
     * @param amount        positive PHP amount
     * @param method        payment method; null for entries that move no cash
     * @param occurredOn    business date (Asia/Manila)
     * @param description   optional note
     * @param sourceType    kind of record that caused it (e.g. DELIVERY); null for expenses
     * @param sourceId      id of that record
     * @param reversesId    for reversal entries, the entry being cancelled
     * @param createdBy     id of the user who recorded it
     * @param createdAt     when it was recorded
     */
    record EntryView(long id, EntryType type, CashDirection cashDirection, BigDecimal amount, PaymentMethod method,
                     LocalDate occurredOn, String description, String sourceType, Long sourceId, Long reversesId,
                     long createdBy, Instant createdAt) {}
}
