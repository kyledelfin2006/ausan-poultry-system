package kyle.poultry.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

    Page<EntryView> entries(LocalDate from, LocalDate to, EntryType type, Pageable page);

    record EntryView(long id, EntryType type, CashDirection cashDirection, BigDecimal amount, PaymentMethod method,
                     LocalDate occurredOn, String description, String sourceType, Long sourceId, Long reversesId,
                     long createdBy, Instant createdAt) {}
}
