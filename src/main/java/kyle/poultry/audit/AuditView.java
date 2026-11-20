package kyle.poultry.audit;

import java.time.Instant;

/**
 * One audit entry as returned by {@code GET /api/audit}.
 *
 * @param id            entry id (newest entries have the highest ids)
 * @param occurredAt    when the change was recorded (UTC instant)
 * @param actorUserId   the user who made the change, or null when the system did
 * @param actorUsername that user's username at the time, or {@code system}
 * @param action        what happened
 * @param entityType    the kind of record that changed
 * @param entityId      the id of the record that changed
 * @param summary       a readable description, for example {@code price: 70000.00 -> 75000.00}
 * @param requestId     the {@code X-Request-Id} of the HTTP call that caused it, or null
 */
public record AuditView(long id, Instant occurredAt, Long actorUserId, String actorUsername, AuditAction action,
                        AuditEntity entityType, long entityId, String summary, String requestId) {}
