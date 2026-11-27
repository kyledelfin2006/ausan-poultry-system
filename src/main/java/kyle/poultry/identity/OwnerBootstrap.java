package kyle.poultry.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first OWNER from configuration when the system has no users yet, so a fresh install can be logged
 * into. Runs once at startup; package-private and not used by other modules.
 */
@Component
class OwnerBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);
    private final UserService users;
    private final BootstrapProperties props;

    /**
     * Creates the runner.
     *
     * @param users user service used to create the account
     * @param props the configured initial owner credentials
     */
    OwnerBootstrap(UserService users, BootstrapProperties props) { this.users = users; this.props = props; }

    /**
     * Creates the initial owner if no user exists and credentials are configured; otherwise does nothing.
     *
     * @param args startup arguments (unused)
     */
    @Override
    public void run(ApplicationArguments args) {
        // Never touch an existing system: only bootstrap when there are no users at all.
        if (users.count() > 0) return;
        if (!props.configured()) {
            log.warn("No users exist and app.bootstrap.owner-* is not set; nobody will be able to log in");
            return;
        }
        users.create(props.ownerUsername(), props.ownerPassword(), "Owner", Role.OWNER, props.requirePasswordChange());
        // Log the username only; the password must never be logged.
        log.info("Created initial owner account '{}'", props.ownerUsername());
    }
}
