package ryzn.macro.client.feature.macros.impl.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Finds consecutive vertical log runs and selects stable trunk roots. */
final class TreeTrunkColumns {

  List<BlockPos> roots(List<BlockPos> logs, int minimumRunLength) {
    List<BlockPos> roots = new ArrayList<>();
    for (TrunkRun run : runs(logs, minimumRunLength)) {
      roots.add(run.root());
    }
    return roots;
  }

  BlockPos rootInColumn(LogCluster cluster, BlockPos column, int minimumRunLength) {
    if (cluster == null || column == null) {
      return null;
    }
    List<BlockPos> columnLogs = new ArrayList<>();
    for (BlockPos log : cluster.blocks()) {
      if (sameColumn(log, column)) {
        columnLogs.add(log);
      }
    }
    List<BlockPos> roots = roots(columnLogs, minimumRunLength);
    return roots.isEmpty() ? null : roots.getFirst();
  }

  boolean containsColumn(LogCluster cluster, BlockPos column) {
    if (cluster == null || column == null) {
      return false;
    }
    for (BlockPos log : cluster.blocks()) {
      if (sameColumn(log, column)) {
        return true;
      }
    }
    return false;
  }

  private static List<TrunkRun> runs(List<BlockPos> logs, int minimumRunLength) {
    Map<Long, ArrayList<Integer>> heightsByColumn = new HashMap<>();
    for (BlockPos log : logs) {
      long key = columnKey(log.getX(), log.getZ());
      heightsByColumn.computeIfAbsent(key, ignored -> new ArrayList<>()).add(log.getY());
    }

    List<TrunkRun> runs = new ArrayList<>();
    for (Map.Entry<Long, ArrayList<Integer>> entry : heightsByColumn.entrySet()) {
      ArrayList<Integer> heights = entry.getValue();
      if (heights.size() < minimumRunLength) {
        continue;
      }
      heights.sort(Integer::compareTo);
      int runStart = heights.getFirst();
      int runLast = runStart;
      int runLength = 1;
      for (int index = 1; index <= heights.size(); index++) {
        boolean continues = index < heights.size() && heights.get(index) == runLast + 1;
        if (continues) {
          runLast = heights.get(index);
          runLength++;
          continue;
        }
        if (runLength >= minimumRunLength) {
          runs.add(
              new TrunkRun(
                  new BlockPos(columnX(entry.getKey()), runStart, columnZ(entry.getKey())),
                  runLength));
        }
        if (index < heights.size()) {
          runStart = heights.get(index);
          runLast = runStart;
          runLength = 1;
        }
      }
    }
    return runs;
  }

  private static boolean sameColumn(BlockPos first, BlockPos second) {
    return first.getX() == second.getX() && first.getZ() == second.getZ();
  }

  private static long columnKey(int x, int z) {
    return ((long) x << 32) ^ (z & 0xffffffffL);
  }

  private static int columnX(long key) {
    return (int) (key >> 32);
  }

  private static int columnZ(long key) {
    return (int) key;
  }

  private static double distanceToBlockCenterSqr(double x, double y, double z, BlockPos block) {
    double deltaX = block.getX() + 0.5 - x;
    double deltaY = block.getY() + 0.5 - y;
    double deltaZ = block.getZ() + 0.5 - z;
    return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
  }

  private record TrunkRun(BlockPos root, int length) {}
}
