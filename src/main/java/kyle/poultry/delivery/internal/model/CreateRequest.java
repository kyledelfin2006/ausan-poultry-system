package kyle.poultry.delivery.internal.model;

import java.time.Instant;
import java.util.List;
import kyle.poultry.finance.api.model.PaymentMethod;

/**
 * Everything needed to create a delivery.
 *
 * @param customerName  who the goods are for
 * @param driverName    who delivers them
 * @param fromBranchId  branch whose stock is used
 * @param destination   where the goods go
 * @param startedAt     when the trip starts, or null for now
 * @param paymentMethod how the customer paid
 * @param lines         the packages sold; at least one
 */
public record CreateRequest(String customerName, String driverName, long fromBranchId, String destination,
                            Instant startedAt, PaymentMethod paymentMethod, List<LineRequest> lines) {}
