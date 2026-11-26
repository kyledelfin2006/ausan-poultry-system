package kyle.poultry.identity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One thing a user may be allowed to do, named for the action and not for the role that happens to have it today.
 *
 * <p>This is the single place that says which role may do what ({@link #grantedTo}). Services ask
 * {@code actor.require(Capability.X)} or {@code actor.can(...)} instead of comparing roles, and
 * {@code GET /api/auth/me} lists the caller's capabilities so a UI can show or hide actions without guessing from the
 * role. Giving an assistant one of these later is a change to {@link #grantedTo} only.
 *
 * <p>Route-level {@code @PreAuthorize("hasRole('OWNER')")} rules on controllers are not derived from this enum yet; a
 * test keeps the two from disagreeing about what an assistant may do.
 */
public enum Capability {
    /** Cancel a delivery and reverse its stock and money. */
    CANCEL_DELIVERY,
    /** Record a refund of money held for a cancelled delivery. */
    RECORD_REFUND,
    /** Enter opening stock or a stock adjustment (quantities with no physical event). */
    ADJUST_STOCK,
    /** Change a product's reorder level. */
    SET_REORDER_LEVELS,
    /** Date an entry more than the assistant window in the past. */
    BACKDATE_FREELY,
    /** See cost, profit, cash-movement and expense figures, and the ledger types that carry them. */
    VIEW_COST_AND_PROFIT,
    /** Create and edit products, packages and branches. */
    MANAGE_CATALOG,
    /** Create users, change roles, reset passwords. */
    MANAGE_USERS,
    /** Read the audit trail. */
    VIEW_AUDIT_LOG;

    /**
     * The capabilities of a role.
     *
     * @param role the role
     * @return the capabilities that role has; an assistant has none of the owner-only ones
     */
    public static Set<Capability> grantedTo(Role role) {
        return switch (role) {
            case OWNER -> EnumSet.allOf(Capability.class);
            case ASSISTANT -> EnumSet.noneOf(Capability.class);
        };
    }

    /**
     * The capabilities of a role as a list in declaration order, for stable JSON.
     *
     * @param role the role
     * @return the granted capabilities, ordered
     */
    public static List<Capability> listFor(Role role) {
        Set<Capability> granted = grantedTo(role);
        return Arrays.stream(values()).filter(granted::contains).toList();
    }
}
