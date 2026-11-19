package kyle.poultry.shared;

/**
 * A loose pointer to the business record that caused a ledger row (e.g. DELIVERY #42).
 * <p>
 * It carries no foreign key, so modules stay independent: the stock and finance ledgers store only the
 * type and id as plain values. Any module may create one when it asks another module to record something.
 *
 * @param type the kind of source record, e.g. {@code "DELIVERY"} (stored in a 20-character column)
 * @param id   the id of that record within its own module
 */
public record SourceRef(String type, long id) {}
