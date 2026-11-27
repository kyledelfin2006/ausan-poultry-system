package kyle.poultry.finance;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.function.Consumer;
import java.util.stream.Stream;
import kyle.poultry.finance.FinanceApi.EntryView;
import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.identity.api.model.Capability;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads ledger entries one at a time for exports, so a large period never has to sit in memory. It returns the same
 * entries, in the same order and under the same role rules, as {@link FinanceApi#entries}; only the delivery differs.
 *
 * <p>It is a separate bean (not a method of {@link FinanceLedger}) so its transaction starts on whichever thread
 * calls it: a streamed response is written on an async thread, after the request thread has returned.
 */
@Service
class EntryExporter {
    /** Entities read so far are detached in groups of this size so memory does not grow with the period. */
    private static final int DETACH_EVERY = 500;

    private final FinancialEntryRepository entries;
    @PersistenceContext private EntityManager em;

    /**
     * Creates the exporter.
     *
     * @param entries storage for ledger rows
     */
    EntryExporter(FinancialEntryRepository entries) { this.entries = entries; }

    /**
     * Passes each entry of the period to {@code sink}, newest date first and, within a day, newest id first. The
     * period must already have passed {@link FinanceLedger#requirePeriod}. The whole read is one query, so the rows
     * form one consistent snapshot even while entries are being recorded.
     *
     * @param from  first business date included
     * @param to    last business date included
     * @param type  only this type, or null for all
     * @param actor the caller; assistants see only the types they are allowed to see, as in {@link FinanceApi#entries}
     * @param sink  receives each entry; an exception it throws stops the export
     */
    @Transactional(readOnly = true)
    void forEach(LocalDate from, LocalDate to, EntryType type, Actor actor, Consumer<EntryView> sink) {
        if (!actor.can(Capability.VIEW_COST_AND_PROFIT) && type != null && !FinanceLedger.ASSISTANT_VISIBLE_TYPES.contains(type)) return;
        try (Stream<FinancialEntry> rows = actor.can(Capability.VIEW_COST_AND_PROFIT)
                ? type == null ? entries.streamPeriod(from, to) : entries.streamPeriodByType(from, to, type)
                : type == null ? entries.streamPeriodByTypes(from, to, FinanceLedger.ASSISTANT_VISIBLE_TYPES)
                : entries.streamPeriodByType(from, to, type)) {
            int[] seen = {0};
            rows.forEach(row -> {
                sink.accept(row.view());
                if (++seen[0] % DETACH_EVERY == 0) em.clear();
            });
        }
    }
}
