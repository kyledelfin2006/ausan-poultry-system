package kyle.poultry.catalog.api.model;

/**
 * Public representation of a branch.
 *
 * @param id     the branch id
 * @param name   the unique branch name
 * @param active whether the branch may be used for new movements and deliveries
 */
public record BranchView(long id, String name, boolean active) {}
