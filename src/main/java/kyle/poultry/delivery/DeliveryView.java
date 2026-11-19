package kyle.poultry.delivery;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import kyle.poultry.finance.PaymentMethod;

/** @param retainedPayment for a CANCELLED delivery: money received and not yet refunded; otherwise null */
record DeliveryView(long id, String code, DeliveryStatus status, String customerName, String driverName,
                    long fromBranchId, String destination, Instant startedAt, Instant completedAt,
                    BigDecimal total, PaymentMethod paymentMethod, String cancelReason, BigDecimal retainedPayment,
                    List<DeliveryLine> lines, long createdBy, Instant createdAt) {}
