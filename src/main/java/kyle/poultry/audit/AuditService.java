package kyle.poultry.audit;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import kyle.poultry.shared.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and reads the audit trail; implements the public {@link AuditApi}. Package-private: other modules use it
 * only through the interface, and {@link AuditController} calls {@link #search}.
 */
@Service
class AuditService implements AuditApi {
    /** Longest summary stored; matches the column size. */
    static final int MAX_SUMMARY = 1000;
    /** Actor name used when nobody is logged in (for example the first-owner bootstrap at startup). */
    static final String SYSTEM = "system";

    private final AuditLogRepository log;
    private final Clock clock;
    private final MeterRegistry metrics;

    /**
     * Creates the service.
     *
     * @param log     audit persistence
     * @param clock   source of "now" (also gives the business time zone for date filters)
     * @param metrics registry for the {@code ausan.audit.entries} counter
     */
    AuditService(AuditLogRepository log, Clock clock, MeterRegistry metrics) {
        this.log = log; this.clock = clock; this.metrics = metrics;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, AuditEntity entity, long entityId, String summary) {
        Long actorId = null;
        String actorName = SYSTEM;
        // The actor comes from the security context only, so no caller can claim to be somebody else.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            actorId = Long.valueOf(jwt.getName());
            String name = jwt.getToken().getClaimAsString("username");
            if (name != null) actorName = name;
        }
        String text = summary == null ? "" : summary;
        if (text.length() > MAX_SUMMARY) text = text.substring(0, MAX_SUMMARY);
        log.save(new AuditLog(clock.instant(), actorId, actorName, action, entity, entityId, text,
                MDC.get(RequestIdFilter.MDC_REQUEST_ID)));
        metrics.counter("ausan.audit.entries", "entity", entity.name(), "action", action.name()).increment();
    }

    /**
     * Searches the trail, newest first. Every filter is optional.
     *
     * @param entity   only this kind of record, or null
     * @param entityId only this record, or null
     * @param actorId  only changes by this user, or null
     * @param from     first business day to include (Manila date), or null
     * @param to       last business day to include, or null
     * @param page     zero-based page number
     * @param size     page size
     * @return one page of entries
     */
    @Transactional(readOnly = true)
    Page<AuditView> search(AuditEntity entity, Long entityId, Long actorId, LocalDate from, LocalDate to, int page, int size) {
        // Start from "match everything" and AND on each filter that was supplied.
        Specification<AuditLog> spec = (r, q, cb) -> cb.conjunction();
        if (entity != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("entityType"), entity));
        if (entityId != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("entityId"), entityId));
        if (actorId != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("actorUserId"), actorId));
        // Dates are business days in the farm's zone: [from 00:00, to + 1 day 00:00) in that zone.
        if (from != null) {
            Instant start = from.atStartOfDay(clock.getZone()).toInstant();
            spec = spec.and((r, q, cb) -> cb.greaterThanOrEqualTo(r.<Instant>get("occurredAt"), start));
        }
        if (to != null) {
            Instant end = to.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
            spec = spec.and((r, q, cb) -> cb.lessThan(r.<Instant>get("occurredAt"), end));
        }
        // Newest first; the id breaks ties so paging is stable.
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        return log.findAll(spec, pageable).map(AuditLog::view);
    }
}
