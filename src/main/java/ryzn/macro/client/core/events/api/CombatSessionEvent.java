package ryzn.macro.client.core.events.api;

import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import net.minecraft.core.BlockPos;
import ryzn.macro.client.feature.modules.impl.combat.CombatModule;

/**
 * Requests one action on the combat engine's managed session from a macro or the failsafe.
 *
 * <p>Macros borrow the engine rather than owning it, so every start, stop, profile change, patrol
 * resume, and look hold travels through this request. A reconfigure or patrol resume is
 * synchronous: the engine records whether it accepted the change before {@code post} returns.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Accessors(fluent = true)
public final class CombatSessionEvent implements Event {
  /** The combat session request kinds. */
  public enum Action {
    /** Starts a session with the carried configuration. */
    START,
    /** Stops the running session. */
    STOP,
    /** Replaces the running session's configuration. */
    RECONFIGURE,
    /** Resumes patrolling from the carried route point. */
    RESUME_PATROL_AT,
    /** Keeps combat off the head for the carried number of milliseconds. */
    HOLD_LOOK
  }

  /** The requested session action. */
  private final Action action;

  /** The session profile for start, reconfigure, and resume, otherwise null. */
  private final CombatModule.ManagedSettings settings;

  /** The stop notification for a start, otherwise null. */
  private final Consumer<CombatModule.ManagedResult> callback;

  /** Whether a reconfigure keeps its route while waiting for a target. */
  private final boolean retainNavigation;

  /** The patrol point for a resume, otherwise null. */
  private final BlockPos point;

  /** The look hold for a hold request in milliseconds, otherwise 0. */
  private final long millis;

  /** True once the engine applied a reconfigure or patrol resume. */
  private boolean accepted;

  /**
   * @description Requests a new managed session, replacing any running one.
   * @param settings the session profile, or null for the defaults
   * @param callback notified once when the session stops
   * @return the start request
   */
  public static CombatSessionEvent start(
      CombatModule.ManagedSettings settings, Consumer<CombatModule.ManagedResult> callback) {
    return new CombatSessionEvent(Action.START, settings, callback, false, null, 0L);
  }

  /**
   * @description Requests that the managed session stop.
   * @return the stop request
   */
  public static CombatSessionEvent stop() {
    return new CombatSessionEvent(Action.STOP, null, null, false, null, 0L);
  }

  /**
   * @description Requests a profile change without tearing down the current route.
   * @param settings the new profile, or null for the defaults
   * @param retainNavigation whether the route is kept while waiting for a target
   * @return the reconfigure request; {@code accepted()} is false when no session is running
   */
  public static CombatSessionEvent reconfigure(
      CombatModule.ManagedSettings settings, boolean retainNavigation) {
    return new CombatSessionEvent(Action.RECONFIGURE, settings, null, retainNavigation, null, 0L);
  }

  /**
   * @description Requests a patrol profile restore routed to one of its points.
   * @param settings the patrol profile, which must contain the point
   * @param point the patrol point to resume at
   * @return the resume request; {@code accepted()} is false when it could not be applied
   */
  public static CombatSessionEvent resumePatrolAt(
      CombatModule.ManagedSettings settings, BlockPos point) {
    return new CombatSessionEvent(Action.RESUME_PATROL_AT, settings, null, false, point, 0L);
  }

  /**
   * @description Requests that the session leave the head alone for a while.
   * @param millis how long to hold, ignored when not positive
   * @return the hold request
   */
  public static CombatSessionEvent holdLook(long millis) {
    return new CombatSessionEvent(Action.HOLD_LOOK, null, null, false, null, millis);
  }

  /**
   * @description Records the engine's answer to a reconfigure or patrol resume.
   * @param accepted whether the change was applied
   */
  public void setAccepted(boolean accepted) {
    this.accepted = accepted;
  }
}
