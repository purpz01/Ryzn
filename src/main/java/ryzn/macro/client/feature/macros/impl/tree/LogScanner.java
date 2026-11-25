package ryzn.macro.client.feature.macros.impl.tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Locates dense clumps of selected logs inside the chop perimeter. Reads the LIVE client world (no
 * packets, read-only) one block at a time inside an XZ box and a Y band, then flood-fills
 * 26-connectivity (face + edge + corner neighbours) so adjacent trunks merge into one clump. Only
 * clumps with at least {@code minDensity} log blocks are returned - the density gate is what makes
 * a Treecapitator hit worthwhile and what makes a chopped-then-regrown tree ineligible until it has
 * fully grown back.
 *
 * <p>Bounded by design: the box is small (tens of blocks per side) and the Y band is configurable,
 * so a full pass is a few thousand block reads - cheap enough to run on the client thread between
 * navigations.
 */
public final class LogScanner {

  public static final int MIN_TREE_Y = 71;

  private LogScanner() {}

  public static boolean isLog(BlockState state, TreeLogType type) {
    return (type == null ? TreeLogType.OAK : type).matches(state);
  }

  /**
   * Scan the box [minX..maxX] x [minZ..maxZ] over Y in [minY..maxY] for oak-log clumps, returning
   * every connected clump of at least {@code minDensity} blocks, nearest {@code base} first
   * relative to (originX, originY, originZ). Vertical distance is weighted to mirror the
   * pathfinder's vertical aversion, so "cheaper" (the chosen target) prefers same-level trees over
   * ones a climb away.
   *
   * @param level the world to read blocks from
   * @param minX the smallest block X of the scan box
   * @param maxX the largest block X of the scan box
   * @param minZ the smallest block Z of the scan box
   * @param maxZ the largest block Z of the scan box
   * @param minY the lowest block Y scanned
   * @param maxY the highest block Y scanned
   * @param minDensity the fewest connected logs a clump needs to count
   * @param originX the X distances are measured from, usually the player
   * @param originY the Y distances are measured from
   * @param originZ the Z distances are measured from
   * @param type the log type to collect
   * @return every qualifying clump, cheapest first
   */
  public static List<LogCluster> scan(
      Level level,
      int minX,
      int maxX,
      int minZ,
      int maxZ,
      int minY,
      int maxY,
      int minDensity,
      double originX,
      double originY,
      double originZ,
      TreeLogType type) {
    final List<LogCluster> out = new ArrayList<>();
    if (level == null || maxX < minX || maxZ < minZ || maxY < minY) {
      return out;
    }
    type = type == null ? TreeLogType.OAK : type;
    if (type.isOak()) {
      minY = Math.max(minY, MIN_TREE_Y);
    }
    if (maxY < minY) {
      return out;
    }

    final Set<Long> visited = new HashSet<>();
    final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        for (int y = minY; y <= maxY; y++) {
          // Order matters for cost: the block read happens for every cell (unavoidable, but cheap -
          // a palette index), while the visited-set lookup (which boxes a Long) only runs on the
          // few cells that are actually logs. That keeps a full sweep allocation-light even when
          // the macro rescans several times a second to track other players felling trees.
          if (!isLog(level.getBlockState(probe.set(x, y, z)), type)) {
            continue;
          }
          if (!visited.add(BlockPos.asLong(x, y, z))) {
            continue;
          }
          final LogCluster cluster =
              floodFill(level, x, y, z, minX, maxX, minZ, maxZ, minY, maxY, visited, type);
          if (cluster.size() >= minDensity) {
            out.add(cluster);
          }
        }
      }
    }

    out.sort(Comparator.comparingDouble(c -> cost(c.base(), originX, originY, originZ)));
    return out;
  }

  /**
   * Cheap, bounded re-validation of a single clump: flood-fill from {@code base} and return true as
   * soon as {@code minDensity} connected logs are found (early-out). Used to re-check the macro's
   * current target every tick while walking to it - if another player has felled it (or it is
   * mid-regrow), this goes false and the macro re-picks the nearest live clump instead of walking
   * to an empty spot.
   *
   * @param level the world to read blocks from
   * @param base the clump's trunk foot to flood-fill from
   * @param minX the smallest block X of the scan box
   * @param maxX the largest block X of the scan box
   * @param minZ the smallest block Z of the scan box
   * @param maxZ the largest block Z of the scan box
   * @param minY the lowest block Y scanned
   * @param maxY the highest block Y scanned
   * @param minDensity the fewest connected logs a clump needs to count
   * @param type the log type to count
   * @return true once enough connected logs are still standing
   */
  public static boolean isClusterStillDense(
      Level level,
      BlockPos base,
      int minX,
      int maxX,
      int minZ,
      int maxZ,
      int minY,
      int maxY,
      int minDensity,
      TreeLogType type) {
    if (level == null || base == null) {
      return false;
    }
    type = type == null ? TreeLogType.OAK : type;
    if (type.isOak() && base.getY() < MIN_TREE_Y) {
      return false;
    }
    if (type.isOak()) {
      minY = Math.max(minY, MIN_TREE_Y);
    }
    if (maxY < minY) {
      return false;
    }
    if (!isLog(level.getBlockState(base), type)) {
      return false;
    }

    final Set<Long> visited = new HashSet<>();
    final ArrayDeque<long[]> queue = new ArrayDeque<>();
    final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    visited.add(base.asLong());
    queue.add(new long[] {base.getX(), base.getY(), base.getZ()});
    int count = 0;

    while (!queue.isEmpty()) {
      final long[] cur = queue.poll();
      final int cx = (int) cur[0];
      final int cy = (int) cur[1];
      final int cz = (int) cur[2];
      if (++count >= minDensity) {
        return true;
      }
      for (int ox = -1; ox <= 1; ox++) {
        for (int oy = -1; oy <= 1; oy++) {
          for (int oz = -1; oz <= 1; oz++) {
            if (ox == 0 && oy == 0 && oz == 0) {
              continue;
            }
            final int nx = cx + ox;
            final int ny = cy + oy;
            final int nz = cz + oz;
            if (nx < minX || nx > maxX || nz < minZ || nz > maxZ || ny < minY || ny > maxY) {
              continue;
            }
            if (!isLog(level.getBlockState(probe.set(nx, ny, nz)), type)) {
              continue;
            }
            if (visited.add(BlockPos.asLong(nx, ny, nz))) {
              queue.add(new long[] {nx, ny, nz});
            }
          }
        }
      }
    }
    return false;
  }

  /**
   * Cost proxy used both for sorting clusters and for the macro's "choose the cheaper one"
   * selection.
   *
   * @param base the clump's trunk foot
   * @param originX the X the cost is measured from
   * @param originY the Y the cost is measured from
   * @param originZ the Z the cost is measured from
   * @return the weighted distance, lower is cheaper
   */
  public static double cost(BlockPos base, double originX, double originY, double originZ) {
    final double dx = (base.getX() + 0.5) - originX;
    final double dz = (base.getZ() + 0.5) - originZ;
    final double dy = (base.getY() + 0.5) - originY;
    // Horizontal distance plus a 3x vertical penalty (matches the engine's deliberate vertical
    // aversion).
    return Math.sqrt(dx * dx + dz * dz) + 3.0 * Math.abs(dy);
  }

  private static LogCluster floodFill(
      Level level,
      int sx,
      int sy,
      int sz,
      int minX,
      int maxX,
      int minZ,
      int maxZ,
      int minY,
      int maxY,
      Set<Long> visited,
      TreeLogType type) {
    final List<BlockPos> blocks = new ArrayList<>();
    final ArrayDeque<long[]> queue = new ArrayDeque<>();
    final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    visited.add(BlockPos.asLong(sx, sy, sz));
    queue.add(new long[] {sx, sy, sz});

    long sumX = 0;
    long sumY = 0;
    long sumZ = 0;

    while (!queue.isEmpty()) {
      final long[] cur = queue.poll();
      final int cx = (int) cur[0];
      final int cy = (int) cur[1];
      final int cz = (int) cur[2];
      blocks.add(new BlockPos(cx, cy, cz));
      sumX += cx;
      sumY += cy;
      sumZ += cz;

      for (int ox = -1; ox <= 1; ox++) {
        for (int oy = -1; oy <= 1; oy++) {
          for (int oz = -1; oz <= 1; oz++) {
            if (ox == 0 && oy == 0 && oz == 0) {
              continue;
            }
            final int nx = cx + ox;
            final int ny = cy + oy;
            final int nz = cz + oz;
            if (nx < minX || nx > maxX || nz < minZ || nz > maxZ || ny < minY || ny > maxY) {
              continue;
            }
            final long npacked = BlockPos.asLong(nx, ny, nz);
            if (visited.contains(npacked)) {
              continue;
            }
            if (!isLog(level.getBlockState(probe.set(nx, ny, nz)), type)) {
              continue;
            }
            visited.add(npacked);
            queue.add(new long[] {nx, ny, nz});
          }
        }
      }
    }

    final int n = blocks.size();
    // Lowest block of the clump = the trunk foot, reachable from ground level.
    BlockPos base = blocks.get(0);
    for (BlockPos p : blocks) {
      if (p.getY() < base.getY()) {
        base = p;
      }
    }
    return new LogCluster(blocks, base, (double) sumX / n, (double) sumY / n, (double) sumZ / n, n);
  }
}
