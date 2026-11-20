package kyle.poultry.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Persistence for {@link AuditLog}. Package-private; used by {@link AuditService} only. Rows are only ever inserted
 * and read, never updated or deleted. Filtering uses specifications so each optional filter is simply left out when
 * absent (a nullable-parameter query would need database-specific casts).
 */
interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {}
