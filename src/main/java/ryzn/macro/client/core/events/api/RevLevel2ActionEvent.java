package ryzn.macro.client.core.events.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * Requests one Rev lvl 2 lifecycle action from the menu, command, failsafe, or Level 7 route.
 *
 * <p>A Level 7 start is synchronous: the macro records whether the child route actually started, so
 * the parent can fail its own stage when it did not.
 */
@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public final class RevLevel2ActionEvent implements Event {
  /** The Rev lvl 2 request kinds. */
  public enum Action {
    /** Starts the route from the menu or command. */
    START,
    /** Starts the route as a Level 7 child and records whether it started. */
    START_FROM_LEVEL7,
    /** Stops the route. */
    STOP
  }

  /** The requested start or stop the macro should apply. */
  private final Action action;

  /** True only after the registered macro accepted a Level 7 start. */
  private boolean started;

  /**
   * @description Records the outcome of a Level 7 start for the publisher.
   * @param started whether the route started
   */
  public void setStarted(boolean started) {
    this.started = started;
  }
}
