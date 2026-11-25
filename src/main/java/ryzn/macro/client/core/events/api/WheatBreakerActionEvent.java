package ryzn.macro.client.core.events.api;

/**
 * Requests one Wheat Breaker action from the menu, failsafe, or Level 7 route.
 *
 * @description Delivers a run-state, option, or Level 7 hand-off request to the registered macro.
 * @param action the requested operation
 * @param value the switch value for option and run-state actions, otherwise false
 */
public record WheatBreakerActionEvent(Action action, boolean value) implements Event {
  /** The Wheat Breaker request kinds. */
  public enum Action {
    /** Turns the run on or off. */
    SET_ENABLED,
    /** Changes whether wheat is sold automatically. */
    SET_AUTO_SELL,
    /** Changes whether a hoe is bought automatically. */
    SET_AUTO_BUY_HOE,
    /** Changes whether the hoe is equipped automatically. */
    SET_AUTO_EQUIP_HOE,
    /** Changes whether a cleared field triggers a lobby hop. */
    SET_LOBBY_HOP_AFTER_CLEAR,
    /** Changes whether the zones are looped. */
    SET_LOOP_ZONES,
    /** Clears the pause the Level 7 route requested. */
    CLEAR_LEVEL7_PAUSE,
    /** Sells the inventory, then pauses for the Level 7 route. */
    SELL_THEN_PAUSE_FOR_LEVEL7,
    /** Resumes the route the Level 7 pause stopped. */
    RESUME_LEVEL7_PAUSED_ROUTE
  }

  /**
   * @description Creates an action that carries no switch value.
   * @param action a hand-off action such as {@link Action#CLEAR_LEVEL7_PAUSE}
   */
  public WheatBreakerActionEvent(Action action) {
    this(action, false);
  }
}
