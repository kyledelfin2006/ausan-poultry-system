package kyle.poultry.delivery.internal.model;

/**
 * Lifecycle of a delivery: ACTIVE -> DONE, ACTIVE -> CANCELLED, or DONE -> RETURNED (the whole delivery comes back after
 * arrival). CANCELLED and RETURNED are final; so is DONE except for a return. Internal to the delivery module; appears in the API as a string.
 */
public enum DeliveryStatus { ACTIVE, DONE, CANCELLED, RETURNED }
