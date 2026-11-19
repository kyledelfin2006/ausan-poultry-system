package kyle.poultry.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates the first OWNER from configuration when the system has no users yet. */
@Component
class OwnerBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);
    private final UserService users;
    private final BootstrapProperties props;

    OwnerBootstrap(UserService users, BootstrapProperties props) { this.users = users; this.props = props; }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        if (!props.configured()) {
            log.warn("No users exist and app.bootstrap.owner-* is not set; nobody will be able to log in");
            return;
        }
        users.create(props.ownerUsername(), props.ownerPassword(), "Owner", Role.OWNER);
        log.info("Created initial owner account '{}'", props.ownerUsername());
    }
}
