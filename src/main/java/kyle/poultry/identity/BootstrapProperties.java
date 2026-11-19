package kyle.poultry.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings {@code app.bootstrap.*} naming the first owner account to create on an empty system. Package-private;
 * read only by {@link OwnerBootstrap}.
 *
 * @param ownerUsername login name of the initial owner
 * @param ownerPassword initial password; supplied through the environment, never committed
 */
@ConfigurationProperties("app.bootstrap")
record BootstrapProperties(String ownerUsername, String ownerPassword) {

    /** {@return true when both the username and the password are set and non-blank} */
    boolean configured() {
        return ownerUsername != null && !ownerUsername.isBlank() && ownerPassword != null && !ownerPassword.isBlank();
    }
}
