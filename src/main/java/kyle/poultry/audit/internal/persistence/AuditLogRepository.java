package kyle.poultry.audit.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Persistence for {@link AuditLog}. Used by {@link kyle.poultry.audit.internal.service.AuditService} only. Rows are only ever inserted
 * and read, never updated or deleted. Filtering uses specifications so each optional filter is simply left out when
 * absent (a nullable-parameter query would need database-specific casts).
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {}
