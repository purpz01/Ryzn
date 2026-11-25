package ryzn.macro.client.feature.macros.impl.tree;

import java.util.List;
import java.util.function.Predicate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/** Owns the currently selected tree cluster, approach stand, and last broken log. */
@Accessors(fluent = true)
final class TreeClusterTarget {
  private static final double DIRECT_DRIVE_RADIUS = 2.25;
  private static final double AT_STAND_Y_TOLERANCE = 0.35;
  private static final double CLOSE_TO_STAND_Y_TOLERANCE = 1.25;
  private static final double STEP_JUMP_MIN_Y_DELTA = 0.45;

  @Getter(AccessLevel.PACKAGE)
  private BlockPos base;

  @Getter(AccessLevel.PACKAGE)
  private BlockPos stand;

  @Getter(AccessLevel.PACKAGE)
  private List<BlockPos> blocks = List.of();

  @Getter(AccessLevel.PACKAGE)
  private BlockPos lastBrokenLog;

  private List<BlockPos> approachLogs = List.of();

  void select(BlockPos base, BlockPos stand, List<BlockPos> blocks) {
    this.base = base;
    this.stand = stand;
    this.blocks = List.copyOf(blocks);
    approachLogs = this.blocks;
    lastBrokenLog = null;
  }

  void snapshotApproach(Predicate<BlockPos> isLive) {
    approachLogs = blocks.stream().filter(isLive).toList();
  }

  boolean lostApproachLog(Predicate<BlockPos> isMissing) {
    for (BlockPos log : approachLogs) {
      if (isMissing.test(log)) {
        return true;
      }
    }
    return false;
  }

  boolean withinOneBlockOfTrunk(double x, double z, Predicate<BlockPos> isLive) {
    if (base == null) {
      return false;
    }
    for (BlockPos log : blocks) {
      if (log.getY() > base.getY() || log.getY() < base.getY() - 2 || !isLive.test(log)) {
        continue;
      }
      double dx = Math.max(Math.max(log.getX() - x, x - log.getX() - 1), 0);
      double dz = Math.max(Math.max(log.getZ() - z, z - log.getZ() - 1), 0);
      if (dx * dx + dz * dz <= 1.0) {
        return true;
      }
    }
    return false;
  }

  void moveStand(BlockPos stand) {
    this.stand = stand;
  }

  boolean playerIsAtStand(LocalPlayer player) {
    return player != null
        && stand != null
        && Mth.floor(player.getX()) == stand.getX()
        && Mth.floor(player.getZ()) == stand.getZ()
        && isAtStandHeight(player.getY(), stand.getY());
  }

  boolean playerIsCloseToStand(LocalPlayer player) {
    if (player == null || stand == null) {
      return false;
    }
    if (!canCloseToStandHeight(player.getY(), stand.getY())) {
      return false;
    }
    double dx = (stand.getX() + 0.5) - player.getX();
    double dz = (stand.getZ() + 0.5) - player.getZ();
    return dx * dx + dz * dz <= DIRECT_DRIVE_RADIUS * DIRECT_DRIVE_RADIUS;
  }

  static boolean isAtStandHeight(double playerY, int standY) {
    return Math.abs(playerY - standY) <= AT_STAND_Y_TOLERANCE;
  }

  static boolean canCloseToStandHeight(double playerY, int standY) {
    return Math.abs(playerY - standY) <= CLOSE_TO_STAND_Y_TOLERANCE;
  }

  static boolean needsStepJump(double playerY, int standY) {
    double delta = standY - playerY;
    return delta > STEP_JUMP_MIN_Y_DELTA && delta <= CLOSE_TO_STAND_Y_TOLERANCE;
  }

  void recordBrokenLog(BlockPos log) {
    lastBrokenLog = log;
    approachLogs = approachLogs.stream().filter(candidate -> !candidate.equals(log)).toList();
  }

  void clear() {
    base = null;
    stand = null;
    blocks = List.of();
    approachLogs = List.of();
    lastBrokenLog = null;
  }
}
