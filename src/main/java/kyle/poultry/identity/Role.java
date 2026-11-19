package kyle.poultry.identity;

/**
 * Authorization level of a user: OWNER may administer users and perform owner-only actions (cancel, refund, edit the
 * catalog); ASSISTANT handles day-to-day work. Public because other modules check {@link Actor#role()}.
 */
public enum Role { OWNER, ASSISTANT }
