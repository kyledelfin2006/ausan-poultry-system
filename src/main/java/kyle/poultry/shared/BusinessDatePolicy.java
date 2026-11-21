package kyle.poultry.shared;

import java.time.Clock;
import java.time.LocalDate;

/** Business-date limits shared by the delivery, stock and finance write paths. */
public final class BusinessDatePolicy {
    /** Assistants may enter events up to seven calendar days before the current business date. */
    public static final long ASSISTANT_BACKDATE_DAYS = 7;

    private BusinessDatePolicy() {}

    /**
     * Requires a date that is not in the future and is within the assistant backdating window unless the actor owns
     * the system.
     *
     * @param on       the date supplied for the business event
     * @param owner    whether the actor is an owner
     * @param clock    application clock in the farm's business zone
     * @throws RuleViolation when the date is missing, future-dated or too old for an assistant
     */
    public static void requireAllowed(LocalDate on, boolean owner, Clock clock) {
        if (on == null) throw new RuleViolation("A business date is required");
        LocalDate today = LocalDate.now(clock);
        if (on.isAfter(today)) throw new RuleViolation("Date cannot be in the future");
        if (!owner && on.isBefore(today.minusDays(ASSISTANT_BACKDATE_DAYS)))
            throw new RuleViolation("Assistant entries may be backdated by at most 7 days");
    }
}
