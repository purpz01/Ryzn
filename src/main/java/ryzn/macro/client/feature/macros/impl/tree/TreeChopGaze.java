package ryzn.macro.client.feature.macros.impl.tree;

import lombok.AccessLevel;
import lombok.Getter;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;

/**
 * Who holds the head while a route walks the body to the next tree.
 *
 * <p>The path executor normally owns the head and gazes down the route, so the bot used to walk to
 * a tree facing the path and only turn onto the trunk once it stopped. This is the same handover
 * the Chillshot hunt uses on a mob: the executor yields ONLY the head and keeps steering the body
 * by strafe, so the bot walks its route and watches the tree at the same time.
 *
 * <p>The two sight counters are deliberately asymmetric - quick to look at something in plain
 * sight, slow to look away. Symmetric thresholds are what make a bot flick between the path and the
 * target every time a trunk crosses the line, and a second of blocked view is a tree in the way,
 * not a tree that vanished.
 */
final class TreeChopGaze {

  private static final int SIGHT_LOCK_ON_TICKS = 3;
  private static final int SIGHT_LOCK_OFF_TICKS = 20;

  /** Watch the tree only once this close; hold the watch until it gets this far away. */
  private static final double TAKE_RANGE = 16.0;

  private static final double RELEASE_RANGE = 22.0;

  /**
   * How far the route may bend away from the tree before the head goes back on the path.
   *
   * <p>This was 100, chosen against vanilla's S cone alone. That ignored the three executor gates
   * that all measure the SAME angle, and every one of them bites first:
   *
   * <ul>
   *   <li>the hop gate needs faceGap &lt;= 45 (90 for a step-up or vault), so above that jumps
   *       never fire;
   *   <li>{@code PathMovementController} cuts the backstep allowance from 10 ticks to 2 while a
   *       macro owns the head, so the key set empties instead of blending through the turn;
   *   <li>the 135-degree facing gate clears every key, and normally recovers when the head swings
   *       back - which it never does while the gaze is holding it, so the body simply stops.
   * </ul>
   *
   * 40 sits under the strictest of those with margin, which keeps the route resolving onto W (plus
   * at most one strafe) instead of onto S. Do not raise it without re-checking HOP_FACING_GATE_DEG.
   */
  private static final float BACKSTEP_LIMIT_DEGREES = 40f;

  /**
   * Re-take only when the TREE sits near the route heading. Handing the head back turns the body
   * onto the route, so a body-relative re-take is true again the very next tick: head swings to the
   * tree, the gap re-opens, the head goes back on the path, and the two trade it every few ticks.
   * That is the stutter. The bearing to the tree does not move when the body turns, so it settles
   * the argument once.
   *
   * <p>Kept well inside {@link #BACKSTEP_LIMIT_DEGREES} so the gaze only ever takes the head for a
   * tree that is already roughly where the body is going - watching something ahead of you, not
   * marching sideways at it.
   */
  private static final float REACQUIRE_LIMIT_DEGREES = 25f;

  /**
   * Aim up the trunk rather than at its foot - walking with the camera on the floor is its own
   * tell.
   */
  private static final double AIM_HEIGHT_ABOVE_ROOT = 2.5;

  @Getter(AccessLevel.PACKAGE)
  private boolean owned;

  private int sightTicks;
  private int blindTicks;

  /**
   * Decide who holds the head this tick. Returns true while the macro should be aiming at {@code
   * trunk}.
   */
  boolean update(LocalPlayer player, Level level, BlockPos trunk) {
    if (player == null
        || level == null
        || trunk == null
        || routeWouldBackstep(player, trunk, owned)) {
      release();
      return false;
    }
    final double range = owned ? RELEASE_RANGE : TAKE_RANGE;
    if (player.position().distanceToSqr(aimPoint(trunk)) > range * range) {
      release();
      return false;
    }
    if (canSee(player, level, trunk)) {
      blindTicks = 0;
      if (++sightTicks >= SIGHT_LOCK_ON_TICKS) {
        owned = true;
      }
    } else {
      sightTicks = 0;
      if (owned && ++blindTicks >= SIGHT_LOCK_OFF_TICKS) {
        release();
      }
    }
    return owned;
  }

  void release() {
    owned = false;
    sightTicks = 0;
    blindTicks = 0;
  }

  static Vec3 aimPoint(BlockPos trunk) {
    return new Vec3(trunk.getX() + 0.5, trunk.getY() + AIM_HEIGHT_ABOVE_ROOT, trunk.getZ() + 0.5);
  }

  /**
   * Any tree block on the way counts as seeing the tree - the canopy in front of a trunk is still
   * the forest.
   */
  private static boolean canSee(LocalPlayer player, Level level, BlockPos trunk) {
    final Vec3 eye = player.getEyePosition();
    final BlockHitResult hit =
        level.clip(
            new ClipContext(
                eye, aimPoint(trunk), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
    if (hit.getType() == HitResult.Type.MISS) {
      return true;
    }
    final BlockPos blocker = hit.getBlockPos();
    if (blocker.getX() == trunk.getX() && blocker.getZ() == trunk.getZ()) {
      return true;
    }
    final BlockState state = level.getBlockState(blocker);
    // Leaves collide, so walking through a forest the ray lands on somebody's canopy on most ticks.
    // Counting
    // that as "cannot see the tree" pinned sightTicks at 0, and the gaze needs SIGHT_LOCK_ON_TICKS
    // in a ROW
    // to latch - so it mostly never latched, and each brief latch restarted the ease from a
    // standstill.
    // That stop-start is what read as the head turning slowly on the way to a tree.
    return state.is(BlockTags.LEAVES)
        || LogScanner.isLog(
            state, RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).chopLogType());
  }

  /**
   * Would holding the head on this tree make the route walk backwards? See {@link
   * #BACKSTEP_LIMIT_DEGREES}.
   */
  private static boolean routeWouldBackstep(LocalPlayer player, BlockPos trunk, boolean owned) {
    final float routeYaw = PathfindCommand.routeHeadingYaw();
    if (Float.isNaN(routeYaw)) {
      return true;
    }
    // Release is measured against the BODY, like the executor's own move error: while the head is
    // parked
    // (occluded aim point) the bearing to the tree and the facing diverge, and testing the bearing
    // would
    // keep the gaze on a tree the body is walking away from. Re-taking uses the bearing - see
    // REACQUIRE_LIMIT_DEGREES.
    final float reference = owned ? player.getYRot() : bearingYaw(player, trunk);
    return conflictsWithRoute(routeYaw, reference, owned);
  }

  /** Pure half of {@link #routeWouldBackstep}, split out so the hysteresis is testable. */
  static boolean conflictsWithRoute(float routeYaw, float referenceYaw, boolean owned) {
    final float limit = owned ? BACKSTEP_LIMIT_DEGREES : REACQUIRE_LIMIT_DEGREES;
    return Math.abs(Mth.wrapDegrees(routeYaw - referenceYaw)) > limit;
  }

  private static float bearingYaw(LocalPlayer player, BlockPos trunk) {
    final Vec3 aim = aimPoint(trunk);
    return (float)
        (Math.toDegrees(Math.atan2(aim.z - player.getZ(), aim.x - player.getX())) - 90.0);
  }
}
