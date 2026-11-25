package ryzn.macro.client.core.events.api;

import ryzn.macro.client.feature.macros.impl.bea.BeaMacro;

/**
 * Requests one Bea run action from the failsafe or a Level 7 route.
 *
 * @description Delivers a run-state change or a start of one half of the run to the registered
 *     macro.
 * @param action the requested operation
 * @param enabled the requested run state for {@link Action#SET_ENABLED}
 * @param phase the half of the run for {@link Action#START}, otherwise null
 */
public record BeaActionEvent(Action action, boolean enabled, BeaMacro.Phase phase)
    implements Event {
  /** The Bea request kinds. */
  public enum Action {
    /** Turns the full run on or off. */
    SET_ENABLED,
    /** Restarts the run at the carried half. */
    START
  }

  /**
   * @description Creates a run-state request; turning it on runs the full route.
   * @param enabled the requested run state
   * @return the request
   */
  public static BeaActionEvent enabled(boolean enabled) {
    return new BeaActionEvent(Action.SET_ENABLED, enabled, null);
  }

  /**
   * @description Creates a request that restarts the run at one half.
   * @param phase the half of the run to do
   * @return the request
   */
  public static BeaActionEvent start(BeaMacro.Phase phase) {
    return new BeaActionEvent(Action.START, true, phase);
  }
}
