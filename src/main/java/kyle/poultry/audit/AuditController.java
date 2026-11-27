package kyle.poultry.audit;

import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import kyle.poultry.shared.RuleViolation;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoint {@code GET /api/audit}: the read-only audit trail. OWNER only. Package-private; reached only over HTTP.
 */
@RestController
@RequestMapping("/api/audit")
// The audit module cannot depend on identity (identity depends on audit), so this is the string form of
// @RequiresCapability(VIEW_AUDIT_LOG); CapabilityRoutePolicy reads both and SecurityMatrixTests checks the result.
@PreAuthorize("hasAuthority('CAPABILITY_VIEW_AUDIT_LOG')")
class AuditController {
    private final AuditService audit;

    /**
     * Creates the controller.
     *
     * @param audit the audit service
     */
    AuditController(AuditService audit) { this.audit = audit; }

    /**
     * Searches the audit trail, newest first.
     *
     * @param entityType only this kind of record ({@code USER}, {@code PRODUCT}, {@code BRANCH}, {@code PACKAGE})
     * @param entityId   only this record id
     * @param actorId    only changes made by this user id
     * @param from       first business day to include ({@code yyyy-MM-dd}, Manila time)
     * @param to         last business day to include
     * @param page       zero-based page number, default 0
     * @param size       page size 1 to 100, default 20
     * @return a page of entries
     * @throws RuleViolation (422) when {@code from} is after {@code to}
     */
    @GetMapping
    Page<AuditView> search(@RequestParam(required = false) AuditEntity entityType,
                           @RequestParam(required = false) Long entityId,
                           @RequestParam(required = false) Long actorId,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(defaultValue = "0") @Min(0) int page,
                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        // An inverted range can never match anything, so it is reported as a mistake instead of an empty page.
        if (from != null && to != null && from.isAfter(to)) throw new RuleViolation("from must not be after to");
        return audit.search(entityType, entityId, actorId, from, to, page, size);
    }
}
