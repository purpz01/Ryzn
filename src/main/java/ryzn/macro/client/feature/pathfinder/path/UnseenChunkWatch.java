package ryzn.macro.client.feature.pathfinder.path;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import ryzn.swift.cache.CachedWorld;
import ryzn.swift.nativepath.NativeStateEncoder;

/**
 * Watches the part of a route that was planned from the DISK cache.
 *
 * <p>The planner routes through chunks the player visited earlier but the client has not streamed
 * in yet - that is how a route past render distance exists at all. Nothing used to re-check that
 * stretch when those chunks arrived: the cache refreshed native for the NEXT search, the running
 * route kept the old plan, and the live look-ahead skipped any chunk that was unloaded at plan time
 * for the rest of the route. A stale cache (a regrown tree, a placed block, a door, a hollow chunk)
 * was only found by walking into it.
 *
 * <p>So at plan time this records what the planner SAW for the route's body corridor in each
 * unloaded chunk, and when one streams in it compares that against the live world. Only a real
 * difference asks for a replan. Replanning on every arrival would not be free even when nothing
 * changed: a replan picks a random move order, so the route would flicker between equal-cost
 * versions every time a chunk loaded.
 */
final class UnseenChunkWatch {

  private static final double BODY_HALF_WIDTH = 0.3;

  /**
   * The corridor cells of one unloaded chunk: packed block pos -> the block code the planner used
   * there.
   */
  private record PlannedChunk(int chunkX, int chunkZ, Map<Long, Short> cells) {}

  /**
   * A chunk the route crosses has streamed in. {@code changedAt} is null when it matched the plan.
   */
  record Arrival(int chunkX, int chunkZ, BlockPos changedAt) {
    long key() {
      return ChunkPos.pack(chunkX, chunkZ);
    }

    boolean changed() {
      return changedAt != null;
    }
  }

  private final Map<Long, PlannedChunk> planned = new HashMap<>();

  void clear() {
    planned.clear();
  }

  /**
   * Record what the planner saw at one trail sample inside a chunk the client has not loaded. Trail
   * Y is the floor block, so the corridor is the floor plus the two body cells above it - the same
   * footprint the look-ahead checks, plus the floor, because a floor that turned out missing breaks
   * a route as surely as a wall does (the hollow-chunk bug).
   */
  void recordSample(double x, double y, double z) {
    int floorY = (int) Math.floor(y);
    int minX = (int) Math.floor(x - BODY_HALF_WIDTH);
    int maxX = (int) Math.floor(x + BODY_HALF_WIDTH);
    int minZ = (int) Math.floor(z - BODY_HALF_WIDTH);
    int maxZ = (int) Math.floor(z + BODY_HALF_WIDTH);
    for (int bx = minX; bx <= maxX; bx++) {
      for (int bz = minZ; bz <= maxZ; bz++) {
        int chunkX = bx >> 4;
        int chunkZ = bz >> 4;
        PlannedChunk chunk =
            planned.computeIfAbsent(
                ChunkPos.pack(chunkX, chunkZ),
                k -> new PlannedChunk(chunkX, chunkZ, new HashMap<>()));
        for (int dy = 0; dy <= 2; dy++) {
          // Null means the cache had nothing here either. The planner treats an uncached chunk as
          // a solid wall and never routes through it, so there is no plan to hold it to.
          Short code = CachedWorld.getBlockCode(bx, floorY + dy, bz);
          if (code != null) {
            chunk.cells().put(BlockPos.asLong(bx, floorY + dy, bz), code);
          }
        }
      }
    }
  }

  /**
   * Check whether any recorded chunk has streamed in, and if so whether the live world agrees with
   * the plan. At most one arrival per call: a changed chunk replans at once, and the replan
   * rebuilds this watch from the new route, so there is nothing to gain from judging a second chunk
   * against a route being replaced.
   */
  Arrival poll(ClientLevel level) {
    if (level == null || planned.isEmpty()) {
      return null;
    }
    for (Iterator<PlannedChunk> it = planned.values().iterator(); it.hasNext(); ) {
      PlannedChunk chunk = it.next();
      LevelChunk live =
          level.getChunkSource().getChunk(chunk.chunkX(), chunk.chunkZ(), ChunkStatus.FULL, false);
      if (live == null) {
        continue;
      }
      it.remove();
      return new Arrival(
          chunk.chunkX(), chunk.chunkZ(), firstDifference(level, live, chunk.cells()));
    }
    return null;
  }

  private static BlockPos firstDifference(
      ClientLevel level, LevelChunk live, Map<Long, Short> cells) {
    LevelChunkSection[] sections = live.getSections();
    for (Map.Entry<Long, Short> cell : cells.entrySet()) {
      BlockPos pos = BlockPos.of(cell.getKey());
      int sectionIndex = (pos.getY() - level.getMinY()) >> 4;
      // A section the client has not been sent reads as air. That is "no information", not "the
      // floor
      // is gone" - comparing it would turn every half-streamed chunk into a phantom change.
      if (sectionIndex < 0 || sectionIndex >= sections.length || sections[sectionIndex] == null) {
        continue;
      }
      if (walkCodesDiffer(
          cell.getValue(), NativeStateEncoder.codeForState(live.getBlockState(pos)))) {
        return pos;
      }
    }
    return null;
  }

  /** True when two cells differ in anything a walker cares about (code or danger bit). */
  static boolean walkCodesDiffer(short planned, short live) {
    return planned != live;
  }
}
