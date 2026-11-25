package ryzn.macro.client.feature.pathfinder.path;

import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.Vec3;

/** Builds the body-safe, wall-inset trail followed by {@link PathExecutor}. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class PathTrailBuilder {
  private static final double NODE_STEP = 1.5;
  private static final double CURVE_BULGE_CAP = 0.8;
  private static final double STRAIGHTEN_DEVIATION = 0.35;
  private static final double BODY_HALF_WIDTH = 0.3;
  private static final double WALL_INSET = 0.25;

  /**
   * Flat distance a trail point keeps from a neighbouring riser: the body's half width plus room
   * for eight-way steering. A recorded Hub slab run put a point 0.38 from a full block, 0.08 of
   * clearance, so every wobble rubbed it and knocked the body off the route.
   */
  private static final double RISER_CLEARANCE = 0.55;

  /** Largest sideways shift that clearance may apply, so a point never leaves its lane. */
  private static final double MAX_RISER_SHIFT = 0.3;

  /** Rise a walking body clears on its own; anything taller beside the trail is a riser. */
  private static final double STEP_UP = 0.6;

  /** Most trail points one straightened run may span (about 15 blocks). */
  private static final int STRAIGHTEN_MAX_POINTS = 10;

  /** Furthest a laid-straight point may move off where the planner put it. */
  private static final double STRAIGHTEN_MAX_SHIFT = 2.0;

  /**
   * Footprint half-width a straightened line is checked with: the body's 0.3 plus the same 0.3 of
   * room again, so a straight line never runs closer to an edge or a wall than the planner's own.
   */
  private static final double STRAIGHTEN_HALF_WIDTH = 0.6;

  private final Minecraft client;

  Result build(List<PathNode> turnNodes) {
    List<Vec3> points = new ArrayList<>();
    List<PathNode.Medium> media = new ArrayList<>();
    List<Integer> turnLaneMarks = new ArrayList<>();
    if (turnNodes.isEmpty()) {
      return new Result(points, media, turnLaneMarks);
    }

    points.add(insetCenter(turnNodes.getFirst()));
    media.add(turnNodes.getFirst().medium());
    turnLaneMarks.add(0);

    for (int i = 0; i < turnNodes.size() - 1; i++) {
      List<Vec3> curved = buildCurvedSpan(turnNodes, i);
      List<Vec3> span = curved != null ? curved : buildStraightSpan(turnNodes, i);
      points.addAll(span);
      PathNode.Medium spanMedium = turnNodes.get(i + 1).medium();
      for (int point = 0; point < span.size(); point++) {
        media.add(spanMedium);
      }
      turnLaneMarks.add(points.size() - 1);
    }
    straightenSlopes(points, media);
    List<Vec3> planned = List.copyOf(points);
    for (int i = 0; i < points.size(); i++) {
      if (media.get(i) != PathNode.Medium.SWIM_SURFACE) {
        points.set(i, clearOfRisers(planned, i));
      }
    }
    return new Result(points, media, turnLaneMarks);
  }

  /**
   * Lays each run of walk points the body can cross in one straight grounded line onto that line,
   * in place, so turn marks and planned jumps keep their indices. The planner walks a wide diagonal
   * slope as a block staircase (east, north, east), and near the Dwarven Mines bridge that jog
   * swung the steer bearing 60-150 deg and the body strafed A, A+S and D through it; the recorded
   * person crossed the same slope in one straight line on W. Steps up or down of more than a free
   * step, swimming and points that would move more than {@link #STRAIGHTEN_MAX_SHIFT} are left as
   * planned, as is any line a wider-than-body footprint cannot walk on continuous floor.
   */
  private void straightenSlopes(List<Vec3> points, List<PathNode.Medium> media) {
    if (client.level == null) {
      return;
    }
    final PathHopTerrain terrain = new PathHopTerrain(client);
    int start = 0;
    while (start < points.size() - 2) {
      int best = start + 1;
      final int limit = Math.min(points.size() - 1, start + STRAIGHTEN_MAX_POINTS);
      // Every step of the run counts, the first one too: skipping it once laid the line from a
      // platform edge straight to a point under the platform, erasing the drop between them, and
      // the body shuffled at the lip for 25 s looking for a fall through solid floor.
      for (int end = start + 2; end <= limit && walkStep(points, media, start + 1); end++) {
        if (!walkStep(points, media, end)) {
          break;
        }
        if (maxShift(points, start, end) <= STRAIGHTEN_MAX_SHIFT
            && lineWalkable(terrain, points.get(start), points.get(end))) {
          best = end;
        }
      }
      if (best > start + 1) {
        final Vec3 from = points.get(start);
        final Vec3 to = points.get(best);
        for (int i = start + 1; i < best; i++) {
          final Vec3 onLine = from.lerp(to, (double) (i - start) / (best - start));
          // The executor reads a point's height as the block it stands on; a lerped height over a
          // slope reads as off the route vertically and hops or replans.
          final double floorY = terrain.supportBlockY(onLine.x, onLine.z, onLine.y + 1.0);
          points.set(i, Double.isNaN(floorY) ? onLine : new Vec3(onLine.x, floorY, onLine.z));
        }
      }
      start = best;
    }
  }

  /** A grounded walk from point {@code index - 1} to {@code index}, no full step up or down. */
  private static boolean walkStep(List<Vec3> points, List<PathNode.Medium> media, int index) {
    return media.get(index) != PathNode.Medium.SWIM_SURFACE
        && media.get(index - 1) != PathNode.Medium.SWIM_SURFACE
        && Math.abs(points.get(index).y - points.get(index - 1).y) <= STEP_UP;
  }

  /** How far any point strictly between {@code start} and {@code end} would move onto the chord. */
  private static double maxShift(List<Vec3> points, int start, int end) {
    final Vec3 from = points.get(start);
    final Vec3 to = points.get(end);
    double worst = 0.0;
    for (int i = start + 1; i < end; i++) {
      final Vec3 onChord = from.lerp(to, (double) (i - start) / (end - start));
      final Vec3 point = points.get(i);
      worst = Math.max(worst, Math.hypot(point.x - onChord.x, point.z - onChord.z));
    }
    return worst;
  }

  private static boolean lineWalkable(PathHopTerrain terrain, Vec3 from, Vec3 to) {
    final BlockPos support = BlockPos.containing(from.x, from.y, from.z);
    return terrain.lineWalkable(
        from.x,
        WalkCheck.getCollisionTopY(support),
        from.z,
        to.x,
        to.z,
        STRAIGHTEN_HALF_WIDTH,
        true);
  }

  /**
   * Shifts trail point {@code index} sideways, across the local route direction only, away from
   * neighbouring cells that stand more than a step above its floor. Along the route is never
   * touched, so a riser the route climbs is not pushed away from. Risers on both sides (a lane too
   * narrow to centre in) or a shift that would land the point on a riser leave it as it was.
   */
  private Vec3 clearOfRisers(List<Vec3> points, int index) {
    if (client.level == null || points.size() < 2) {
      return points.get(index);
    }
    Vec3 point = points.get(index);
    Vec3 before = points.get(Math.max(0, index - 1));
    Vec3 after = points.get(Math.min(points.size() - 1, index + 1));
    double dirX = after.x - before.x;
    double dirZ = after.z - before.z;
    double dirLength = Math.hypot(dirX, dirZ);
    if (dirLength < 1.0e-6) {
      return point;
    }
    double sideX = -dirZ / dirLength;
    double sideZ = dirX / dirLength;

    int blockX = (int) Math.floor(point.x);
    int blockY = (int) Math.floor(point.y);
    int blockZ = (int) Math.floor(point.z);
    double floor = WalkCheck.getCollisionTopY(new BlockPos(blockX, blockY, blockZ));
    double pushLeft = 0.0;
    double pushRight = 0.0;
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        if ((dx == 0 && dz == 0) || !isRiser(blockX + dx, blockY, blockZ + dz, floor)) {
          continue;
        }
        double nearestX = Math.clamp(point.x, blockX + dx, blockX + dx + 1.0);
        double nearestZ = Math.clamp(point.z, blockZ + dz, blockZ + dz + 1.0);
        double gap = Math.hypot(point.x - nearestX, point.z - nearestZ);
        if (gap >= RISER_CLEARANCE || gap < 1.0e-6) {
          continue;
        }
        double away = ((point.x - nearestX) * sideX + (point.z - nearestZ) * sideZ) / gap;
        // A riser mostly ahead or behind is a step the route takes, not a wall beside it.
        if (Math.abs(away) < 0.7) {
          continue;
        }
        if (away > 0) {
          pushLeft = Math.max(pushLeft, RISER_CLEARANCE - gap);
        } else {
          pushRight = Math.max(pushRight, RISER_CLEARANCE - gap);
        }
      }
    }
    if ((pushLeft > 0 && pushRight > 0) || (pushLeft == 0 && pushRight == 0)) {
      return point;
    }
    double shift =
        pushLeft > 0 ? Math.min(MAX_RISER_SHIFT, pushLeft) : -Math.min(MAX_RISER_SHIFT, pushRight);
    Vec3 shifted = new Vec3(point.x + sideX * shift, point.y, point.z + sideZ * shift);
    int shiftedX = (int) Math.floor(shifted.x);
    int shiftedZ = (int) Math.floor(shifted.z);
    boolean sameCell = shiftedX == blockX && shiftedZ == blockZ;
    return sameCell || !isRiser(shiftedX, blockY, shiftedZ, floor) ? shifted : point;
  }

  /** Whether the column at (x, z) rises more than a step above {@code floor} within body height. */
  private boolean isRiser(int x, int floorBlockY, int z, double floor) {
    for (int y = floorBlockY + 2; y >= floorBlockY; y--) {
      BlockPos cell = new BlockPos(x, y, z);
      if (!WalkCheck.canWalkThrough(cell)) {
        return WalkCheck.getCollisionTopY(cell) > floor + STEP_UP;
      }
    }
    return false;
  }

  private List<Vec3> buildCurvedSpan(List<PathNode> turnNodes, int segmentIndex) {
    PathNode p1 = turnNodes.get(segmentIndex);
    PathNode p2 = turnNodes.get(segmentIndex + 1);
    if (p1.medium() != p2.medium()) {
      return null;
    }
    BlockPos n1 = p1.cell();
    BlockPos n2 = p2.cell();
    if (n2.getY() - n1.getY() < -1 || p2.jumpUp() && n2.getY() - n1.getY() > 1) {
      return null;
    }

    Vec3 c1 = insetCenter(turnNodes.get(segmentIndex));
    Vec3 c2 = insetCenter(turnNodes.get(segmentIndex + 1));
    double k1x = c1.x;
    final double k1y = c1.y;
    double k1z = c1.z;
    double k2x = c2.x;
    final double k2y = c2.y;
    double k2z = c2.z;

    double k0x;
    double k0z;
    if (segmentIndex > 0) {
      Vec3 c0 = insetCenter(turnNodes.get(segmentIndex - 1));
      k0x = c0.x;
      k0z = c0.z;
    } else {
      k0x = k1x + (k1x - k2x);
      k0z = k1z + (k1z - k2z);
    }

    double k3x;
    double k3z;
    if (segmentIndex + 2 < turnNodes.size()) {
      Vec3 c3 = insetCenter(turnNodes.get(segmentIndex + 2));
      k3x = c3.x;
      k3z = c3.z;
    } else {
      k3x = k2x + (k2x - k1x);
      k3z = k2z + (k2z - k1z);
    }

    double[] d0 = {k0x, k0z};
    double[] d1 = {k1x, k1z};
    double[] d2 = {k2x, k2z};
    double[] d3 = {k3x, k3z};

    double runLength = 0;
    double lastX = k1x;
    double lastZ = k1z;
    for (int sample = 1; sample <= 10; sample++) {
      double[] point = splineHorizontal(d0, d1, d2, d3, sample / 10.0);
      runLength += Math.hypot(point[0] - lastX, point[1] - lastZ);
      lastX = point[0];
      lastZ = point[1];
    }

    int steps = Math.max(1, (int) Math.ceil(runLength / NODE_STEP));
    List<Vec3> result = new ArrayList<>();
    double maxBulge = 0.0;
    for (int step = 1; step <= steps; step++) {
      double t = (double) step / steps;
      double[] xz = splineHorizontal(d0, d1, d2, d3, t);
      double x = xz[0];
      double z = xz[1];
      double y = k1y + (k2y - k1y) * t;

      double bulge = segmentGapHorizontal(x, z, k1x, k1z, k2x, k2z);
      maxBulge = Math.max(maxBulge, bulge);
      if (bulge > CURVE_BULGE_CAP) {
        double spanX = k2x - k1x;
        double spanZ = k2z - k1z;
        double spanSquared = spanX * spanX + spanZ * spanZ;
        double along = 0.5;
        if (spanSquared > 1e-9) {
          along = ((x - k1x) * spanX + (z - k1z) * spanZ) / spanSquared;
          along = Math.max(0, Math.min(1, along));
        }
        double footX = k1x + spanX * along;
        double footZ = k1z + spanZ * along;
        double shrink = CURVE_BULGE_CAP / bulge;
        x = footX + (x - footX) * shrink;
        z = footZ + (z - footZ) * shrink;
      }

      if (!isPointClear(x, y, z, p2.medium())) {
        return null;
      }
      result.add(new Vec3(x, y, z));
    }

    return maxBulge <= STRAIGHTEN_DEVIATION ? null : result;
  }

  private List<Vec3> buildStraightSpan(List<PathNode> turnNodes, int segmentIndex) {
    BlockPos from = turnNodes.get(segmentIndex).cell();
    BlockPos to = turnNodes.get(segmentIndex + 1).cell();
    Vec3 fromOffset = insetCenter(turnNodes.get(segmentIndex));
    Vec3 toOffset = insetCenter(turnNodes.get(segmentIndex + 1));
    double originX = fromOffset.x;
    double originY = fromOffset.y;
    double originZ = fromOffset.z;
    double deltaX = toOffset.x - originX;
    double deltaY = toOffset.y - originY;
    double deltaZ = toOffset.z - originZ;

    if (deltaY < -1 || turnNodes.get(segmentIndex + 1).jumpUp() && deltaY > 1) {
      return List.of(toOffset);
    }

    double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
    List<Vec3> result = new ArrayList<>();
    int steps = Math.max(1, (int) Math.ceil(distance / NODE_STEP));
    for (int step = 1; step <= steps; step++) {
      double t = (double) step / steps;
      result.add(new Vec3(originX + deltaX * t, originY + deltaY * t, originZ + deltaZ * t));
    }
    return result;
  }

  static double[] splineHorizontal(double[] k0, double[] k1, double[] k2, double[] k3, double t) {
    double squared = t * t;
    double cubed = squared * t;
    double x =
        0.5
            * (2 * k1[0]
                + (-k0[0] + k2[0]) * t
                + (2 * k0[0] - 5 * k1[0] + 4 * k2[0] - k3[0]) * squared
                + (-k0[0] + 3 * k1[0] - 3 * k2[0] + k3[0]) * cubed);
    double z =
        0.5
            * (2 * k1[1]
                + (-k0[1] + k2[1]) * t
                + (2 * k0[1] - 5 * k1[1] + 4 * k2[1] - k3[1]) * squared
                + (-k0[1] + 3 * k1[1] - 3 * k2[1] + k3[1]) * cubed);
    return new double[] {x, z};
  }

  static double segmentGapHorizontal(
      double pointX, double pointZ, double startX, double startZ, double endX, double endZ) {
    double deltaX = endX - startX;
    double deltaZ = endZ - startZ;
    double lengthSquared = deltaX * deltaX + deltaZ * deltaZ;
    if (lengthSquared < 1e-9) {
      return Math.hypot(pointX - startX, pointZ - startZ);
    }
    double t =
        Math.max(
            0,
            Math.min(1, ((pointX - startX) * deltaX + (pointZ - startZ) * deltaZ) / lengthSquared));
    return Math.hypot(pointX - (startX + t * deltaX), pointZ - (startZ + t * deltaZ));
  }

  /**
   * Record every body-corridor cell (feet + head across the footprint) that is SOLID right now at
   * (x, y, z) into {@code out}, as packed {@link BlockPos#asLong()} keys. This is the obstruction
   * watch's plan-time baseline: the native planner certified this whole route walkable, so whatever
   * is solid along it now is terrain the path deliberately passes (a step riser, a slab, an
   * overhang it ducks under), never an obstruction. What's underfoot (blockY) is ignored - only the
   * two body cells matter.
   */
  void collectBlockedCells(double x, double y, double z, java.util.Set<Long> out) {
    int blockY = (int) Math.floor(y);
    int minX = (int) Math.floor(x - BODY_HALF_WIDTH);
    int maxX = (int) Math.floor(x + BODY_HALF_WIDTH);
    int minZ = (int) Math.floor(z - BODY_HALF_WIDTH);
    int maxZ = (int) Math.floor(z + BODY_HALF_WIDTH);
    BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    for (int candidateX = minX; candidateX <= maxX; candidateX++) {
      for (int candidateZ = minZ; candidateZ <= maxZ; candidateZ++) {
        for (int dy = 1; dy <= 2; dy++) {
          probe.set(candidateX, blockY + dy, candidateZ);
          if (!WalkCheck.canWalkThrough(probe)) {
            out.add(probe.asLong());
          }
        }
      }
    }
  }

  /**
   * True if any body-corridor cell at (x, y, z) is solid NOW but was NOT solid at plan time ({@code
   * baseline}, from {@link #collectBlockedCells}) - i.e. a block that has MOVED into the committed
   * corridor since the route was planned (a placed block, a regrown tree). Terrain the path always
   * passed (steps, slabs, overhangs) is in the baseline and ignored, which is what stops a
   * staircase or a step-up from reading as a fresh obstruction and replan-looping. Standability is
   * deliberately NOT checked here.
   */
  boolean hasNewBlockage(double x, double y, double z, java.util.Set<Long> baseline) {
    int blockY = (int) Math.floor(y);
    int minX = (int) Math.floor(x - BODY_HALF_WIDTH);
    int maxX = (int) Math.floor(x + BODY_HALF_WIDTH);
    int minZ = (int) Math.floor(z - BODY_HALF_WIDTH);
    int maxZ = (int) Math.floor(z + BODY_HALF_WIDTH);
    BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    for (int candidateX = minX; candidateX <= maxX; candidateX++) {
      for (int candidateZ = minZ; candidateZ <= maxZ; candidateZ++) {
        for (int dy = 1; dy <= 2; dy++) {
          probe.set(candidateX, blockY + dy, candidateZ);
          if (!WalkCheck.canWalkThrough(probe) && !baseline.contains(probe.asLong())) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private boolean isPointClear(double x, double y, double z, PathNode.Medium medium) {
    if (medium == PathNode.Medium.SWIM_SURFACE) {
      return WaterPathQueries.isSurfaceClear(client.level, x, y, z, BODY_HALF_WIDTH);
    }
    // Standability is asked of every cell the FOOTPRINT covers, not just the one under the point.
    // It used to be a single canStandOn at floor(x)/floor(z) while passability swept the whole
    // footprint,
    // and that asymmetry is what let a curve bulge (up to CURVE_BULGE_CAP, 0.8) put a sample 0.2
    // off a
    // cell boundary and pass while half the body hung over a cell with no floor under it. The
    // planner had
    // certified the cells; the swept body was never the thing being checked.
    int blockY = (int) Math.floor(y);
    int minX = (int) Math.floor(x - BODY_HALF_WIDTH);
    int maxX = (int) Math.floor(x + BODY_HALF_WIDTH);
    int minZ = (int) Math.floor(z - BODY_HALF_WIDTH);
    int maxZ = (int) Math.floor(z + BODY_HALF_WIDTH);
    for (int candidateX = minX; candidateX <= maxX; candidateX++) {
      for (int candidateZ = minZ; candidateZ <= maxZ; candidateZ++) {
        if (!WalkCheck.canStandOn(new BlockPos(candidateX, blockY, candidateZ))) {
          return false;
        }
        if (!WalkCheck.canWalkThrough(new BlockPos(candidateX, blockY + 1, candidateZ))
            || !WalkCheck.canWalkThrough(new BlockPos(candidateX, blockY + 2, candidateZ))) {
          return false;
        }
      }
    }
    return true;
  }

  private Vec3 insetCenter(PathNode node) {
    BlockPos cell = node.cell();
    double x = node.center().x;
    double y = node.center().y;
    double z = node.center().z;
    if (client.level == null || node.medium() == PathNode.Medium.SWIM_SURFACE) {
      return new Vec3(x, y, z);
    }

    int blockX = cell.getX();
    int blockY = cell.getY();
    int blockZ = cell.getZ();
    if (client.level.getBlockState(cell).getBlock() instanceof StairBlock) {
      return new Vec3(x, y, z);
    }

    if (!WalkCheck.canWalkThrough(new BlockPos(blockX + 1, blockY + 1, blockZ))) {
      x -= WALL_INSET;
    }
    if (!WalkCheck.canWalkThrough(new BlockPos(blockX - 1, blockY + 1, blockZ))) {
      x += WALL_INSET;
    }
    if (!WalkCheck.canWalkThrough(new BlockPos(blockX, blockY + 1, blockZ + 1))) {
      z -= WALL_INSET;
    }
    if (!WalkCheck.canWalkThrough(new BlockPos(blockX, blockY + 1, blockZ - 1))) {
      z += WALL_INSET;
    }
    return new Vec3(x, y, z);
  }

  record Result(List<Vec3> points, List<PathNode.Medium> media, List<Integer> turnLaneMarks) {}
}
