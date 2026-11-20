package kyle.poultry.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Small helpers shared by the pure unit tests (no Spring context, no database).
 * <p>
 * Production entities get their id from the database, so a unit test that needs "a saved entity" has to give it one
 * by hand; and every service reads "now" from an injected {@link Clock}, so a unit test needs a fixed one in the
 * business time zone.
 */
public final class TestSupport {

    /** The business time zone of the farm; the production clock bean uses the same zone. */
    public static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    /** Not instantiable: static helpers only. */
    private TestSupport() {}

    /**
     * Builds a clock that always returns the same instant, in the business time zone.
     *
     * @param instant an ISO-8601 instant such as {@code 2026-11-20T02:15:00Z}
     * @return a fixed clock whose zone is {@link #MANILA}
     */
    public static Clock clockAt(String instant) {
        return Clock.fixed(Instant.parse(instant), MANILA);
    }

    /**
     * Gives an entity the id the database would have generated, so it behaves like a saved row.
     *
     * @param entity the entity, whose id field is called {@code id}
     * @param id     the id to assign
     * @param <T>    the entity type
     * @return the same entity, for fluent use
     */
    public static <T> T withId(T entity, long id) {
        // Entities expose no setter for the id on purpose (only the database assigns it), so reflection is the way in.
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
