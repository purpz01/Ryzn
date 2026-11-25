package ryzn.macro.client.core.events.api;

/**
 * Requests one Sand Miner action from UI, failsafe, or another macro.
 *
 * @description Delivers a saved-state or route action to the registered macro.
 * @param action the requested operation
 * @param value the Boolean value used by setting actions
 */
public record SandMinerActionEvent(Action action, boolean value) implements Event {
  /** The Sand Miner request kinds. */
  public enum Action {
    /** Turns the run on or off. */
    SET_ENABLED,
    /** Changes whether the route is drawn. */
    SET_SHOW_ROUTE,
    /** Changes whether sand is crafted into enchanted sand. */
    SET_CRAFT_ENCHANTED,
    /** Adds the player's position as a route point. */
    ADD_WAYPOINT,
    /** Removes the most recently added route point. */
    REMOVE_WAYPOINT,
    /** Removes every route point. */
    CLEAR_WAYPOINTS,
    /** Restores the default route and options. */
    RESET_DEFAULTS,
    /** Clears the tracked collection level. */
    RESET_COLLECTION
  }

  /**
   * @description Creates an action that does not require a Boolean value.
   * @param action the requested operation; its Boolean value is false
   */
  public SandMinerActionEvent(Action action) {
    this(action, false);
  }
}
