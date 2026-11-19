package kyle.poultry.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import kyle.poultry.finance.EntryType.CashDirection;
import kyle.poultry.identity.Actor;
import kyle.poultry.shared.SourceRef;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Public face of the finance module: an append-only ledger. Every method joins the caller's transaction,
 * so a delivery and its sale/payment/cost commit or fail together.
 * Amounts must be positive with at most 2 decimals (PHP).
 */
public interface FinanceApi {

    EntryView recordStockPurchase(BigDecimal amount, PaymentMethod method, LocalDate on, String description,
                                  SourceRef source, Actor actor);

    EntryView recordExpense(BigDecimal amount, PaymentMethod method, LocalDate on, String description, Actor actor);

    EntryView recordSale(BigDecimal amount, LocalDate on, SourceRef source, Actor actor);

    /** Cost of the stock delivered. Zero (e.g. donated stock) records nothing. */
    void recordCostOfGoods(BigDecimal amount, LocalDate on, SourceRef source, Actor actor);

    EntryView recordPayment(BigDecimal amount, PaymentMethod method, LocalDate on, SourceRef source, Actor actor);

    /**
     * Cancels the sale and its cost for the source by appending reversal rows; the originals stay.
     * Payments are untouched (money is only returned by a refund). Safe to call twice.
     */
    void reverseSaleAndCost(SourceRef source, LocalDate on, Actor actor);

    /**
     * Money actually handed back. Owner only. Cannot exceed what was received and not yet refunded for the source.
     * @throws kyle.poultry.shared.RuleViolation when it would exceed the retained payment
     */
    EntryView recordRefund(BigDecimal amount, PaymentMethod method, LocalDate on, String description,
                           SourceRef source, Actor actor);

    /** Payments received minus refunds for the source: money still held for, e.g., a cancelled delivery. */
    BigDecimal retainedPayment(SourceRef source);

    /**
     * Sum of amounts per entry type for the inclusive business-date period. Every {@link EntryType} is present;
     * types with no entries are 0.00. Aggregated in the database.
     */
    Map<EntryType, BigDecimal> totalsByType(LocalDate from, LocalDate to);

    /**
     * Net cash per payment method for the inclusive period (cash in minus cash out). Every {@link PaymentMethod}
     * is present; unused methods are 0.00. Aggregated in the database.
     */
    Map<PaymentMethod, BigDecimal> cashByMethod(LocalDate from, LocalDate to);

    /**
     * Money still held for cancelled sources as of {@code asOf}: for every source that has a SALE_REVERSAL dated on or
     * before {@code asOf}, payments received minus refunds paid, both counted up to and including {@code asOf}
     * (cumulative, not limited to a reporting period). Sources with nothing held contribute nothing.
     */
    BigDecimal retainedOnCancelledSources(LocalDate asOf);

    Page<EntryView> entries(LocalDate from, LocalDate to, EntryType type, Pageable page);

    record EntryView(long id, EntryType type, CashDirection cashDirection, BigDecimal amount, PaymentMethod method,
                     LocalDate occurredOn, String description, String sourceType, Long sourceId, Long reversesId,
                     long createdBy, Instant createdAt) {}
}
