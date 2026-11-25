package ryzn.macro.client.feature.macros.impl.tree;

import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Live index of choppable trunk roots, refreshed a slice at a time.
 *
 * <p>The scan itself is the same sweep the macro always did; what changed is WHEN it runs. It used
 * to run whole from {@code FIND_CLUSTER} - every cell of the tree box in one tick (the oak box
 * alone is ~160k block reads) - and that state is only reached after a tree has been felled, so the
 * spike landed while the player stood still with nothing else to do. That was the visible pause
 * between trees.
 *
 * <p>Here the sweep carries a cursor and consumes at most {@link #READS_PER_TICK} cells per tick,
 * publishing a new root list each time it wraps. The previous list stays readable throughout, so
 * the macro can always pick a next tree without waiting for a scan to finish.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class TreeIndex {

  /**
   * Minimum consecutive logs in one column for it to count as a trunk rather than a branch or a
   * stump.
   *
   * <p>Only runs taller than four logs qualify. Connected neighboring logs are part of that tree.
   */
  static final int TRUNK_COLUMN_MIN_LOGS = 5;

  /**
   * Cells read per tick. ~4k keeps a sweep tick well under a millisecond; a full oak box takes ~2s.
   */
  private static final int READS_PER_TICK = 4000;

  /**
   * Ceiling on the one-off {@link #seed(Level, TreeLogType, int[])} sweep. The oak box is ~160k
   * cells, but the player-relative box the other log types use is ~1.6M - seeding that unbounded
   * would hang the client for about a second on start. Past the cap the seed simply stops early and
   * the incremental sweep fills the index in; a slower first target beats a frozen client.
   */
  private static final int SEED_READ_LIMIT = 200_000;

  private final TreeTrunkColumns trunkColumns;
  private final TreeChopAvoidance avoidance;

  private List<BlockPos> roots = List.of();
  private List<BlockPos> pending = new ArrayList<>();
  private int[] sweepBounds;
  private int cursor;

  /**
   * Roots from the most recently completed sweep. Never null; empty until the first sweep lands.
   */
  List<BlockPos> roots() {
    return roots;
  }

  /**
   * One full sweep, run to completion. Only for macro start, where the player is stationary anyway
   * and an empty index would otherwise mean a couple of seconds of no target at all.
   */
  void seed(Level level, TreeLogType type, int[] bounds) {
    if (level == null || bounds == null) {
      return;
    }
    beginSweep(bounds);
    int spent = 0;
    while (sweepBounds != null && spent < SEED_READ_LIMIT) {
      spent += sweepColumns(level, type, READS_PER_TICK);
    }
  }

  /**
   * Advance the sweep by at most one tick's budget. Safe to call every tick.
   *
   * <p>New bounds are only picked up when a fresh sweep starts, never mid-sweep. The non-oak bounds
   * are player-relative, so they shift every time the player crosses a block boundary - restarting
   * on each change would mean a sweep in progress never reaches the end and the index stays
   * permanently empty. Carrying the captured bounds to completion instead costs at most one sweep
   * of staleness.
   */
  void tick(Level level, TreeLogType type, int[] bounds) {
    if (level == null || bounds == null) {
      return;
    }
    if (sweepBounds == null) {
      beginSweep(bounds);
    }
    sweepColumns(level, type, READS_PER_TICK);
  }

  /**
   * Drop a root from the published list immediately, without waiting for the next sweep. Felling a
   * tree makes its root stale the same tick, and re-targeting a stump is exactly the loop that made
   * the bot walk back to a tree it had just finished.
   */
  void forget(BlockPos root) {
    if (root == null || roots.isEmpty()) {
      return;
    }
    List<BlockPos> remaining = new ArrayList<>(roots.size());
    for (BlockPos candidate : roots) {
      if (!candidate.equals(root)) {
        remaining.add(candidate);
      }
    }
    roots = List.copyOf(remaining);
  }

  void clear() {
    roots = List.of();
    pending = new ArrayList<>();
    sweepBounds = null;
    cursor = 0;
  }

  private void beginSweep(int[] bounds) {
    sweepBounds = bounds.clone();
    pending = new ArrayList<>();
    cursor = 0;
  }

  /**
   * Read whole Y columns until the budget runs out or the sweep wraps. Columns are the unit of work
   * so a partially-scanned column can never reach {@link TreeTrunkColumns}, which would split one
   * trunk into two short runs and drop it under the minimum-run gate.
   */
  private int sweepColumns(Level level, TreeLogType type, int budget) {
    final int minX = sweepBounds[0];
    final int maxX = sweepBounds[1];
    final int minZ = sweepBounds[2];
    final int maxZ = sweepBounds[3];
    final int minY = sweepBounds[4];
    final int maxY = sweepBounds[5];
    final int spanZ = maxZ - minZ + 1;
    final int columns = (maxX - minX + 1) * spanZ;
    final int spanY = maxY - minY + 1;
    if (spanZ <= 0 || columns <= 0 || spanY <= 0) {
      finishSweep();
      return 0;
    }

    final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    int spent = 0;
    while (spent < budget) {
      final int x = minX + cursor / spanZ;
      final int z = minZ + cursor % spanZ;
      for (int y = minY; y <= maxY; y++) {
        probe.set(x, y, z);
        if (!avoidance.isAvoidTarget(type, probe)
            && LogScanner.isLog(level.getBlockState(probe), type)) {
          pending.add(probe.immutable());
        }
      }
      spent += spanY;
      if (++cursor >= columns) {
        finishSweep();
        return spent;
      }
    }
    return spent;
  }

  private void finishSweep() {
    roots = List.copyOf(trunkColumns.roots(pending, TRUNK_COLUMN_MIN_LOGS));
    pending = new ArrayList<>();
    sweepBounds = null;
    cursor = 0;
  }
}
