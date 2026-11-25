package ryzn.macro.client.core.events.api;

/**
 * Requests one Level 7 route lifecycle action from the menu, command, or failsafe.
 *
 * @description Delivers a start or stop request to the registered route macro.
 * @param action the requested operation
 */
public record Level7ActionEvent(Action action) implements Event {
  /** The Level 7 route request kinds. */
  public enum Action {
    /** Starts the route. */
    START,
    /** Starts at the first stage of the route. */
    START_FRESH,
    /** Stops the route and its active child, and cancels any pending recovery. */
    STOP,
    /** A failsafe stop: the route's own recovery may still bring it back. */
    FAILSAFE_STOP,
    /** A failsafe stop for an unexpected server swap, which the route walks back from. */
    WORLD_CHANGE_STOP
  }
}
