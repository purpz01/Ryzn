package ryzn.macro.client.core.events.api;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * Requests one Gold Shovel run action from its command, the failsafe, or a Level 7 route.
 *
 * <p>Step and Rev lvl 2 starts are synchronous: the macro records whether the run started, so the
 * publisher can report or fail its own stage when it did not.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Accessors(fluent = true)
public final class GoldShovelActionEvent implements Event {
  /** The Gold Shovel request kinds. */
  public enum Action {
    /** Turns the full run on or off. */
    SET_ENABLED,
    /** Starts the run at the carried step and leg. */
    START_AT,
    /** Runs only the Rev lvl 2 Undead Sword Smite V leg. */
    START_UNDEAD_SWORD_SMITE
  }

  /** The requested operation the macro should apply. */
  private final Action action;

  /** The run state for a run-state request, or whether a step start is the axe leg. */
  private final boolean flag;

  /** The step name for a step start, otherwise null. */
  private final String step;

  /** True only after the registered macro accepted a step or Rev lvl 2 start. */
  private boolean started;

  /**
   * @description Creates a run-state request.
   * @param enabled the requested run state
   * @return the request
   */
  public static GoldShovelActionEvent enabled(boolean enabled) {
    return new GoldShovelActionEvent(Action.SET_ENABLED, enabled, null);
  }

  /**
   * @description Creates a request to start the run at one step.
   * @param step the step name, matched without case
   * @param axeLeg true to run the step as the axe leg
   * @return the request
   */
  public static GoldShovelActionEvent startAt(String step, boolean axeLeg) {
    return new GoldShovelActionEvent(Action.START_AT, axeLeg, step);
  }

  /**
   * @description Creates a request to run only the Rev lvl 2 Undead Sword Smite V leg.
   * @return the request
   */
  public static GoldShovelActionEvent startUndeadSwordSmite() {
    return new GoldShovelActionEvent(Action.START_UNDEAD_SWORD_SMITE, false, null);
  }

  /**
   * @description Records the outcome of a start for the publisher.
   * @param started whether the run started
   */
  public void setStarted(boolean started) {
    this.started = started;
  }
}
