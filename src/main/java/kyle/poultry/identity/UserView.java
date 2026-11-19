package kyle.poultry.identity;

record UserView(long id, String username, String displayName, Role role, boolean active) {}
