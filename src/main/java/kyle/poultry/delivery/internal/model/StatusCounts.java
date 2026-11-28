package kyle.poultry.delivery.internal.model;

/**
 * How many deliveries a selection holds in each status.
 *
 * @param active    deliveries still on the road
 * @param done      deliveries that arrived
 * @param cancelled deliveries that were cancelled
 * @param returned  deliveries that arrived and then came back
 * @param total     the four added together
 */
public record StatusCounts(long active, long done, long cancelled, long returned, long total) {}
