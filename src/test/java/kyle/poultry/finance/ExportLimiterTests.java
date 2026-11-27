package kyle.poultry.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kyle.poultry.shared.error.TooManyRequestsException;
import org.junit.jupiter.api.Test;

/** The export cap: refuses at once when full, frees a slot when a permit is released, and ignores a second release. */
class ExportLimiterTests {

    @Test
    void refusesWhenAllPermitsAreTakenAndAdmitsAgainAfterARelease() {
        var limiter = new ExportLimiter(2);
        var first = limiter.acquire();
        limiter.acquire();

        assertThatThrownBy(limiter::acquire).isInstanceOfSatisfying(TooManyRequestsException.class,
                e -> assertThat(e.retryAfterSeconds()).isEqualTo(ExportLimiter.RETRY_AFTER_SECONDS));

        first.release();
        limiter.acquire();   // a slot is free again
    }

    @Test
    void releasingTheSamePermitTwiceFreesOnlyOneSlot() {
        var limiter = new ExportLimiter(1);
        var permit = limiter.acquire();
        permit.release();
        permit.release();

        limiter.acquire();
        assertThatThrownBy(limiter::acquire).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void aLimitBelowOneIsRejected() {
        assertThatThrownBy(() -> new ExportLimiter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
