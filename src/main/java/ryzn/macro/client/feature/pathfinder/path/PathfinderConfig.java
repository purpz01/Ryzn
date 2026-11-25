package ryzn.macro.client.feature.pathfinder.path;

/** Persisted pathfinder behaviour toggles. User-facing options that survive a restart live here. */
public final class PathfinderConfig {
  private PathfinderConfig() {}

  public enum GoalMode {
    /** Stop once the player reaches the standard waypoint-advance radius near the goal. */
    FUZZY,
    /** Keep walking until the player is standing on the goal block (X/Z match). */
    EXACT
  }

  public static GoalMode goalMode = GoalMode.FUZZY;
}
