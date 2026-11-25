package ryzn.macro.client.feature.pathfinder.path;

/**
 * Pure decision logic extracted from PathExecutor. Deliberately side-effect-free so it can be
 * exercised by unit tests without a live Minecraft instance.
 */
public final class MovementGate {

  private MovementGate() {}

  /**
   * Distance-aware movement gating. Close targets always allow strafing; far targets require yaw
   * alignment so the player doesn't walk pure-backwards while the rotation is still catching up.
   */
  public static boolean shouldAllowMovement(
      double distToTarget,
      float lookAlignDiffDeg,
      double closeTargetThreshold,
      float farAlignThresholdDeg) {
    if (distToTarget < closeTargetThreshold) {
      return true;
    }
    return lookAlignDiffDeg <= farAlignThresholdDeg;
  }

  /**
   * Picks the yaw alignment threshold used by the jump gate. Anti-stuck jumps get the loosest
   * threshold, ascending jumps a middle band, regular jumps the strictest.
   */
  public static float jumpAlignmentThresholdDeg(
      boolean antiStuck,
      boolean ascending,
      float jumpAlignDeg,
      float ascendingJumpAlignDeg,
      float farAlignDeg) {
    if (antiStuck) {
      return farAlignDeg;
    }
    if (ascending) {
      return ascendingJumpAlignDeg;
    }
    return jumpAlignDeg;
  }

  /**
   * True when the cursor should be held on a node the body still has to step UP onto, so a flat
   * (XZ-only) advance test cannot retire the climb while the body is still at the bottom of the
   * riser.
   *
   * <p>Bounded at both ends. The floor keeps same-level leaps out of it. The ceiling is the part
   * that matters: a step the body can make is one block, so a node further overhead than {@code
   * maxClimbableRise} was never a climb - it is a node the body has fallen away from, left behind
   * by a route planned just before a drop. Pinning there can never be satisfied, and the pin runs
   * ahead of every advance test, so the cursor stops dead on a target the body cannot reach.
   */
  public static boolean shouldPinForClimb(
      double riseToNode, double stepGateRise, double maxClimbableRise) {
    return riseToNode > stepGateRise && riseToNode <= maxClimbableRise;
  }

  /**
   * True when the body target is too close to give a usable heading and should instead be projected
   * along the route past the node. Under the deadzone the player-to-node vector is sub-block, so
   * its atan2 flips with normal body wobble and the key selector chatters every direction key at
   * once. The last node is excluded: it has no outgoing direction, and projecting past it would
   * overshoot the goal.
   */
  public static boolean shouldProjectHeading(
      double gapToTarget, int aimIndex, int trailSize, double deadzoneBlocks) {
    if (aimIndex + 1 >= trailSize) {
      return false;
    }
    return gapToTarget < deadzoneBlocks;
  }

  /**
   * True when the back key has been held long enough to be TRAVEL rather than a transition, and so
   * must be dropped. Duration is the discriminator - not distance, not angle.
   *
   * <p>Turning to something behind you genuinely passes through S. The direction cones overlap, so
   * the key set blends S -> S+A -> A -> A+W -> W as the head comes round, and clipping S out of
   * that leaves a turn that starts wrong. A few ticks of it is simply how a body changes direction.
   *
   * <p>What is not human is HOLDING it. The head clears the S cone (67.5 degrees) in roughly 2
   * ticks at the fast end of the pathfinder's turn rate and 6 at the slow end, so a run past ten
   * ticks means the head is not coming round and the body is genuinely walking backwards - the
   * moonwalk. Capping the run length keeps the natural transition and makes sustained backward
   * travel impossible, without having to guess whether a given press is a settle, air control, or a
   * real turn.
   */
  public static boolean isBackstepTravel(
      boolean wantsBack, int consecutiveTicks, int maxTransitionTicks) {
    return wantsBack && consecutiveTicks > maxTransitionTicks;
  }

  public static boolean shouldJump(
      float yawDiffDeg, int jumpCooldownTicks, boolean needsJump, float alignmentThresholdDeg) {
    if (!needsJump) {
      return false;
    }
    if (jumpCooldownTicks > 0) {
      return false;
    }
    return yawDiffDeg <= alignmentThresholdDeg;
  }

  /**
   * Optional repeated hops are only safe on ordinary travel, clear of the goal and the next real
   * rise.
   */
  public static boolean shouldHoldConstantJump(
      boolean enabled,
      boolean dryLand,
      boolean sneaking,
      boolean moving,
      double finalGap,
      double endBuffer,
      double upcomingJumpGap,
      double jumpApproachBuffer,
      boolean momentumAligned,
      boolean initialFacingAligned) {
    if (!enabled || !dryLand || sneaking || !moving || !momentumAligned || !initialFacingAligned) {
      return false;
    }
    if (finalGap <= endBuffer) {
      return false;
    }
    return Double.isNaN(upcomingJumpGap) || upcomingJumpGap > jumpApproachBuffer;
  }

  /** Once a fresh route has been faced, later turns retain the normal constant-jump behavior. */
  public static boolean initialFacingReady(
      boolean alreadyFacedRoute, float yawDiffDeg, float alignmentThresholdDeg) {
    return alreadyFacedRoute || yawDiffDeg <= alignmentThresholdDeg;
  }

  /**
   * True when current horizontal velocity is already carrying the body toward its steering target.
   */
  public static boolean momentumAligned(
      double velocityX,
      double velocityZ,
      double targetDeltaX,
      double targetDeltaZ,
      double speedFloor,
      float maxTurnDegrees) {
    double speed = Math.hypot(velocityX, velocityZ);
    double targetLength = Math.hypot(targetDeltaX, targetDeltaZ);
    if (speed <= speedFloor || targetLength < 1.0e-6) {
      return false;
    }
    double cosine =
        Math.clamp(
            (velocityX * targetDeltaX + velocityZ * targetDeltaZ) / (speed * targetLength),
            -1.0,
            1.0);
    return Math.toDegrees(Math.acos(cosine)) <= maxTurnDegrees + 1.0e-6;
  }

  /**
   * True when the one movement key allowed in the air should be a strafe.
   *
   * <p>Airborne the body gets a SINGLE key: two at once is a diagonal impulse that curves the hop,
   * which is how a mid-air nudge shoves the body off its arc or off a ledge. Forward wins whenever
   * it is held, since that is what carries a hop across a gap. With no forward there is no arc to
   * preserve, so a lone strafe is let through and a fall keeps some control instead of being fully
   * dead.
   */
  public static boolean airborneKeepsStrafe(boolean forwardHeld, boolean anyStrafeHeld) {
    return !forwardHeld && anyStrafeHeld;
  }
}
