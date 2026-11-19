package kyle.poultry.delivery;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/** What was sold, frozen at sale time: later edits to the package never alter a past delivery. */
@Embeddable
record DeliveryLine(long packageId, String packageName, long quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}
