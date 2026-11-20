package kyle.poultry.audit;

/**
 * The kind of record an audit entry is about. Stored as text in {@code audit_log.entity_type}, so existing names
 * must never be renamed.
 */
public enum AuditEntity {
    /** An application user (module {@code identity}). */
    USER,
    /** A product (module {@code catalog}). */
    PRODUCT,
    /** A branch (module {@code catalog}). */
    BRANCH,
    /** A sales package (module {@code catalog}). */
    PACKAGE
}
