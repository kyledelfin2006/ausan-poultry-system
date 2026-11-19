package kyle.poultry.identity;

/**
 * Read model of a user for the API. Deliberately has no password or hash. Package-private; serialized to JSON.
 *
 * @param id          database id
 * @param username    login name
 * @param displayName name shown in the UI
 * @param role        authorization level
 * @param active      false when the account is deactivated
 */
record UserView(long id, String username, String displayName, Role role, boolean active) {}
