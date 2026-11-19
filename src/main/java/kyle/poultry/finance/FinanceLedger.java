package kyle.poultry.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kyle.poultry.identity.Actor;
import kyle.poultry.shared.RuleViolation;
import kyle.poultry.shared.SourceRef;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class FinanceLedger implements FinanceApi {
    private final FinancialEntryRepository entries;
    private final Clock clock;

    FinanceLedger(FinancialEntryRepository entries, Clock clock) { this.entries = entries; this.clock = clock; }

    @Override
    public EntryView recordStockPurchase(BigDecimal amount, PaymentMethod method, LocalDate on, String description,
                                         SourceRef source, Actor actor) {
        return append(EntryType.STOCK_PURCHASE, amount, method, on, description, source, actor);
    }

    @Override
    public EntryView recordExpense(BigDecimal amount, PaymentMethod method, LocalDate on, String description, Actor actor) {
        if (description == null || description.isBlank()) throw new RuleViolation("An expense needs a description");
        requireNotFuture(on);
        return append(EntryType.EXPENSE, amount, method, on, description, null, actor);
    }

    @Override
    public EntryView recordSale(BigDecimal amount, LocalDate on, SourceRef source, Actor actor) {
        return append(EntryType.SALE, amount, null, on, null, requireSource(source), actor);
    }

    @Override
    public void recordCostOfGoods(BigDecimal amount, LocalDate on, SourceRef source, Actor actor) {
        if (amount != null && amount.signum() == 0) return;   // e.g. donated stock: no cost to recognise
        append(EntryType.COST_OF_GOODS, amount, null, on, null, requireSource(source), actor);
    }

    @Override
    public EntryView recordPayment(BigDecimal amount, PaymentMethod method, LocalDate on, SourceRef source, Actor actor) {
        return append(EntryType.PAYMENT_IN, amount, method, on, null, requireSource(source), actor);
    }

    @Override
    public void reverseSaleAndCost(SourceRef source, LocalDate on, Actor actor) {
        entries.findBySourceTypeAndSourceIdAndTypeIn(source.type(), source.id(), List.of(EntryType.SALE, EntryType.COST_OF_GOODS))
                .stream()
                .filter(e -> !entries.existsByReversesId(e.id()))
                .forEach(e -> entries.save(e.reversal(on, actor, clock.instant())));
    }

    @Override
    public EntryView recordRefund(BigDecimal amount, PaymentMethod method, LocalDate on, String description,
                                  SourceRef source, Actor actor) {
        if (!actor.isOwner()) throw new AccessDeniedException("Refunds are restricted to the owner");
        BigDecimal refund = normalise(amount);
        requireSource(source);
        requireNotFuture(on);
        entries.findBySourceTypeAndSourceIdAndType(source.type(), source.id(), EntryType.PAYMENT_IN);   // takes the lock
        if (refund.compareTo(retainedPayment(source)) > 0)
            throw new RuleViolation("Refund exceeds the payment still held for " + source.type() + " " + source.id());
        return append(EntryType.REFUND, refund, method, on, description, source, actor);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal retainedPayment(SourceRef source) {
        return entries.sum(source.type(), source.id(), EntryType.PAYMENT_IN)
                .subtract(entries.sum(source.type(), source.id(), EntryType.REFUND));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EntryView> entries(LocalDate from, LocalDate to, EntryType type, Pageable page) {
        if (from == null || to == null || to.isBefore(from)) throw new RuleViolation("A valid from/to period is required");
        var result = type == null ? entries.period(from, to, page) : entries.periodByType(from, to, type, page);
        return result.map(FinancialEntry::view);
    }

    private EntryView append(EntryType type, BigDecimal amount, PaymentMethod method, LocalDate on,
                             String description, SourceRef source, Actor actor) {
        var e = FinancialEntry.of(type, normalise(amount), method, on, description, source, actor, clock.instant());
        return entries.save(e).view();
    }

    private static BigDecimal normalise(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new RuleViolation("Amount must be greater than zero");
        if (amount.stripTrailingZeros().scale() > 2) throw new RuleViolation("Amount cannot have more than 2 decimals");
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static SourceRef requireSource(SourceRef source) {
        if (source == null) throw new RuleViolation("A source record is required");
        return source;
    }

    private void requireNotFuture(LocalDate on) {
        if (on != null && on.isAfter(LocalDate.now(clock))) throw new RuleViolation("Date cannot be in the future");
    }
}
