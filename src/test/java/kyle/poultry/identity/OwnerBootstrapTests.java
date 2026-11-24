package kyle.poultry.identity;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The first owner is marked for a password change when bootstrap policy requires it. */
@ExtendWith(MockitoExtension.class)
class OwnerBootstrapTests {
    @Mock UserService users;

    /** An empty system creates its initial owner with the configured password-change requirement. */
    @Test
    void createsTheBootstrapOwnerWithTheConfiguredPasswordRequirement() {
        when(users.count()).thenReturn(0L);
        var properties = new BootstrapProperties("owner", "initial-password", true);

        new OwnerBootstrap(users, properties).run(null);

        verify(users).create("owner", "initial-password", "Owner", Role.OWNER, true);
    }
}
