package kyle.poultry.delivery;

/**
 * Lifecycle of a delivery: ACTIVE -> DONE, ACTIVE -> CANCELLED, or DONE -> RETURNED (the whole delivery comes back after
 * arrival). CANCELLED and RETURNED are final; so is DONE except for a return. Package-private; appears in the API as a string.
 */
enum DeliveryStatus { ACTIVE, DONE, CANCELLED, RETURNED }
