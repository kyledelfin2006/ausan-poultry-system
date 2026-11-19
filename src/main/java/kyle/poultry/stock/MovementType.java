package kyle.poultry.stock;

import java.util.EnumSet;
import java.util.Set;

/**
 * The reason a quantity of stock entered or left a branch, and therefore which sign (direction) is legal.
 * <p>
 * Every {@code StockMovement} row has exactly one type. The same sign rules are enforced twice: in
 * {@link #accepts(long)} before saving and by the {@code ck_movement_sign} check constraint in the database.
 * Anyone recording stock picks a type from {@link #MANUAL}; the delivery types are used only internally.
 */
public enum MovementType {
    /** Stock counted when the system starts, or first stocked at a branch (adds stock, owner only). */
    OPENING(Sign.IN),
    /** Stock bought from a supplier (adds stock, and the cash paid goes to the finance ledger). */
    PURCHASE(Sign.IN),
    /** Stock received free of charge (adds stock). */
    DONATION(Sign.IN),
    /** Stock returned when a delivery is cancelled; created only by the delivery flow (adds stock). */
    DELIVERY_REVERSAL(Sign.IN),
    /** Stock consumed or lost on the farm, e.g. feed given to birds (removes stock, reason required). */
    USAGE(Sign.OUT),
    /** Stock leaving for a customer delivery; created only by the delivery flow (removes stock). */
    DELIVERY_OUT(Sign.OUT),
    /** A correction after a physical count (adds or removes stock, owner only, reason required). */
    ADJUSTMENT(Sign.EITHER);

    /** Types a person may enter directly. Delivery types are produced only by the delivery flow. */
    public static final Set<MovementType> MANUAL = EnumSet.of(OPENING, PURCHASE, DONATION, USAGE, ADJUSTMENT);

    /** Types only the owner may enter (they create or rewrite quantities without a physical event). */
    public static final Set<MovementType> OWNER_ONLY = EnumSet.of(OPENING, ADJUSTMENT);

    /** The direction of stock a movement type is allowed to have. */
    private enum Sign {
        /** Quantity must be positive. */
        IN,
        /** Quantity must be negative. */
        OUT,
        /** Quantity may be positive or negative, but not zero. */
        EITHER
    }

    private final Sign sign;

    /**
     * Binds a type to its allowed direction.
     *
     * @param sign the legal quantity direction for this type
     */
    MovementType(Sign sign) { this.sign = sign; }

    /**
     * Tells whether a signed quantity has a legal direction for this type. Zero is never legal.
     *
     * @param delta the signed quantity change (positive adds stock, negative removes it)
     * @return {@code true} if the sign matches what this type allows
     */
    boolean accepts(long delta) {
        return switch (sign) {
            case IN -> delta > 0;
            case OUT -> delta < 0;
            case EITHER -> delta != 0;
        };
    }

    /**
     * Tells whether a free-text reason must accompany this type. Usage and adjustments need one because
     * they change stock without a purchase or delivery record to explain them.
     *
     * @return {@code true} for {@link #ADJUSTMENT} and {@link #USAGE}
     */
    boolean needsReason() { return this == ADJUSTMENT || this == USAGE; }
}
