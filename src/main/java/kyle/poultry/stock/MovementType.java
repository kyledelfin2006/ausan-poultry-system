package kyle.poultry.stock;

import java.util.EnumSet;
import java.util.Set;

public enum MovementType {
    OPENING(Sign.IN), PURCHASE(Sign.IN), DONATION(Sign.IN), DELIVERY_REVERSAL(Sign.IN),
    USAGE(Sign.OUT), DELIVERY_OUT(Sign.OUT),
    ADJUSTMENT(Sign.EITHER);

    /** Types a person may enter directly. Delivery types are produced only by the delivery flow. */
    public static final Set<MovementType> MANUAL = EnumSet.of(OPENING, PURCHASE, DONATION, USAGE, ADJUSTMENT);

    /** Types only the owner may enter (they create or rewrite quantities without a physical event). */
    public static final Set<MovementType> OWNER_ONLY = EnumSet.of(OPENING, ADJUSTMENT);

    private enum Sign { IN, OUT, EITHER }

    private final Sign sign;

    MovementType(Sign sign) { this.sign = sign; }

    boolean accepts(long delta) {
        return switch (sign) {
            case IN -> delta > 0;
            case OUT -> delta < 0;
            case EITHER -> delta != 0;
        };
    }

    boolean needsReason() { return this == ADJUSTMENT || this == USAGE; }
}
