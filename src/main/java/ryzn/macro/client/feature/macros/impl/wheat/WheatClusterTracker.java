package ryzn.macro.client.feature.macros.impl.wheat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class WheatClusterTracker {

  record Cluster(BlockPos center, int size, int adjacent) {}

  private static final boolean USE_DIAGONALS = true;
  private static final int MIN_ADJACENT = 4;
  private static final int DENSITY_RADIUS = 2;
  private static final int SCAN_INTERVAL = 5;
  private static final double STICKY_SWITCH_FACTOR = 1.35;
  private static final int STICKY_FOLLOW_SQ = DENSITY_RADIUS * DENSITY_RADIUS * 2;
  private static final double DISTANCE_PENALTY = 0.25;
  private static final long VISITED_TICKS = 200;
  private static final int VISITED_SPREAD = 1;
  private static final double CONTINUOUS_REACH = 4.5;
  private static final double LOOK_Y_OFFSET = 0.85;
  private final WheatFieldRoute fieldRoute;
  private final List<Cluster> liveClusters = new ArrayList<>();
  private final Map<BlockPos, Long> visitedUntil = new HashMap<>();
  private long clock;
  private int initialCount;
  private int scanCooldown;
  private BlockPos stickyCenter;

  void reset() {
    liveClusters.clear();
    visitedUntil.clear();
    initialCount = 0;
    scanCooldown = 0;
    stickyCenter = null;
    clock = 0;
  }

  /**
   * The walk reached (or could not reach) this bunch: leave it alone for a while so the next pick
   * is somewhere else and the body keeps moving. Whatever is still standing there is picked up
   * again once the mark expires.
   */
  void markVisited(Cluster cluster) {
    visitedUntil.put(cluster.center(), clock + VISITED_TICKS);
    stickyCenter = null;
  }

  private boolean isVisited(BlockPos center) {
    for (BlockPos visited : visitedUntil.keySet()) {
      if (Math.abs(visited.getX() - center.getX()) <= VISITED_SPREAD
          && Math.abs(visited.getZ() - center.getZ()) <= VISITED_SPREAD) {
        return true;
      }
    }
    return false;
  }

  int liveCount() {
    return liveClusters.size();
  }

  float progress() {
    return progress(initialCount, liveCount());
  }

  static float progress(int initialCount, int liveCount) {
    if (initialCount <= 0) {
      return 0.0f;
    }
    return Mth.clamp((initialCount - liveCount) / (float) initialCount, 0.0f, 1.0f);
  }

  void captureInitial(ClientLevel level, BlockPos zoneCenter, double zoneRadiusSq) {
    refresh(level, true, zoneCenter, zoneRadiusSq);
    initialCount = liveCount();
  }

  void refresh(ClientLevel level, boolean force, BlockPos zoneCenter, double zoneRadiusSq) {
    clock++;
    visitedUntil.values().removeIf(until -> until <= clock);
    if (!force && scanCooldown > 0) {
      scanCooldown--;
      return;
    }
    scanCooldown = SCAN_INTERVAL;
    scan(level, zoneCenter, zoneRadiusSq);
  }

  /**
   * The bunch to walk to: any live cluster around the player, nearer and denser first. Once picked,
   * the same bunch is kept while any part of it is left (its centre block is usually broken first,
   * so the nearest surviving cluster within the density radius stands in for it).
   */
  Cluster selectSticky(LocalPlayer player, float anchorYaw) {
    if (liveClusters.isEmpty()) {
      stickyCenter = null;
      return null;
    }
    Vec3 eye = player.getEyePosition();
    double reach = Math.max(CONTINUOUS_REACH, player.blockInteractionRange());
    Cluster best = null;
    double bestScore = -1.0;
    Cluster sticky = null;
    double stickyScore = -1.0;
    int stickyDistSq = Integer.MAX_VALUE;
    for (Cluster cluster : liveClusters) {
      if (isVisited(cluster.center())) {
        continue;
      }
      double score = score(cluster, eye, anchorYaw, reach);
      if (score > bestScore) {
        bestScore = score;
        best = cluster;
      }
      if (stickyCenter != null) {
        int distSq = horizontalDistSq(cluster.center(), stickyCenter);
        if (distSq <= STICKY_FOLLOW_SQ
            && (distSq < stickyDistSq || distSq == stickyDistSq && score > stickyScore)) {
          stickyDistSq = distSq;
          sticky = cluster;
          stickyScore = score;
        }
      }
    }
    if (sticky != null && stickyScore * STICKY_SWITCH_FACTOR >= bestScore) {
      stickyCenter = sticky.center();
      return sticky;
    }
    stickyCenter = best == null ? null : best.center();
    return best;
  }

  BlockPos bestInCone(
      LocalPlayer player,
      ClientLevel level,
      float anchorYaw,
      int scanRadius,
      float halfCone,
      BlockPos zoneCenter,
      double zoneRadiusSq) {
    int radius = (int) Math.ceil(Math.max(CONTINUOUS_REACH, scanRadius));
    double reach = Math.max(CONTINUOUS_REACH, player.blockInteractionRange());
    double reachSq = reach * reach;
    Vec3 eye = player.getEyePosition();
    BlockPos origin = BlockPos.containing(player.getX(), player.getY(), player.getZ());
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    BlockPos best = null;
    double bestScore = -1.0;
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        for (int dy = -2; dy <= 2; dy++) {
          cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
          if (!isMatureWheat(level, cursor) || !isActiveCrop(cursor, zoneCenter, zoneRadiusSq)) {
            continue;
          }
          double distSq = targetDistanceSqr(eye, cursor);
          if (distSq > reachSq) {
            continue;
          }
          float bearing = Mth.wrapDegrees(yawTo(eye, cursor) - anchorYaw);
          if (Math.abs(bearing) > halfCone) {
            continue;
          }
          int density = 1 + countMatureNeighbors(level, cursor, USE_DIAGONALS);
          double score = (density * density) / (Math.sqrt(distSq) + 1.0);
          if (score > bestScore) {
            bestScore = score;
            best = cursor.immutable();
          }
        }
      }
    }
    return best;
  }

  Vec3 centerVector(Cluster cluster) {
    BlockPos center = cluster.center();
    return new Vec3(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
  }

  private void scan(ClientLevel level, BlockPos zoneCenter, double zoneRadiusSq) {
    liveClusters.clear();
    if (!fieldRoute.farmland().isEmpty()) {
      for (BlockPos farmland : fieldRoute.farmland()) {
        addIfValid(level, farmland.above(), zoneCenter, zoneRadiusSq);
      }
      return;
    }
    int radius = (int) Math.ceil(Math.sqrt(zoneRadiusSq));
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        for (int dy = -3; dy <= 3; dy++) {
          cursor.set(zoneCenter.getX() + dx, zoneCenter.getY() + dy, zoneCenter.getZ() + dz);
          addIfValid(level, cursor, zoneCenter, zoneRadiusSq);
        }
      }
    }
  }

  private void addIfValid(
      ClientLevel level, BlockPos pos, BlockPos zoneCenter, double zoneRadiusSq) {
    if (!isMatureWheat(level, pos) || !isActiveCrop(pos, zoneCenter, zoneRadiusSq)) {
      return;
    }
    int adjacent = countMatureNeighbors(level, pos, USE_DIAGONALS);
    if (adjacent < MIN_ADJACENT) {
      return;
    }
    int size = countMatureInRadius(level, pos, DENSITY_RADIUS);
    liveClusters.add(new Cluster(pos.immutable(), size, adjacent));
  }

  private boolean isActiveCrop(BlockPos pos, BlockPos zoneCenter, double zoneRadiusSq) {
    double dx = pos.getX() - zoneCenter.getX();
    double dz = pos.getZ() - zoneCenter.getZ();
    return dx * dx + dz * dz <= zoneRadiusSq || fieldRoute.containsCrop(pos);
  }

  private static int countMatureNeighbors(ClientLevel level, BlockPos center, boolean diagonals) {
    int count = 0;
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        if (dx == 0 && dz == 0 || !diagonals && dx != 0 && dz != 0) {
          continue;
        }
        cursor.set(center.getX() + dx, center.getY(), center.getZ() + dz);
        if (isMatureWheat(level, cursor)) {
          count++;
        }
      }
    }
    return count;
  }

  private static int countMatureInRadius(ClientLevel level, BlockPos center, int radius) {
    int count = 0;
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        cursor.set(center.getX() + dx, center.getY(), center.getZ() + dz);
        if (isMatureWheat(level, cursor)) {
          count++;
        }
      }
    }
    return count;
  }

  private static double score(Cluster cluster, Vec3 eye, float anchorYaw, double reach) {
    double dist = Math.sqrt(targetDistanceSqr(eye, cluster.center()));
    double reachScore = dist <= reach ? 1.0 : reach / dist;
    float bearing = Mth.wrapDegrees(yawTo(eye, cluster.center()) - anchorYaw);
    double angleScore = 0.5 + 0.5 * Math.cos(Math.toRadians(bearing));
    return cluster.size()
        * (0.45 + 0.55 * reachScore)
        * (0.55 + 0.45 * angleScore)
        / (1.0 + dist * DISTANCE_PENALTY);
  }

  private static int horizontalDistSq(BlockPos a, BlockPos b) {
    int dx = a.getX() - b.getX();
    int dz = a.getZ() - b.getZ();
    return dx * dx + dz * dz;
  }

  private static boolean isMatureWheat(ClientLevel level, BlockPos pos) {
    BlockState state = level.getBlockState(pos);
    return state.is(Blocks.WHEAT)
        && state.getBlock() instanceof CropBlock crop
        && crop.isMaxAge(state);
  }

  private static float yawTo(Vec3 from, BlockPos target) {
    double targetX = target.getX() + 0.5;
    double targetZ = target.getZ() + 0.5;
    return (float) Math.toDegrees(Math.atan2(-(targetX - from.x), targetZ - from.z));
  }

  private static double targetDistanceSqr(Vec3 eye, BlockPos target) {
    double dx = target.getX() + 0.5 - eye.x;
    double dy = target.getY() + LOOK_Y_OFFSET - eye.y;
    double dz = target.getZ() + 0.5 - eye.z;
    return dx * dx + dy * dy + dz * dz;
  }

  private static double horizontalDistanceSq(LocalPlayer player, BlockPos target) {
    double dx = target.getX() + 0.5 - player.getX();
    double dz = target.getZ() + 0.5 - player.getZ();
    return dx * dx + dz * dz;
  }
}
