package kyle.poultry.identity.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings {@code app.bootstrap.*} naming the first owner account to create on an empty system. Internal to the identity module;
 * read only by {@code OwnerBootstrap}.
 *
 * @param ownerUsername login name of the initial owner
 * @param ownerPassword initial password; supplied through the environment, never committed
 * @param requirePasswordChange whether the initial owner must replace the bootstrap password before using the API
 */
@ConfigurationProperties("app.bootstrap")
public record BootstrapProperties(String ownerUsername, String ownerPassword, boolean requirePasswordChange) {

    /** {@return true when both the username and the password are set and non-blank} */
    public boolean configured() {
        return ownerUsername != null && !ownerUsername.isBlank() && ownerPassword != null && !ownerPassword.isBlank();
    }
}
