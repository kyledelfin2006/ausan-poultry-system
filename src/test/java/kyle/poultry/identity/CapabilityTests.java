package kyle.poultry.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** The central policy: what each role may do, and how an actor is asked. */
class CapabilityTests {
    private final Actor owner = new Actor(1, Role.OWNER);
    private final Actor assistant = new Actor(2, Role.ASSISTANT);

    @Test
    void theOwnerHasEveryCapability() {
        for (Capability c : Capability.values()) assertThat(owner.can(c)).as(c.name()).isTrue();
        assertThat(Capability.listFor(Role.OWNER)).containsExactly(Capability.values());
    }

    /** Every action here is owner-only today; granting one to assistants must be a deliberate edit that fails this test. */
    @Test
    void anAssistantHasNoneOfThem() {
        for (Capability c : Capability.values()) assertThat(assistant.can(c)).as(c.name()).isFalse();
        assertThat(Capability.listFor(Role.ASSISTANT)).isEmpty();
    }

    @Test
    void requireRefusesWithAccessDeniedAndNamesTheCapability() {
        assertThatThrownBy(() -> assistant.require(Capability.CANCEL_DELIVERY))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("CANCEL_DELIVERY");
        assertThatCode(() -> owner.require(Capability.CANCEL_DELIVERY)).doesNotThrowAnyException();
    }
}
