package ryzn.macro.client.feature.pathfinder.path;

import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.OBSTRUCT_LOOKAHEAD_BLOCKS;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.OBSTRUCT_SAMPLE_STEP;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import ryzn.swift.cache.CachedWorld;

/**
 * @description Baselines the committed corridor and checks live obstructions and newly loaded
 *     chunks.
 */
final class PathCorridorWatch {

  private final PathExecutor executor;

  PathCorridorWatch(PathExecutor executor) {
    this.executor = executor;
  }

  /**
   * Proactive obstruction watch. Walks the next {@link PathExecutor#OBSTRUCT_LOOKAHEAD_BLOCKS}
   * blocks of the committed trail against the LIVE world and replans the instant a body cell that
   * was OPEN at plan time turns solid - the tree-respawns-over-the-route case. The plan-time
   * baseline ({@link #snapshotCorridorBaseline}) is what keeps it honest: the route's own terrain
   * (step risers, slabs, overhangs the planner deliberately routed through) is recorded up front
   * and ignored, so a staircase or a step-up never reads as a fresh obstruction and replan-loops
   * (the stand-still stutter). Unlike the wedge / progress / drift watchers it does not wait for
   * the body to physically stall on the new block; it sees it coming and reroutes first.
   * Grounded-only (an airborne hop arc is already committed and shouldn't drop its keys to replan)
   * and horizon-limited so it stays cheap. Body passage only, never standability. Returns true if
   * it suspended / stopped us (the caller returns).
   */
  boolean tickObstructionWatch() {
    if (executor.airTicks > 0) {
      return false;
    }
    if (!executor.corridorBaselineReady) {
      return false;
    } // no plan-time terrain snapshot -> can't tell terrain from a new block
    double walked = 0.0;
    for (int i = executor.trailCursor;
        i + 1 < executor.trail.size() && walked < OBSTRUCT_LOOKAHEAD_BLOCKS;
        i++) {
      Vec3 a = executor.trail.get(i);
      Vec3 b = executor.trail.get(i + 1);
      double segLen = PathExecutor.flatDist(a.x, a.z, b.x, b.z);
      int steps = Math.max(1, (int) Math.ceil(segLen / OBSTRUCT_SAMPLE_STEP));
      for (int s = 0; s <= steps; s++) {
        double t = (double) s / steps;
        double x = a.x + (b.x - a.x) * t;
        double y = a.y + (b.y - a.y) * t;
        double z = a.z + (b.z - a.z) * t;
        int cx = ((int) Math.floor(x)) >> 4;
        int cz = ((int) Math.floor(z)) >> 4;
        if (!executor.plannedLoadedChunks.contains(ChunkPos.pack(cx, cz))) {
          continue;
        } // no trustworthy baseline here
        // Only a cell that turned solid AFTER the route was planned counts. Steps, slabs and
        // overhangs
        // the planner routed through are in plannedCorridorBlocks and ignored - that's what stops a
        // staircase or a step-up from replan-looping - while a placed block / regrown tree still
        // fires.
        if (executor.trailBuilder.hasNewBlockage(x, y, z, executor.plannedCorridorBlocks)) {
          if (executor.askReplan(
              String.format("trail obstructed at (%.1f,%.1f,%.1f)", x, y, z),
              "something blocking the path")) {
            return true;
          }
          executor.error("Route is blocked and out of retries - stopping");
          executor.bailed = true;
          executor.won = false;
          executor.stop();
          return true;
        }
      }
      walked += segLen;
    }
    return false;
  }

  /**
   * Snapshot every corridor cell that is already solid along the WHOLE route at plan time. The
   * native planner certified this route, so anything solid on it now is terrain it deliberately
   * passes; the live obstruction watch then fires only on a cell that becomes solid LATER (a placed
   * block, a regrown tree) - something that actually moved into the path - instead of re-flagging
   * the steps / slabs / overhangs it routed through (the stand-still replan loop on stairs).
   * Rebuilt on every setPath, so it always matches the live route.
   */
  void snapshotCorridorBaseline() {
    executor.plannedCorridorBlocks.clear();
    executor.plannedLoadedChunks.clear();
    executor.unseenChunks.clear();
    executor.corridorBaselineReady = false;
    if (executor.client.level == null || executor.trail.size() < 2) {
      return;
    }
    forEachCorridorSample(
        p -> {
          int cx = ((int) Math.floor(p.x)) >> 4;
          int cz = ((int) Math.floor(p.z)) >> 4;
          if (!executor.client.level.getChunkSource().hasChunk(cx, cz)) {
            // Unloaded reads as air, so the live world cannot baseline it honestly. Remember what
            // the
            // PLANNER saw instead, and hold the live world to that when the chunk streams in.
            executor.unseenChunks.recordSample(p.x, p.y, p.z);
            return;
          }
          executor.plannedLoadedChunks.add(ChunkPos.pack(cx, cz));
          executor.trailBuilder.collectBlockedCells(p.x, p.y, p.z, executor.plannedCorridorBlocks);
        });
    executor.corridorBaselineReady = true;
  }

  /** Every point along the whole trail at the look-ahead's sample spacing. */
  void forEachCorridorSample(java.util.function.Consumer<Vec3> sample) {
    for (int i = 0; i + 1 < executor.trail.size(); i++) {
      Vec3 a = executor.trail.get(i);
      Vec3 b = executor.trail.get(i + 1);
      double segLen = PathExecutor.flatDist(a.x, a.z, b.x, b.z);
      int steps = Math.max(1, (int) Math.ceil(segLen / OBSTRUCT_SAMPLE_STEP));
      for (int s = 0; s <= steps; s++) {
        double t = (double) s / steps;
        sample.accept(
            new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t));
      }
    }
  }

  /**
   * A chunk the route was planned through from the disk cache has streamed in. If the live terrain
   * on the route differs from what the route was planned on, pull the chunk into native and replan
   * in place. If it matches, the cached copy was right: baseline the chunk from the live world
   * (honest now, since live == planned) so the look-ahead watches it for the rest of the route like
   * any other.
   *
   * <p>Grounded or not - unlike the look-ahead this only fires on a chunk ARRIVING, which the body
   * cannot be in the middle of committing to, and waiting for landing would let it walk further
   * into a wrong plan.
   */
  boolean tickUnseenChunks() {
    UnseenChunkWatch.Arrival arrival = executor.unseenChunks.poll(executor.client.level);
    if (arrival == null) {
      return false;
    }
    if (!arrival.changed()) {
      long key = arrival.key();
      executor.plannedLoadedChunks.add(key);
      forEachCorridorSample(
          p -> {
            if (ChunkPos.pack(((int) Math.floor(p.x)) >> 4, ((int) Math.floor(p.z)) >> 4) == key) {
              executor.trailBuilder.collectBlockedCells(
                  p.x, p.y, p.z, executor.plannedCorridorBlocks);
            }
          });
      return false;
    }
    BlockPos at = arrival.changedAt();
    CachedWorld.forceReingestChunk(arrival.chunkX(), arrival.chunkZ());
    // No hard stop when the budget is gone, unlike the look-ahead: a changed cell is not
    // necessarily a
    // blocked one (a block that turned to air changes the plan too), and the look-ahead and stall
    // watchers still guard the route if it really is blocked.
    return executor.askReplan(
        String.format(
            "chunk %d,%d streamed in different from the cache at %d,%d,%d",
            arrival.chunkX(), arrival.chunkZ(), at.getX(), at.getY(), at.getZ()),
        null);
  }
}
