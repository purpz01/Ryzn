package ryzn.macro.client.feature.pathfinder.path;

import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.modules.impl.combat.CombatModule;

/**
 * Guards the strafe while W carries the body at the node the head is aimed at.
 *
 * <p>The normal bearing strafe is left alone. This only steps in when drift becomes a problem: far
 * enough off that the route no longer describes the ground under the body, or heading toward a drop
 * or a raised block the route went around (falling off, or an auto-jump the route never planned).
 * Then it holds the correcting strafe until the body is back near the line. It also drops a bearing
 * strafe that would carry the body into such a spot. Chasing the line itself was tried and read as
 * a constant side-to-side shake.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class PathLaneKeeper {

  /** Ticks of measured side velocity added to the offset before deciding. */
  private static final double LOOKAHEAD_TICKS = 4.0;

  /** Predicted side offset that starts a correction on open ground. */
  private static final double FREE_DRIFT_BLOCKS = 0.8;

  /** Predicted side offset that starts a correction when the drift side is a drop or a wall. */
  private static final double HAZARD_DRIFT_BLOCKS = 0.3;

  /** Predicted side offset under which a correction is released. Short of the line on purpose. */
  private static final double RELEASE_BLOCKS = 0.2;

  /** Side and forward reach of the hazard probe, from the body centre. */
  private static final double HAZARD_SIDE_REACH = 0.7;

  private static final double HAZARD_AHEAD_REACH = 0.5;

  /** Strafe must move the body at least this much across the line to be worth pressing. */
  private static final double MIN_STRAFE_AUTHORITY = 0.35;

  private static final double SIDE_PROBE_RANGE = 0.42;

  /** Rise a walking body takes without a hop. */
  private static final double STEP_UP = 0.6;

  /** Cosine of the head-to-segment angle (25 deg) past which the body counts as turning. */
  private static final double TURN_COS = Math.cos(Math.toRadians(25.0));

  private static final double STRIDE_LOOKAHEAD_TICKS = 3.0;
  private static final double MOMENTUM_TICKS = 0.5;
  private static final double MIN_ROUTE_PROGRESS = 0.35;
  private static final double ROUTE_ALIGNMENT_WEIGHT = 0.25;
  private static final int AVOIDANCE_HOLD_TICKS = 3;

  private final Minecraft client;
  private final PathSpatialQueries spatialQueries;
  private final PathHopTerrain hopTerrain;

  /** -1 correcting with the left key, +1 with the right key, 0 idle. */
  private int holding;

  private int avoidanceSide;
  private int avoidanceTicks;

  void reset() {
    holding = 0;
    avoidanceTicks = 0;
  }

  /**
   * Replaces A/D in {@code keys} with the drift correction for the segment ending at trail index
   * {@code cursor}. Leaves the keys alone and resets when forward is not held or there is no
   * segment.
   */
  void apply(List<KeyMapping> keys, List<Vec3> trail, int cursor, boolean recordedWalk) {
    KeyMapping left = client.options.keyLeft;
    KeyMapping right = client.options.keyRight;
    if (client.player == null
        || cursor <= 0
        || cursor >= trail.size()
        || !keys.contains(client.options.keyUp)
        || keys.contains(client.options.keyDown)) {
      reset();
      return;
    }
    Vec3 from = trail.get(cursor - 1);
    Vec3 to = trail.get(cursor);
    double dirX = to.x - from.x;
    double dirZ = to.z - from.z;
    double length = Math.hypot(dirX, dirZ);
    if (length < 1.0e-3) {
      reset();
      return;
    }
    dirX /= length;
    dirZ /= length;
    if (recordedWalk && avoidBlockedStride(keys, trail, cursor, dirX, dirZ)) {
      holding = 0;
      return;
    }
    if (!recordedWalk) {
      avoidanceTicks = 0;
    }
    double normalX = -dirZ;
    double normalZ = dirX;
    double offset =
        (client.player.getX() - from.x) * normalX + (client.player.getZ() - from.z) * normalZ;
    Vec3 velocity = client.player.getDeltaMovement();
    double predicted =
        offset + (velocity.x * normalX + velocity.z * normalZ) * driftLookaheadTicks();

    // Vanilla moves the left key along (cos yaw, sin yaw).
    double yaw = Math.toRadians(client.player.getYRot());
    double leftAcross = Math.cos(yaw) * normalX + Math.sin(yaw) * normalZ;
    if (Math.abs(leftAcross) < MIN_STRAFE_AUTHORITY) {
      holding = 0;
      return;
    }

    double drift = Math.abs(predicted);
    // Mid-turn the body is off the new line by design and the head is still coming round; a held
    // correction then adds to the overshoot and makes the loop back onto the path.
    boolean turning = -Math.sin(yaw) * dirX + Math.cos(yaw) * dirZ < TURN_COS;
    int wanted = holding;
    if (turning) {
      wanted = 0;
    } else if (holding == 0) {
      double side = Math.signum(predicted);
      double engage =
          drift > HAZARD_DRIFT_BLOCKS
                  && hazardAt(
                      client.player.getX()
                          + normalX * side * HAZARD_SIDE_REACH
                          + dirX * HAZARD_AHEAD_REACH,
                      client.player.getZ()
                          + normalZ * side * HAZARD_SIDE_REACH
                          + dirZ * HAZARD_AHEAD_REACH)
              ? HAZARD_DRIFT_BLOCKS
              : FREE_DRIFT_BLOCKS;
      if (drift > engage) {
        wanted = -predicted * leftAcross > 0 ? -1 : 1;
      }
    } else if (drift < RELEASE_BLOCKS
        || Math.signum(predicted) * Math.signum(leftAcross) == -holding) {
      wanted = 0;
    }
    if (wanted == -1 && spatialQueries.blockedAtYawOffset(90f, SIDE_PROBE_RANGE)
        || wanted == 1 && spatialQueries.blockedAtYawOffset(-90f, SIDE_PROBE_RANGE)) {
      wanted = 0;
    }
    holding = wanted;

    if (holding != 0) {
      keys.remove(left);
      keys.remove(right);
      keys.add(holding == -1 ? left : right);
      return;
    }
    // No correction running: keep the normal bearing strafe, minus one that walks off an edge or
    // into a block the route went around.
    double leftX = Math.cos(yaw);
    double leftZ = Math.sin(yaw);
    if (keys.contains(left) && strafeHazard(leftX, leftZ, dirX, dirZ)) {
      keys.remove(left);
    }
    if (keys.contains(right) && strafeHazard(-leftX, -leftZ, dirX, dirZ)) {
      keys.remove(right);
    }
  }

  private double driftLookaheadTicks() {
    CombatModule combat = RyznBootstrap.moduleManager().getByClass(CombatModule.class);
    if (!client.player.onGround()
        || !combat.isCombatNavActive()
        || !combat.usesRecordedMovement()) {
      return LOOKAHEAD_TICKS;
    }
    double drag =
        client
                .level
                .getBlockState(client.player.getBlockPosBelowThatAffectsMyMovement())
                .getBlock()
                .getFriction()
            * FallPredictor.HORIZONTAL_DRAG;
    // Ground drift decays geometrically: the rest of a coast is v / (1 - drag), not 4 v.
    return Math.min(LOOKAHEAD_TICKS, 1.0 / (1.0 - drag));
  }

  private boolean avoidBlockedStride(
      List<KeyMapping> keys, List<Vec3> trail, int cursor, double dirX, double dirZ) {
    double yaw = Math.toRadians(client.player.getYRot());
    double forwardX = -Math.sin(yaw);
    double forwardZ = Math.cos(yaw);
    double leftX = Math.cos(yaw);
    double leftZ = Math.sin(yaw);
    int current =
        keys.contains(client.options.keyLeft) ? -1 : keys.contains(client.options.keyRight) ? 1 : 0;
    Vec3 velocity = client.player.getDeltaMovement();
    double reach =
        Math.max(
            client.player.getBbWidth(),
            Math.hypot(velocity.x, velocity.z) * STRIDE_LOOKAHEAD_TICKS);
    double currentDistance =
        routeDistance(client.player.getX(), client.player.getZ(), trail, cursor);
    double bestScore = Double.POSITIVE_INFINITY;
    int best = current;
    boolean blocked = false;
    for (int side : new int[] {current, 0, -1, 1}) {
      double x = forwardX - side * leftX;
      double z = forwardZ - side * leftZ;
      double norm = Math.hypot(x, z);
      double toX = client.player.getX() + x / norm * reach + velocity.x * MOMENTUM_TICKS;
      double toZ = client.player.getZ() + z / norm * reach + velocity.z * MOMENTUM_TICKS;
      boolean walkable = hopTerrain.safeWalkingLine(toX, toZ);
      if (side == current && !blocked) {
        if (walkable && avoidanceTicks == 0) {
          return false;
        }
        blocked = true;
      }
      if (!walkable || (x * dirX + z * dirZ) / norm < MIN_ROUTE_PROGRESS) {
        continue;
      }
      double distance = routeDistance(toX, toZ, trail, cursor);
      double clearance = FREE_DRIFT_BLOCKS;
      if (hopTerrain.footprintSurfaceY(toX, toZ, client.player.getY())
          > client.player.getY() + 1.0e-3) {
        clearance += client.player.getBbWidth();
      }
      if (distance > Math.max(clearance, currentDistance)) {
        continue;
      }
      double score = distance + ROUTE_ALIGNMENT_WEIGHT * (1.0 - (x * dirX + z * dirZ) / norm);
      if (avoidanceTicks > 0 && side == avoidanceSide) {
        score -= ROUTE_ALIGNMENT_WEIGHT;
      }
      if (score < bestScore) {
        bestScore = score;
        best = side;
      }
    }
    if (bestScore == Double.POSITIVE_INFINITY) {
      avoidanceTicks = 0;
      return false;
    }
    if (best != avoidanceSide || avoidanceTicks == 0) {
      avoidanceSide = best;
      avoidanceTicks = AVOIDANCE_HOLD_TICKS - 1;
    } else {
      avoidanceTicks--;
    }
    keys.remove(client.options.keyLeft);
    keys.remove(client.options.keyRight);
    if (best != 0) {
      keys.add(best == -1 ? client.options.keyLeft : client.options.keyRight);
    }
    return true;
  }

  private static double routeDistance(double x, double z, List<Vec3> trail, int cursor) {
    double best = Double.POSITIVE_INFINITY;
    for (int index = Math.max(1, cursor - 1); index < Math.min(trail.size(), cursor + 4); index++) {
      Vec3 from = trail.get(index - 1);
      Vec3 to = trail.get(index);
      double dx = to.x - from.x;
      double dz = to.z - from.z;
      double lengthSquared = dx * dx + dz * dz;
      double fraction =
          lengthSquared < 1.0e-6
              ? 0.0
              : Math.clamp(((x - from.x) * dx + (z - from.z) * dz) / lengthSquared, 0.0, 1.0);
      best = Math.min(best, Math.hypot(x - from.x - dx * fraction, z - from.z - dz * fraction));
    }
    return best;
  }

  private boolean strafeHazard(double sideX, double sideZ, double dirX, double dirZ) {
    return hazardAt(
        client.player.getX() + sideX * HAZARD_SIDE_REACH + dirX * HAZARD_AHEAD_REACH,
        client.player.getZ() + sideZ * HAZARD_SIDE_REACH + dirZ * HAZARD_AHEAD_REACH);
  }

  /** A drop of two or more blocks, or a block at feet height the body would have to hop. */
  private boolean hazardAt(double x, double z) {
    int blockX = (int) Math.floor(x);
    int blockZ = (int) Math.floor(z);
    int feetY = (int) Math.floor(client.player.getY() + 1.0e-3);
    BlockPos feet = new BlockPos(blockX, feetY, blockZ);
    if (!WalkCheck.canWalkThrough(feet)) {
      // A slab or stair the body steps up is not a wall. Counting it as one kept the keeper
      // correcting away from every step on a staircase: 35% of climbing ticks strafed, 11% on flat.
      return WalkCheck.getCollisionTopY(feet) > client.player.getY() + STEP_UP;
    }
    return !WalkCheck.canStandOn(new BlockPos(blockX, feetY - 1, blockZ))
        && !WalkCheck.canStandOn(new BlockPos(blockX, feetY - 2, blockZ));
  }
}
