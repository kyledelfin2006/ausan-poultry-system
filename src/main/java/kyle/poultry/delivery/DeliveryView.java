package kyle.poultry.delivery;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import kyle.poultry.finance.api.model.PaymentMethod;

/**
 * Read model of a delivery for the API. Package-private; serialized to JSON by {@link DeliveryController}.
 *
 * @param id              database id
 * @param code            human-readable code such as {@code DLV-2026-0001}
 * @param status          lifecycle state
 * @param customerName    who the goods are for
 * @param driverName      who delivers them
 * @param fromBranchId    branch whose stock was used
 * @param destination     where the goods go
 * @param startedAt       when the trip started
 * @param completedAt     when it arrived, or null while not done
 * @param total           sum of the line totals
 * @param paymentMethod   how the customer paid
 * @param cancelReason    why it was cancelled, or null
 * @param returnReason    why the goods came back after arrival, or null
 * @param retainedPayment for a CANCELLED or RETURNED delivery: money received and not yet refunded; otherwise null
 * @param lines           the packages sold
 * @param createdBy       id of the user who created it
 * @param createdAt       when it was created
 */
record DeliveryView(long id, String code, DeliveryStatus status, String customerName, String driverName,
                    long fromBranchId, String destination, Instant startedAt, Instant completedAt,
                    BigDecimal total, PaymentMethod paymentMethod, String cancelReason, String returnReason,
                    BigDecimal retainedPayment,
                    List<DeliveryLine> lines, long createdBy, Instant createdAt) {}
