package kyle.poultry.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kyle.poultry.identity.Actor;
import kyle.poultry.shared.RuleViolation;
import kyle.poultry.shared.SourceRef;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The implementation of {@link FinanceApi}: validates each request, appends immutable ledger rows and answers
 * period aggregates. It is package-private so other modules can only reach it through the interface.
 *
 * <p>The class is transactional: each call joins the caller's transaction (or starts one), which is what makes a
 * delivery and its ledger rows succeed or fail together. Rows are only ever inserted, never updated or deleted.
 */
@Service
@Transactional
class FinanceLedger implements FinanceApi {
    private final FinancialEntryRepository entries;
    private final Clock clock;

    /**
     * Creates the ledger.
     *
     * @param entries storage for ledger rows
     * @param clock   source of "now" and of today's business date (Asia/Manila), replaceable in tests
     */
    FinanceLedger(FinancialEntryRepository entries, Clock clock) { this.entries = entries; this.clock = clock; }

    /** {@inheritDoc} */
    @Override
    public EntryView recordStockPurchase(BigDecimal amount, PaymentMethod method, LocalDate on, String description,
                                         SourceRef source, Actor actor) {
        // Cash out to a supplier; the source (the stock movement) is kept for audit.
        return append(EntryType.STOCK_PURCHASE, amount, method, on, description, source, actor);
    }

    /** {@inheritDoc} */
    @Override
    public EntryView recordExpense(BigDecimal amount, PaymentMethod method, LocalDate on, String description,
                                   Actor actor) {
        // An expense has no source record, so the description is the only explanation and must not be empty.
        if (description == null || description.isBlank()) throw new RuleViolation("An expense needs a description");
        requireNotFuture(on);
        return append(EntryType.EXPENSE, amount, method, on, description, null, actor);
    }

    /** {@inheritDoc} */
    @Override
    public EntryView recordSale(BigDecimal amount, LocalDate on, SourceRef source, Actor actor) {
        // Revenue moves no cash, so no payment method is stored.
        return append(EntryType.SALE, amount, null, on, null, requireSource(source), actor);
    }

    /** {@inheritDoc} */
    @Override
    public void recordCostOfGoods(BigDecimal amount, LocalDate on, SourceRef source, Actor actor) {
        // Donated or opening stock costs nothing; the ledger forbids zero amounts, so skip it instead.
        if (amount != null && amount.signum() == 0) return;
        append(EntryType.COST_OF_GOODS, amount, null, on, null, requireSource(source), actor);
    }

    /** {@inheritDoc} */
    @Override
    public EntryView recordPayment(BigDecimal amount, PaymentMethod method, LocalDate on, SourceRef source,
                                   Actor actor) {
        return append(EntryType.PAYMENT_IN, amount, method, on, null, requireSource(source), actor);
    }

    /** {@inheritDoc} */
    @Override
    public void reverseSaleAndCost(SourceRef source, LocalDate on, Actor actor) {
        // Find the source's sale and cost rows, skip those already reversed (makes the call idempotent),
        // and append a compensating row for each. Originals are never touched, so the audit trail stays intact.
        entries.findBySourceTypeAndSourceIdAndTypeIn(source.type(), source.id(),
                        List.of(EntryType.SALE, EntryType.COST_OF_GOODS))
                .stream()
                .filter(e -> !entries.existsByReversesId(e.id()))
                .forEach(e -> entries.save(e.reversal(on, actor, clock.instant())));
    }

    /** {@inheritDoc} */
    @Override
    public EntryView recordRefund(BigDecimal amount, PaymentMethod method, LocalDate on, String description,
                                  SourceRef source, Actor actor) {
        // Handing money back is an owner decision.
        if (!actor.isOwner()) throw new AccessDeniedException("Refunds are restricted to the owner");
        BigDecimal refund = normalise(amount);
        requireSource(source);
        requireNotFuture(on);
        // Locking the payment rows makes concurrent refunds for one source queue up, so the cap below
        // is checked against up-to-date totals and the source cannot be over-refunded.
        entries.findBySourceTypeAndSourceIdAndType(source.type(), source.id(), EntryType.PAYMENT_IN);
        // Business rule: never return more than was received and not yet returned.
        if (refund.compareTo(retainedPayment(source)) > 0)
            throw new RuleViolation("Refund exceeds the payment still held for " + source.type() + " " + source.id());
        return append(EntryType.REFUND, refund, method, on, description, source, actor);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public BigDecimal retainedPayment(SourceRef source) {
        // Money still held = everything received minus everything already handed back.
        return entries.sum(source.type(), source.id(), EntryType.PAYMENT_IN)
                .subtract(entries.sum(source.type(), source.id(), EntryType.REFUND));
    }

    /** {@inheritDoc} Read-only: reports must never write to the ledger. */
    @Override
    @Transactional(readOnly = true)
    public Map<EntryType, BigDecimal> totalsByType(LocalDate from, LocalDate to) {
        // Same period rule as entries(): both dates present and in order.
        requirePeriod(from, to);
        // Pre-fill every type with 0.00 so callers never meet a missing key or a null.
        Map<EntryType, BigDecimal> totals = new EnumMap<>(EntryType.class);
        for (EntryType t : EntryType.values()) totals.put(t, BigDecimal.ZERO.setScale(2));
        // One grouped query; the database does the summing, only one row per type comes back.
        for (Object[] row : entries.totalsByType(from, to))
            // Amounts are numeric(14,2), so the scale is already 2 and UNNECESSARY would flag any surprise.
            totals.put((EntryType) row[0], ((BigDecimal) row[1]).setScale(2, RoundingMode.UNNECESSARY));
        return totals;
    }

    /** {@inheritDoc} Read-only: reports must never write to the ledger. */
    @Override
    @Transactional(readOnly = true)
    public Map<PaymentMethod, BigDecimal> cashByMethod(LocalDate from, LocalDate to) {
        // Same period rule as entries(): both dates present and in order.
        requirePeriod(from, to);
        // Pre-fill every method with 0.00 so unused methods still appear in the breakdown.
        Map<PaymentMethod, BigDecimal> cash = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethod m : PaymentMethod.values()) cash.put(m, BigDecimal.ZERO.setScale(2));
        // The query signs each row by direction: cash in counts positive, cash out negative.
        for (Object[] row : entries.cashByMethod(from, to, EntryType.CashDirection.IN))
            cash.put((PaymentMethod) row[0], ((BigDecimal) row[1]).setScale(2, RoundingMode.UNNECESSARY));
        return cash;
    }

    /** {@inheritDoc} Read-only: reports must never write to the ledger. */
    @Override
    @Transactional(readOnly = true)
    public BigDecimal retainedOnCancelledSources(LocalDate asOf) {
        if (asOf == null) throw new RuleViolation("An as-of date is required");
        // The repository finds cancelled sources (those with a SALE_REVERSAL) and nets payments against refunds.
        return entries.retainedOnCancelled(asOf, EntryType.PAYMENT_IN, EntryType.REFUND, EntryType.SALE_REVERSAL)
                .setScale(2, RoundingMode.UNNECESSARY);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Page<EntryView> entries(LocalDate from, LocalDate to, EntryType type, Pageable page) {
        requirePeriod(from, to);
        // A null type means "all types".
        var result = type == null ? entries.period(from, to, page) : entries.periodByType(from, to, type, page);
        // Hand out views, not entities, so callers cannot depend on the persistence model.
        return result.map(FinancialEntry::view);
    }

    /**
     * Validates the amount, builds the immutable row and stores it.
     *
     * @param type        what the entry means
     * @param amount      requested amount
     * @param method      payment method, required for cash entries and ignored otherwise
     * @param on          business date
     * @param description optional note
     * @param source      originating record, may be null (expenses)
     * @param actor       who is recording it
     * @return the stored entry as a view
     * @throws RuleViolation if the amount is not a positive peso value or a cash entry lacks a method
     */
    private EntryView append(EntryType type, BigDecimal amount, PaymentMethod method, LocalDate on,
                             String description, SourceRef source, Actor actor) {
        // The creation time comes from the injected clock so tests control it.
        var e = FinancialEntry.of(type, normalise(amount), method, on, description, source, actor, clock.instant());
        return entries.save(e).view();
    }

    /**
     * Rejects non-positive amounts and more than two decimals, returning the amount at scale 2.
     *
     * @param amount the requested amount
     * @return the same value at scale 2
     * @throws RuleViolation if the amount is missing, zero or negative, or has more than 2 decimals
     */
    private static BigDecimal normalise(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new RuleViolation("Amount must be greater than zero");
        // Centavos are the smallest unit: refusing a third decimal avoids silent rounding of money.
        if (amount.stripTrailingZeros().scale() > 2) throw new RuleViolation("Amount cannot have more than 2 decimals");
        // UNNECESSARY proves no digit is lost, as the check above guarantees.
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * Ensures a source record is present.
     *
     * @param source the source to check
     * @return the same source, for fluent use
     * @throws RuleViolation if the source is null
     */
    private static SourceRef requireSource(SourceRef source) {
        if (source == null) throw new RuleViolation("A source record is required");
        return source;
    }

    /**
     * Rejects a missing or backwards period; shared by every period query so they agree on the rule.
     *
     * @param from first date of the period
     * @param to   last date of the period
     * @throws RuleViolation if either date is missing or {@code to} is before {@code from}
     */
    private static void requirePeriod(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from))
            throw new RuleViolation("A valid from/to period is required");
    }

    /**
     * Rejects dates after today in the business time zone, so cash cannot be booked ahead of time.
     *
     * @param on the date to check; null is left for the entry itself to reject
     * @throws RuleViolation if the date is after today
     */
    private void requireNotFuture(LocalDate on) {
        if (on != null && on.isAfter(LocalDate.now(clock))) throw new RuleViolation("Date cannot be in the future");
    }
}
