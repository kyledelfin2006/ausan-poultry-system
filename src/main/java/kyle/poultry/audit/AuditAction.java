package kyle.poultry.audit;

/**
 * What happened to an audited record. Stored as text in {@code audit_log.action}, so existing names must never be
 * renamed (add new ones instead).
 */
public enum AuditAction {
    /** A new record was created. */
    CREATED,
    /** An existing record was changed; the summary lists each changed field. */
    UPDATED,
    /** An owner replaced another user's password (the password itself is never recorded). */
    PASSWORD_RESET,
    /** A user changed their own password. */
    PASSWORD_CHANGED,
    /** A user signed out, ending all of their sessions. */
    SIGNED_OUT
}
