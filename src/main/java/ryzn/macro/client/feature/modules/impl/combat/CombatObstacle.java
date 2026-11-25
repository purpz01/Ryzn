package ryzn.macro.client.feature.modules.impl.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Works out what is actually in the way, and picks the escape that fits it.
 *
 * <p>The old behaviour was to hop at anything it bumped into. A hop is the right answer to a step
 * and does nothing at all for a pillar: the bot lands still touching it and hops again. The first
 * version of this fix hopped, noticed the hop had failed, then shrugged sideways in an alternating
 * direction, which is still guessing - it just guesses later.
 *
 * <p>This looks instead. Three collision probes answer the question outright:
 *
 * <ul>
 *   <li>is the way forward blocked at all,
 *   <li>is it still blocked one block higher (a step is not, a wall is),
 *   <li>is there room to either side.
 * </ul>
 *
 * A step gets a hop. A wall gets a sidestep, toward whichever side is genuinely open, preferring
 * the side the mob is on. A step that also needs sliding round a corner gets both. Nothing open
 * gets neither, and the approach decision falls back to the pathfinder.
 */
final class CombatObstacle {

  /**
   * How far ahead to probe. A little over half the player's width, so it reads contact rather than
   * proximity.
   */
  private static final double PROBE_FORWARD = 0.6;

  /** Sideways probe distance, matched to the forward one so a diagonal gap reads as passable. */
  private static final double PROBE_SIDE = 0.6;

  /**
   * Height a hop clears. Probing the forward box raised by this says "is this a step or a wall".
   */
  private static final double PROBE_JUMP_UP = 1.0;

  /** Fallback step height if the player reports none; vanilla players auto-step 0.6 blocks. */
  private static final double DEFAULT_AUTO_STEP = 0.6;

  /**
   * Nudge above the true step height, so a block exactly at the limit is not probed as borderline.
   */
  private static final double AUTO_STEP_MARGIN = 0.05;

  private CombatObstacle() {}

  /**
   * What to do about the obstruction: hop, slide sideways (-1 left, +1 right), or both, or neither.
   */
  record Escape(boolean hop, int sidestep) {
    static final Escape NONE = new Escape(false, 0);

    boolean any() {
      return hop || sidestep != 0;
    }
  }

  /**
   * Pure decision, so the whole table is testable without a world.
   *
   * @param forwardBlocked something is against the body at walking height
   * @param autoStepClear the way is free at the height vanilla steps up on its own, so no jump is
   *     needed
   * @param jumpClear the way is free at jump height, which makes it a step rather than a wall
   * @param leftClear room to slide left
   * @param rightClear room to slide right
   * @param preferSide which way the mob lies: -1 left, +1 right, 0 no preference
   */
  static Escape choose(
      boolean forwardBlocked,
      boolean autoStepClear,
      boolean jumpClear,
      boolean leftClear,
      boolean rightClear,
      int preferSide) {
    if (!forwardBlocked) {
      return Escape.NONE;
    }

    // Low enough that Minecraft lifts you over it by itself. Candles, lamp edges, carpet, the lip
    // of a
    // slab, a stair tread. Jumping these is the single biggest source of pointless hopping: the bot
    // looks
    // like it is bouncing over decoration, and it loses speed doing it. Walk on.
    if (autoStepClear) {
      return Escape.NONE;
    }

    final int side = openSide(leftClear, rightClear, preferSide);
    // Above auto-step but under jump height: a real step, and hopping is the whole answer. Sliding
    // sideways as well would walk us off the line to the mob.
    if (jumpClear) {
      // ... unless the step is also a corner we are catching on, in which case do both.
      return new Escape(true, leftClear != rightClear ? side : 0);
    }
    // A wall. Hopping cannot help, so the only way past is around.
    return side == 0 ? Escape.NONE : new Escape(false, side);
  }

  private static int openSide(boolean leftClear, boolean rightClear, int preferSide) {
    if (preferSide > 0 && rightClear) {
      return 1;
    }
    if (preferSide < 0 && leftClear) {
      return -1;
    }
    if (rightClear) {
      return 1;
    }
    if (leftClear) {
      return -1;
    }
    return 0;
  }

  /**
   * Probe the live world around the player and decide. Reads block collision only, so nothing is
   * sent to the server.
   *
   * @param preferSide which way the mob lies relative to the facing: -1 left, +1 right, 0 unknown
   */
  static Escape probe(LocalPlayer player, int preferSide) {
    if (player.level() == null) {
      return Escape.NONE;
    }
    if (!blocked(player, PROBE_FORWARD, 0.0, 0.0)) {
      return Escape.NONE;
    }

    // Ask the game how high it lifts this player unaided rather than assuming, since step height is
    // a
    // property of the entity and can be modified.
    double autoStep = player.maxUpStep();
    if (autoStep <= 0.0) {
      autoStep = DEFAULT_AUTO_STEP;
    }

    final boolean autoStepClear = !blocked(player, PROBE_FORWARD, 0.0, autoStep + AUTO_STEP_MARGIN);
    if (autoStepClear) {
      return Escape.NONE;
    } // vanilla walks over it; no need to look sideways at all

    return choose(
        true,
        false,
        !blocked(player, PROBE_FORWARD, 0.0, PROBE_JUMP_UP),
        !blocked(player, 0.0, -PROBE_SIDE, 0.0),
        !blocked(player, 0.0, PROBE_SIDE, 0.0),
        preferSide);
  }

  /** True when moving the player's box by this look-relative offset would collide with blocks. */
  private static boolean blocked(LocalPlayer player, double forward, double strafe, double up) {
    final Vec3 look = player.getLookAngle();
    final double horizontal = Math.hypot(look.x, look.z);
    if (horizontal < 1.0e-6) {
      return true;
    }
    final double forwardX = look.x / horizontal;
    final double forwardZ = look.z / horizontal;
    final double moveX = forwardX * forward - forwardZ * strafe;
    final double moveZ = forwardZ * forward + forwardX * strafe;
    return !player.level().noCollision(player, player.getBoundingBox().move(moveX, up, moveZ));
  }
}
