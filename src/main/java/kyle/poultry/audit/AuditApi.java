package kyle.poultry.audit;

/**
 * The only way other modules write to the audit trail. The acting user is taken from the current security context,
 * so callers never pass (and can never forge) who did it; with no authenticated user the entry is attributed to
 * {@code system}. Entries are written inside the caller's transaction: if the change rolls back, so does its entry,
 * and a change can never commit without its entry.
 */
public interface AuditApi {

    /**
     * Appends one entry to the audit trail.
     *
     * @param action   what happened
     * @param entity   the kind of record
     * @param entityId the record's id
     * @param summary  a human-readable description (at most 1000 characters; longer text is cut). It must never
     *                 contain passwords, hashes or tokens.
     * @throws org.springframework.transaction.IllegalTransactionStateException when called outside a transaction (a programming error)
     */
    void record(AuditAction action, AuditEntity entity, long entityId, String summary);
}
