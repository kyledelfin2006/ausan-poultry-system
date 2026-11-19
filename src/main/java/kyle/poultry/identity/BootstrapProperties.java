package kyle.poultry.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.bootstrap")
record BootstrapProperties(String ownerUsername, String ownerPassword) {
    boolean configured() {
        return ownerUsername != null && !ownerUsername.isBlank() && ownerPassword != null && !ownerPassword.isBlank();
    }
}
