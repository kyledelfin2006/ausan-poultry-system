package kyle.poultry.audit.internal.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import kyle.poultry.audit.api.AuditApi;
import kyle.poultry.audit.api.model.AuditAction;
import kyle.poultry.audit.api.model.AuditEntity;
import kyle.poultry.audit.internal.model.AuditView;

/**
 * One row of the append-only {@code audit_log} table. Public only so the service layer can reach it; the boundary is enforced by ArchUnit (nothing outside {@code audit} may use {@code ..internal..}). Other modules write through {@link AuditApi}
 * and the API reads through {@link AuditView}. There are no setters; the database also refuses UPDATE and DELETE.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {
    /** Database-generated primary key. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** When the change was recorded. */
    @Column(nullable = false) private Instant occurredAt;
    /** The acting user's id, or null for the system. */
    private Long actorUserId;
    /** The acting user's username at that moment, or {@code system}. */
    @Column(nullable = false) private String actorUsername;
    /** What happened. */
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AuditAction action;
    /** The kind of record. */
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AuditEntity entityType;
    /** The record's id. */
    @Column(nullable = false) private long entityId;
    /** Human-readable description. */
    @Column(nullable = false) private String summary;
    /** The request id of the HTTP call that caused it, if any. */
    private String requestId;

    /** Required by JPA; not for application code. */
    protected AuditLog() {}

    /**
     * Creates a new entry.
     *
     * @param occurredAt    when it happened
     * @param actorUserId   the acting user's id, or null for the system
     * @param actorUsername the acting user's username, or {@code system}
     * @param action        what happened
     * @param entityType    the kind of record
     * @param entityId      the record's id
     * @param summary       the description (already trimmed to the column size)
     * @param requestId     the request id, or null
     */
    public AuditLog(Instant occurredAt, Long actorUserId, String actorUsername, AuditAction action, AuditEntity entityType,
             long entityId, String summary, String requestId) {
        this.occurredAt = occurredAt; this.actorUserId = actorUserId; this.actorUsername = actorUsername;
        this.action = action; this.entityType = entityType; this.entityId = entityId;
        this.summary = summary; this.requestId = requestId;
    }

    /** {@return the API view of this entry} */
    public AuditView view() {
        return new AuditView(id, occurredAt, actorUserId, actorUsername, action, entityType, entityId, summary, requestId);
    }
}
