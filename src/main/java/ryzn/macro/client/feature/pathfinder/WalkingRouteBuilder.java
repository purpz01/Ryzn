package ryzn.macro.client.feature.pathfinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import ryzn.macro.client.feature.pathfinder.path.PathNode;
import ryzn.pathfinding.PathManager;

/** Converts one native search result into the executor route and overlay waypoints. */
final class WalkingRouteBuilder {
  private WalkingRouteBuilder() {}

  record Route(List<PathNode> nodes, List<PathRenderer.Waypoint> waypoints) {}

  static Route build(ClientLevel level, PathManager.RouteData route, int maxGapBlocks) {
    final int[] dense = route.dense();
    final int[] keys = route.keyNodes();
    final int[] denseFlags = route.movementFlags();
    final int denseCount = dense.length / 3;
    final int keyCount = keys.length / 3;

    // Match ordered engine turn points to their positions in the dense path.
    final HashSet<Integer> keyDenseIdx = new HashSet<>(keyCount * 2);
    final ArrayList<Integer> keyOrder = new ArrayList<>(keyCount);
    int scan = 0;
    for (int k = 0; k < keyCount; k++) {
      final int kx = keys[k * 3];
      final int ky = keys[k * 3 + 1];
      final int kz = keys[k * 3 + 2];
      for (int d = scan; d < denseCount; d++) {
        if (dense[d * 3] == kx && dense[d * 3 + 1] == ky && dense[d * 3 + 2] == kz) {
          keyDenseIdx.add(d);
          keyOrder.add(d);
          scan = d + 1;
          break;
        }
      }
    }

    final List<PathRenderer.Waypoint> waypoints =
        new ArrayList<>(); // overlay nodes (turn points + gap-fillers)
    final List<PathNode> nodes = new ArrayList<>();
    // Use the configured spacing in open terrain and retain dense nodes along tight corridors.
    final int maxGapSq = maxGapBlocks * maxGapBlocks;
    int lastX = 0;
    int lastZ = 0; // valid after the d==0 emission below
    int kptr = 0; // index into keyOrder: keyOrder[kptr] is the last key node at or before d
    for (int d = 0; d < denseCount; d++) {
      // Advance the bracket so keyOrder[kptr] <= d < keyOrder[kptr+1].
      while (kptr + 1 < keyOrder.size() && keyOrder.get(kptr + 1) <= d) {
        kptr++;
      }

      final int dx = dense[d * 3];
      final int dy = dense[d * 3 + 1];
      final int dz = dense[d * 3 + 2];
      final boolean isKey = keyDenseIdx.contains(d);
      final boolean isRising = d > 0 && dy > dense[(d - 1) * 3 + 1];
      final int srcFlags = (d > 0 && d - 1 < denseFlags.length) ? denseFlags[d - 1] : 0;
      final boolean isHalfStep = (srcFlags & PathManager.FLAG_STEP_UP_HALF) != 0;
      final boolean isRealJump = isRising && !isHalfStep;
      final int nodeFlags = d < denseFlags.length ? denseFlags[d] : 0;
      final PathNode.Medium medium = mediumFromFlags(nodeFlags);
      final PathNode.Medium previousMedium =
          d > 0 ? mediumFromFlags(d - 1 < denseFlags.length ? denseFlags[d - 1] : 0) : medium;
      final PathNode.Medium nextMedium =
          d + 1 < denseCount
              ? mediumFromFlags(d + 1 < denseFlags.length ? denseFlags[d + 1] : 0)
              : medium;
      final boolean mediumBoundary = medium != previousMedium || medium != nextMedium;
      final boolean tight = isTightCell(level, dx, dy, dz);

      boolean emit = d == 0 || isKey || isRealJump || mediumBoundary;
      boolean isGapFiller = false;
      // Where the straight chord between the bracketing key nodes would carry the body at this
      // dense node, and whether that slides it onto a riser beside a slab or stair run - which it
      // then has to jump instead of auto-stepping up the treads the planner chose.
      double chordX = Double.NaN;
      double chordZ = Double.NaN;
      boolean chordClipsRiser = false;
      if (!isKey && kptr + 1 < keyOrder.size()) {
        final int prevIdx = keyOrder.get(kptr);
        final int nextIdx = keyOrder.get(kptr + 1);
        if (nextIdx > prevIdx) {
          final double t = (double) (d - prevIdx) / (double) (nextIdx - prevIdx);
          final double px = dense[prevIdx * 3] + 0.5;
          final double pz = dense[prevIdx * 3 + 2] + 0.5;
          final double nx = dense[nextIdx * 3] + 0.5;
          final double nz = dense[nextIdx * 3 + 2] + 0.5;
          chordX = px + (nx - px) * t;
          chordZ = pz + (nz - pz) * t;
          final int prev = Math.max(0, d - 1) * 3;
          chordClipsRiser =
              Math.hypot(chordX - (dx + 0.5), chordZ - (dz + 0.5)) <= RISER_CHECK_REACH
                  && bodyClipsRiser(
                      level,
                      chordX,
                      chordZ,
                      dy,
                      dx,
                      dz,
                      dense[prev],
                      dense[prev + 1],
                      dense[prev + 2]);
        }
      }
      // Force-emit a gap-filler when the run of unemitted dense nodes since the last waypoint has
      // reached the gap threshold (the dense path is the actual safe corridor, so the polyline
      // stays
      // inside it instead of cutting corners). Tight nodes use gap 1 (every block) regardless of
      // setting. A node whose chord clips a riser is emitted at its own cell, which bends the trail
      // back onto the treads.
      if (!emit && !waypoints.isEmpty()) {
        final int gapSq = tight ? 1 : maxGapSq;
        final int gx = dx - lastX;
        final int gz = dz - lastZ;
        if (gx * gx + gz * gz >= gapSq || chordClipsRiser) {
          emit = true;
          isGapFiller = true;
        }
      }
      if (emit) {
        double centerX = dx + 0.5;
        double centerZ = dz + 0.5;
        // An open-space gap-filler is snapped onto the straight chord between its two bracketing
        // key nodes rather than copying the raw dense node. The dense path is a block staircase
        // (e.g. E,N,E,N along a diagonal, plus small jogs from the penalty gradients); sampling it
        // every few blocks reproduces that ±1-block tread as left/right waypoints, which the
        // smoother
        // had already collapsed. Between two ADJACENT key nodes the smoother certified the chord
        // walk-safe (the dense path can bow several blocks off it), so projecting onto it is safe
        // and straightens the run. Tight cells still trace the dense corridor (they need the
        // polyline), and so do cells where the chord would put the body on a riser.
        if (isGapFiller && !tight && !chordClipsRiser && !Double.isNaN(chordX)) {
          centerX = chordX;
          centerZ = chordZ;
        }
        double centerY = medium == PathNode.Medium.SWIM_SURFACE ? dy + 1.0 : dy;
        waypoints.add(
            new PathRenderer.Waypoint(dx, dy, dz, centerX, centerY, centerZ, !isGapFiller));
        nodes.add(
            PathNode.precise(
                new BlockPos(dx, dy, dz), centerX, centerZ, isRealJump, medium, isGapFiller));
        lastX = dx;
        lastZ = dz;
      }
    }
    return new Route(nodes, waypoints);
  }

  private static PathNode.Medium mediumFromFlags(int flags) {
    if ((flags & PathManager.FLAG_SWIM_SURFACE) != 0) {
      return PathNode.Medium.SWIM_SURFACE;
    }
    if ((flags & PathManager.FLAG_FLUID_FEET) != 0) {
      return PathNode.Medium.WADE;
    }
    return PathNode.Medium.LAND;
  }

  /** Checks the eight neighbors for a wall at both feet and head height. */
  private static boolean isTightCell(ClientLevel level, int x, int y, int z) {
    if (level == null) {
      return false;
    }
    final int by = y + 1; // node y is the floor block; the body occupies y+1
    for (int ox = -1; ox <= 1; ox++) {
      for (int oz = -1; oz <= 1; oz++) {
        if (ox == 0 && oz == 0) {
          continue;
        }
        if (!solidAt(level, x + ox, by, z + oz)) {
          continue;
        }
        // A one-block obstacle can be the next stair tread. Only a two-block wall is tight.
        if (solidAt(level, x + ox, by + 1, z + oz)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * True when a 0.6-wide body centred at {@code (x, z)} would overlap a neighbouring column whose
   * surface stands more than a free step above the lower of this node and the one before it - a
   * riser the body would walk into instead of up. Node y is the floor block, as in {@link
   * #isTightCell}.
   */
  private static boolean bodyClipsRiser(
      ClientLevel level,
      double x,
      double z,
      int nodeY,
      int nodeX,
      int nodeZ,
      int fromX,
      int fromY,
      int fromZ) {
    if (level == null) {
      return false;
    }
    final double floor =
        Math.min(surfaceTop(level, nodeX, nodeY, nodeZ), surfaceTop(level, fromX, fromY, fromZ));
    for (double offsetX : new double[] {-0.3, 0.3}) {
      for (double offsetZ : new double[] {-0.3, 0.3}) {
        final int columnX = Mth.floor(x + offsetX);
        final int columnZ = Mth.floor(z + offsetZ);
        if (surfaceTop(level, columnX, nodeY, columnZ) - floor > STEP_HEIGHT) {
          return true;
        }
      }
    }
    return false;
  }

  /** Highest collision top in a column around a floor block, from one below to two above it. */
  private static double surfaceTop(ClientLevel level, int x, int floorY, int z) {
    for (int y = floorY + 2; y >= floorY - 1; y--) {
      final BlockPos pos = new BlockPos(x, y, z);
      final net.minecraft.world.phys.shapes.VoxelShape shape =
          level.getBlockState(pos).getCollisionShape(level, pos);
      if (!shape.isEmpty()) {
        return y + shape.max(net.minecraft.core.Direction.Axis.Y);
      }
    }
    return floorY - 1;
  }

  /**
   * Furthest a chord point may sit from its dense cell for {@link #bodyClipsRiser} to judge it. The
   * check measures against this cell's floor, but a chord point further out stands on its own
   * ground, which across a slope is a terrace above or below: every such point read as a riser, sat
   * on the dense path between snapped neighbours, and the trail zigzagged several blocks per node.
   */
  private static final double RISER_CHECK_REACH = 2.0;

  /** Vanilla auto-step: a rise the body walks up without jumping. */
  private static final double STEP_HEIGHT = 0.6;

  private static boolean solidAt(ClientLevel level, int x, int y, int z) {
    final BlockPos bp = new BlockPos(x, y, z);
    return !level.getBlockState(bp).getCollisionShape(level, bp).isEmpty();
  }

  /**
   * Diagnostic: blocks to the nearest 2-tall wall from a key node in one cardinal dir (99 if none
   * within 5). Node y is the floor block, so the body occupies y+1 (feet) and y+2 (head).
   */
  static int wallClearance(ClientLevel level, int x, int y, int z, int dx, int dz) {
    if (level == null) {
      return -1;
    }
    final int feet = y + 1;
    for (int d = 1; d <= 5; d++) {
      final int cx = x + dx * d;
      final int cz = z + dz * d;
      if (solidAt(level, cx, feet, cz) && solidAt(level, cx, feet + 1, cz)) {
        return d;
      }
    }
    return 99;
  }
}
