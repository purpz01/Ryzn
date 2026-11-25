package ryzn.macro.client.core.events.api;

import ryzn.macro.client.feature.macros.impl.tree.TreeLogType;

/**
 * Requests one Tree Chopper action from the command, menu, failsafe, or another macro.
 *
 * @description Delivers a start, stop, or log-type request to the registered chopper.
 * @param action the requested operation
 * @param logType the new log type for {@link Action#SET_LOG_TYPE}, otherwise null
 */
public record TreeChopActionEvent(Action action, TreeLogType logType) implements Event {
  /** The Tree Chopper request kinds. */
  public enum Action {
    /** Starts chopping. */
    START,
    /** Starts chopping toward a collection goal. */
    START_FOR_COLLECTION,
    /** Stops chopping. */
    STOP,
    /** Selects the carried log type. */
    SET_LOG_TYPE,
    /** Reacts to a log type changed elsewhere. */
    LOG_TYPE_CHANGED,
    /** Forgets the trees marked unreachable. */
    RESET_LOST_TREES
  }

  /**
   * @description Creates an action that carries no log type.
   * @param action a lifecycle or notification action
   */
  public TreeChopActionEvent(Action action) {
    this(action, null);
  }
}
