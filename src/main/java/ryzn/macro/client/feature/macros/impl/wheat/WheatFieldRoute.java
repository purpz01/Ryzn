package ryzn.macro.client.feature.macros.impl.wheat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

final class WheatFieldRoute {

  private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}

  private static final int FIELD_SCAN_RADIUS_SQ = 10 * 10;
  private static final int FIELD_FLOOD_LIMIT = 768;
  private static final int FIELD_LEAVE_THRESHOLD = 4;
  private static final int LOOP_INSET = 2;

  private final Set<BlockPos> farmland = new HashSet<>();
  private final Set<BlockPos> farmlandView = Collections.unmodifiableSet(farmland);
  private final List<BlockPos> waypoints = new ArrayList<>();
  private final List<BlockPos> waypointView = Collections.unmodifiableList(waypoints);
  private Bounds bounds;

  void rebuild(ClientLevel level, BlockPos center, int scanRadius, int loopSpacing) {
    clear();
    BlockPos seed = findSeedFarmland(level, center);
    if (seed != null) {
      floodFillFarmland(level, center, seed);
      bounds = boundsFor(farmland);
      buildContinuousLoop(loopSpacing);
    }
    if (waypoints.isEmpty()) {
      useFallbackLoop(center, scanRadius);
    }
  }

  void clear() {
    farmland.clear();
    waypoints.clear();
    bounds = null;
  }

  void useFallbackLoop(BlockPos center, int scanRadius) {
    waypoints.clear();
    int radius = Math.max(3, Math.min(6, scanRadius));
    int y = center.getY() - 1;
    waypoints.add(new BlockPos(center.getX() - radius, y, center.getZ() - radius));
    waypoints.add(new BlockPos(center.getX() + radius, y, center.getZ() - radius));
    waypoints.add(new BlockPos(center.getX() + radius, y, center.getZ() + radius));
    waypoints.add(new BlockPos(center.getX() - radius, y, center.getZ() + radius));
  }

  List<BlockPos> waypoints() {
    return waypointView;
  }

  Set<BlockPos> farmland() {
    return farmlandView;
  }

  boolean isEmpty() {
    return farmland.isEmpty() && waypoints.isEmpty();
  }

  boolean hasWaypoints() {
    return !waypoints.isEmpty();
  }

  boolean containsCrop(BlockPos cropPosition) {
    return !farmland.isEmpty() && farmland.contains(cropPosition.below());
  }

  boolean playerLeft(LocalPlayer player, boolean inLooseZone) {
    if (!inLooseZone) {
      return true;
    }
    if (bounds == null || farmland.isEmpty()) {
      return false;
    }
    double playerX = player.getX();
    double playerZ = player.getZ();
    double dx = Math.max(0.0, Math.max(bounds.minX() - playerX, playerX - (bounds.maxX() + 1.0)));
    double dz = Math.max(0.0, Math.max(bounds.minZ() - playerZ, playerZ - (bounds.maxZ() + 1.0)));
    if (dx <= FIELD_LEAVE_THRESHOLD && dz <= FIELD_LEAVE_THRESHOLD) {
      return false;
    }
    BlockPos under = BlockPos.containing(player.getX(), player.getY() - 1.0, player.getZ());
    return !farmland.contains(under);
  }

  boolean playerOn(LocalPlayer player) {
    if (farmland.isEmpty()) {
      return false;
    }
    BlockPos under = BlockPos.containing(player.getX(), player.getY() - 1.0, player.getZ());
    return farmland.contains(under);
  }

  private BlockPos findSeedFarmland(ClientLevel level, BlockPos center) {
    BlockPos best = null;
    int bestScore = Integer.MAX_VALUE;
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int dy = -2; dy <= 2; dy++) {
      for (int dx = -6; dx <= 6; dx++) {
        for (int dz = -6; dz <= 6; dz++) {
          cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
          if (!level.getBlockState(cursor).is(Blocks.FARMLAND)) {
            continue;
          }
          int score = dx * dx + dz * dz + dy * dy * 4;
          if (score < bestScore) {
            bestScore = score;
            best = cursor.immutable();
          }
        }
      }
    }
    return best;
  }

  private void floodFillFarmland(ClientLevel level, BlockPos center, BlockPos seed) {
    ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    farmland.add(seed);
    queue.add(seed);
    while (!queue.isEmpty() && farmland.size() < FIELD_FLOOD_LIMIT) {
      BlockPos position = queue.removeFirst();
      for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (dx == 0 && dz == 0) {
            continue;
          }
          for (int dy = -1; dy <= 1; dy++) {
            BlockPos next = position.offset(dx, dy, dz);
            if (farmland.contains(next) || !isInsideFieldScan(next, center)) {
              continue;
            }
            if (!level.getBlockState(next).is(Blocks.FARMLAND)) {
              continue;
            }
            farmland.add(next);
            queue.add(next);
            if (farmland.size() >= FIELD_FLOOD_LIMIT) {
              return;
            }
          }
        }
      }
    }
  }

  private boolean isInsideFieldScan(BlockPos position, BlockPos center) {
    int dx = position.getX() - center.getX();
    int dz = position.getZ() - center.getZ();
    return dx * dx + dz * dz <= FIELD_SCAN_RADIUS_SQ
        && Math.abs(position.getY() - center.getY()) <= 4;
  }

  private Bounds boundsFor(Set<BlockPos> blocks) {
    if (blocks.isEmpty()) {
      return null;
    }
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;
    int maxZ = Integer.MIN_VALUE;
    for (BlockPos position : blocks) {
      minX = Math.min(minX, position.getX());
      minY = Math.min(minY, position.getY());
      minZ = Math.min(minZ, position.getZ());
      maxX = Math.max(maxX, position.getX());
      maxY = Math.max(maxY, position.getY());
      maxZ = Math.max(maxZ, position.getZ());
    }
    return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
  }

  private void buildContinuousLoop(int spacing) {
    if (bounds == null || farmland.isEmpty()) {
      return;
    }
    int x0 = bounds.minX() + LOOP_INSET;
    int x1 = bounds.maxX() - LOOP_INSET;
    int z0 = bounds.minZ() + LOOP_INSET;
    int z1 = bounds.maxZ() - LOOP_INSET;
    if (x0 > x1) {
      x0 = bounds.minX();
      x1 = bounds.maxX();
    }
    if (z0 > z1) {
      z0 = bounds.minZ();
      z1 = bounds.maxZ();
    }

    List<BlockPos> raw = new ArrayList<>();
    for (int x = x0; x < x1; x += spacing) {
      addLoopPoint(raw, x, z0);
    }
    addLoopPoint(raw, x1, z0);
    for (int z = z0 + spacing; z < z1; z += spacing) {
      addLoopPoint(raw, x1, z);
    }
    addLoopPoint(raw, x1, z1);
    for (int x = x1 - spacing; x > x0; x -= spacing) {
      addLoopPoint(raw, x, z1);
    }
    addLoopPoint(raw, x0, z1);
    for (int z = z1 - spacing; z > z0; z -= spacing) {
      addLoopPoint(raw, x0, z);
    }

    for (BlockPos position : raw) {
      if (waypoints.isEmpty() || !waypoints.get(waypoints.size() - 1).equals(position)) {
        waypoints.add(position);
      }
    }
    if (waypoints.size() > 1 && waypoints.get(0).equals(waypoints.get(waypoints.size() - 1))) {
      waypoints.remove(waypoints.size() - 1);
    }
  }

  private void addLoopPoint(List<BlockPos> output, int x, int z) {
    BlockPos snapped = nearestFarmland(x, z);
    if (snapped != null) {
      output.add(snapped);
    }
  }

  private BlockPos nearestFarmland(int x, int z) {
    BlockPos best = null;
    long bestDistance = Long.MAX_VALUE;
    for (BlockPos position : farmland) {
      long dx = position.getX() - x;
      long dz = position.getZ() - z;
      long distance = dx * dx + dz * dz;
      if (distance < bestDistance) {
        bestDistance = distance;
        best = position;
      }
    }
    return best;
  }
}
