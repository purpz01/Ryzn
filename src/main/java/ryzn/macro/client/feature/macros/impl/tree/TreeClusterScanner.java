package ryzn.macro.client.feature.macros.impl.tree;

import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import ryzn.macro.client.core.RyznBootstrap;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class TreeClusterScanner {

  private static final int AREA_MIN_X = -197;
  private static final int AREA_MAX_X = -141;
  private static final int AREA_MIN_Z = -52;
  private static final int AREA_MAX_Z = 2;
  private static final int SCAN_Y_MIN = 60;
  private static final int SCAN_Y_MAX = 110;
  // Sized to sweep in about the same time as the fixed oak box (~125k cells). The old 64/48 box was
  // 1.6M cells: roughly twenty seconds per full pass at the index's per-tick read budget, and well
  // past
  // what the startup seed will cover, so every non-oak type spent most of its run with an empty or
  // stale index and behaved as if no trees existed. Trees are also never 48 blocks below you, so
  // the
  // vertical band is asymmetric rather than centred.
  private static final int NON_OAK_SCAN_RADIUS = 48;
  private static final int NON_OAK_SCAN_Y_DOWN = 8;
  private static final int NON_OAK_SCAN_Y_UP = 24;

  private static final int ROUTE_SCAN_XZ_PAD = 4;
  private static final int ROUTE_SCAN_Y_DOWN = 4;
  private static final int ROUTE_SCAN_Y_UP = 24;

  private final TreeChopAvoidance avoidance;
  private final TreeTrunkColumns trunkColumns;
  private final TreeLogTargeting targeting;

  LogCluster buildRouteCluster(Level level, BlockPos trunk) {
    TreeLogType type = RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).chopLogType();
    int lowY = Math.max(level.getMinY(), trunk.getY() - ROUTE_SCAN_Y_DOWN);
    int highY = Math.min(level.getMaxY(), trunk.getY() + ROUTE_SCAN_Y_UP);
    List<LogCluster> clusters =
        LogScanner.scan(
            level,
            trunk.getX() - ROUTE_SCAN_XZ_PAD,
            trunk.getX() + ROUTE_SCAN_XZ_PAD,
            trunk.getZ() - ROUTE_SCAN_XZ_PAD,
            trunk.getZ() + ROUTE_SCAN_XZ_PAD,
            lowY,
            highY,
            1,
            trunk.getX() + 0.5,
            trunk.getY() + 0.5,
            trunk.getZ() + 0.5,
            type);
    LogCluster best = null;
    double bestScore = Double.MAX_VALUE;
    for (LogCluster cluster : clusters) {
      double score = cluster.base().distSqr(trunk);
      if (trunkColumns.containsColumn(cluster, trunk)) {
        score -= 1.0E6;
      }
      if (score < bestScore) {
        bestScore = score;
        best = cluster;
      }
    }
    return best;
  }

  BlockPos routeTrunkRoot(LogCluster cluster, BlockPos trunk) {
    return trunkColumns.rootInColumn(cluster, trunk, TreeIndex.TRUNK_COLUMN_MIN_LOGS);
  }

  boolean targetStillDense(
      LocalPlayer player, Level level, BlockPos targetLog, List<BlockPos> blocks) {
    if (!targeting.isSafeLog(targetLog)) {
      return false;
    }
    if (!blocks.isEmpty()) {
      for (BlockPos log : blocks) {
        if (targeting.isSafeLog(log) && targeting.isSelectedLog(level, log)) {
          return true;
        }
      }
      return false;
    }
    int[] bounds = boundsFor(player, level);
    return LogScanner.isClusterStillDense(
        level,
        targetLog,
        bounds[0],
        bounds[1],
        bounds[2],
        bounds[3],
        bounds[4],
        bounds[5],
        RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).chopMinDensity(),
        RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).chopLogType());
  }

  /**
   * True when most of the tree we set off for has disappeared, i.e. somebody else is felling it.
   *
   * <p>{@link #targetStillDense} only asks whether ONE log survives, which is the right question
   * once we are standing at the trunk mid-chop but the wrong one while walking: a Treecapitator hit
   * by another player leaves a stump or a stray branch log, and the bot happily finished the walk
   * to it. Measured against the snapshot taken when the tree was picked, so it scales with the tree
   * instead of the density setting.
   */
  boolean routeTargetMostlyGone(Level level, List<BlockPos> snapshot) {
    if (level == null || snapshot == null || snapshot.isEmpty()) {
      return false;
    }
    int live = 0;
    final int needed = (snapshot.size() + 1) / 2;
    for (BlockPos log : snapshot) {
      if (targeting.isSafeLog(log) && targeting.isSelectedLog(level, log) && ++live >= needed) {
        return false;
      }
    }
    return true;
  }

  int[] boundsFor(LocalPlayer player, Level level) {
    TreeLogType type = RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).chopLogType();
    if (type.isOak()) {
      int lowY = Math.max(level.getMinY(), Math.max(SCAN_Y_MIN, LogScanner.MIN_TREE_Y));
      int highY = Math.min(level.getMaxY(), SCAN_Y_MAX);
      return new int[] {AREA_MIN_X, AREA_MAX_X, AREA_MIN_Z, AREA_MAX_Z, lowY, highY};
    }
    int x = Mth.floor(player.getX());
    int y = Mth.floor(player.getY());
    int z = Mth.floor(player.getZ());
    int lowY = Math.max(level.getMinY(), y - NON_OAK_SCAN_Y_DOWN);
    int highY = Math.min(level.getMaxY(), y + NON_OAK_SCAN_Y_UP);
    return new int[] {
      x - NON_OAK_SCAN_RADIUS,
      x + NON_OAK_SCAN_RADIUS,
      z - NON_OAK_SCAN_RADIUS,
      z + NON_OAK_SCAN_RADIUS,
      lowY,
      highY
    };
  }
}
