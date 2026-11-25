package ryzn.macro.client.feature.pathfinder.path;

/**
 * How the body travels a ground route: {@link WalkMovementMode} or {@link HopMovementMode}. The
 * route, cursor, recovery and watchdogs are shared in {@link PathExecutor}; a mode owns only the
 * choices that differ between walking and constant hopping, so tuning one cannot move the other.
 */
interface GroundMovementMode {

  /** Short name for logs. */
  String label();

  /**
   * Shapes the live steering values (turn rate, look-ahead, node reach, sprint) for this mode.
   *
   * @param profile the values derived from the speed preset and measured speed
   * @return the values this mode drives with
   */
  PathSpeedProfile shape(PathSpeedProfile profile);

  /**
   * Whether to hold jump this tick for the repeated hop.
   *
   * @param safeToHop the executor's gate: dry land, moving, not sneaking, momentum on the route and
   *     clear of the goal and the next planned jump
   * @return true to hold jump
   */
  boolean holdsConstantJump(boolean safeToHop);

  /**
   * @param constantJumping the pathfinder's constant-jumping setting
   * @return the mode that setting asks for
   */
  static GroundMovementMode forSetting(boolean constantJumping) {
    return constantJumping ? HopMovementMode.INSTANCE : WalkMovementMode.INSTANCE;
  }
}
