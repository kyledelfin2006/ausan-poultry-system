package kyle.poultry.identity.internal.model;

import kyle.poultry.identity.api.model.Role;

/**
 * Read model of a user for the API. Deliberately has no password or hash. Internal to the identity module; serialized to JSON.
 *
 * @param id          database id
 * @param username    login name
 * @param displayName name shown in the UI
 * @param role        authorization level
 * @param active      false when the account is deactivated
 * @param mustChangePassword true when the user must replace a temporary password
 */
public record UserView(long id, String username, String displayName, Role role, boolean active, boolean mustChangePassword) {}
