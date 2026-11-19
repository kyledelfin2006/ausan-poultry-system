package kyle.poultry.delivery;

/**
 * Lifecycle of a delivery: ACTIVE -> DONE, or ACTIVE -> CANCELLED. DONE and CANCELLED are final.
 * Package-private; appears in the API as a string.
 */
enum DeliveryStatus { ACTIVE, DONE, CANCELLED }
