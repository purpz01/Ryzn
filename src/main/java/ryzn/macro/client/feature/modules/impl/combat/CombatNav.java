package ryzn.macro.client.feature.modules.impl.combat;

import lombok.AccessLevel;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.feature.pathfinder.path.WalkCheck;
import ryzn.macro.client.util.world.MacroWorld;
import ryzn.swift.nativepath.NativeStateEncoder;
import ryzn.swift.nativepath.NativeVoxelFlags;

/**
 * Combat's side of the pathfinder: who owns the active route, where it was last planned to, and how
 * often it may be replanned. There is one route, so there is one of these.
 *
 * <p>Chasing a mob and roaming to a remembered spot are separate destinations but the same route,
 * so they are {@link Slot}s of one plan rather than two fields. The old pair shared a cooldown and
 * got tangled: releasing the roam also cleared the target's planned block, and each caller had to
 * remember to null the other side. Switching slot now simply forces the next plan through.
 *
 * <p>Every replan is a hot swap: the body keeps flowing along the current route until the new one
 * resolves, instead of releasing the movement keys while it computes.
 */
final class CombatNav {
  private static final int GROUND_SEARCH_DEPTH = 12;

  /** Which destination the current plan is for. */
  enum Slot {
    TARGET,
    ROAM
  }

  /** True while combat owns the active route (the executor is driving the body for us). */
  @Getter(AccessLevel.PACKAGE)
  private boolean owned; // this module started the active pathfinder route

  private int repathCooldown; // ticks before the next allowed replan
  private Slot slot;
  private BlockPos plannedPos; // block the live plan was made toward

  /** Finds a supported feet cell near a moving mob or remembered position. */
  static BlockPos groundDestination(BlockPos requested) {
    ClientLevel level = Minecraft.getInstance().level;
    if (level == null) {
      return null;
    }
    if (level.hasChunkAt(requested) && isStandableGoal(level, requested)) {
      return requested;
    }
    BlockPos best = null;
    double bestDistance = Double.POSITIVE_INFINITY;
    for (int offsetX = -1; offsetX <= 1; offsetX++) {
      for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
        BlockPos column = requested.offset(offsetX, 0, offsetZ);
        if (!level.hasChunkAt(column)) {
          continue;
        }
        for (int drop = 0; drop <= GROUND_SEARCH_DEPTH; drop++) {
          BlockPos support = column.below(drop);
          if (!MacroWorld.isSolid(level, support)) {
            continue;
          }
          BlockPos feet = support.above();
          if (isStandableGoal(level, feet)) {
            double distance = feet.distSqr(requested);
            if (distance < bestDistance) {
              best = feet;
              bestDistance = distance;
            }
          }
          break;
        }
      }
    }
    return best;
  }

  private static boolean isStandableGoal(ClientLevel level, BlockPos feet) {
    if (!MacroWorld.isStandable(level, feet) || !WalkCheck.canStandOn(feet.below())) {
      return false;
    }
    for (int offset = -1; offset <= 1; offset++) {
      BlockPos position = feet.offset(0, offset, 0);
      if (WalkCheck.avoidWalkingInto(position)
          || NativeVoxelFlags.isDanger(
              NativeStateEncoder.codeForState(level.getBlockState(position)))) {
        return false;
      }
    }
    return true;
  }

  void tickCooldown() {
    if (repathCooldown > 0) {
      repathCooldown--;
    }
  }

  /**
   * Plan toward {@code dest} for one slot, if the plan is stale: a different slot, no plan yet, the
   * destination has drifted {@code moveThreshold} blocks, or the route has ended / failed. The idle
   * check is load-bearing - a route that ends at the mob's old block would otherwise never refire
   * while the mob sits just inside the drift threshold, and the bot stands still.
   */
  void planTo(Slot destSlot, BlockPos dest, int moveThreshold, int cooldownTicks) {
    final boolean stale =
        slot != destSlot
            || plannedPos == null
            || plannedPos.distSqr(dest) >= (long) moveThreshold * moveThreshold
            || !WalkingNavigation.isNavigationActive();
    if (!stale || repathCooldown > 0) {
      return;
    }
    if (WalkingNavigation.startToHotSwap(dest.getX(), dest.getY(), dest.getZ())) {
      owned = true;
      slot = destSlot;
      plannedPos = dest;
      repathCooldown = cooldownTicks;
    }
  }

  /**
   * Forget where we planned to, so the next tick plans again. The live route keeps running until
   * then.
   */
  void forgetPlan() {
    plannedPos = null;
  }

  /** Stall recovery: drop the stale plan AND its cooldown so the next tick replans immediately. */
  void replanNow() {
    plannedPos = null;
    repathCooldown = 0;
  }

  /** Hand the body back: stop our route (if we own it) and drop the plan. */
  void release() {
    plannedPos = null;
    if (!owned) {
      return;
    }
    WalkingNavigation.stopNavQuiet();
    owned = false;
  }

  /**
   * Full teardown, cooldown included - a stale hold used to outlive a target loss and throttle the
   * first tick back.
   */
  void reset() {
    release();
    slot = null;
    repathCooldown = 0;
  }
}
