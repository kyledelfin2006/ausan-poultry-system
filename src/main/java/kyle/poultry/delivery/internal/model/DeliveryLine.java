package kyle.poultry.delivery.internal.model;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/**
 * What was sold, frozen at sale time: later edits to the package never alter a past delivery. Embedded in table
 * {@code delivery_line} as part of {@code Delivery}; internal to the delivery module and also used in the API view.
 *
 * @param packageId   the sales package
 * @param packageName the package's name when sold, kept so later renames do not change history
 * @param quantity    how many packages
 * @param unitPrice   price per package for this delivery (may differ from the catalog price)
 * @param lineTotal   {@code unitPrice * quantity}
 */
@Embeddable
public record DeliveryLine(long packageId, String packageName, long quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}
