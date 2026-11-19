package kyle.poultry.shared;

/** A loose pointer to the business record that caused a ledger row (e.g. DELIVERY #42). No foreign key: modules stay independent. */
public record SourceRef(String type, long id) {}
