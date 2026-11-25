package ryzn.macro.client.feature.failsafe;

import ryzn.macro.client.feature.failsafe.api.Failsafe;

/** Owns the expected-world-change window across disconnect and server transitions. */
public final class WorldChangeFailsafe extends Failsafe {
  private static final long WARP_GRACE_MS = 15_000L;
  private long intentionalWorldChangeUntilMs;

  /**
   * Creates the world-change detector with stable failsafe identity.
   *
   * @description Registers the detector without resetting the existing grace policy.
   */
  public WorldChangeFailsafe() {
    super("world_change", "World Change", "Detects unexpected server instance changes.");
  }

  /**
   * Grants the existing wall-clock grace period for an intentional warp.
   *
   * @description Uses wall time so a connection reset does not erase the expected transition.
   */
  void expect() {
    intentionalWorldChangeUntilMs = System.currentTimeMillis() + WARP_GRACE_MS;
  }

  /**
   * Checks whether the new instance belongs to an intentional warp.
   *
   * @description Keeps the window available for multi-edge server transitions.
   * @return true while the current wall-clock time is inside the expected window
   */
  boolean isExpected() {
    return System.currentTimeMillis() <= intentionalWorldChangeUntilMs;
  }
}
