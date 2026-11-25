package ryzn.pathfinding;

import com.chattriggers.ctjs.api.world.pathfinding.NativePathResult;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.FutureTask;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import ryzn.macro.client.config.RyznSettings;
import ryzn.swift.Swift;
import ryzn.swift.cache.CachedWorld;
import ryzn.swift.nativepath.NativePathfinderBridge;
import ryzn.swift.nativepath.NativePathfinderBridge.NativePathSearchRequest;
import ryzn.swift.nativepath.NativeVoxelFlags;

/** Runs native path searches and publishes the latest result for walking navigation. */
public final class PathManager {

  private static final Logger logger = LogUtils.getLogger();

  private static final int MIN_AVOID_RADIUS = 1;
  private static final int MAX_AVOID_RADIUS = 6;
  private static final double MIN_AVOID_PENALTY = 5.0;
  private static final double MAX_AVOID_PENALTY = 120.0;
  private static final int MIN_AVOID_TTL_SEARCHES = 1;
  private static final int MAX_AVOID_TTL_SEARCHES = 8;
  private static final int AVOID_MAX_Y_DIFF = 2;

  // Weighted A*: 1.0 = optimal (explores a huge frontier - measured 106 nodes explored per path
  // node
  // at w=1.05); higher trades path optimality for far fewer expansions. User-tunable via the GUI's
  // "laziness" setting (see RyznSettings.pathfinder().heuristicWeight()); higher laziness =
  // greedier = lazier search.
  private static double heuristicWeight() {
    return RyznSettings.get().pathfinder().heuristicWeight();
  }

  // V5 dense path annotations plus movement flags derived by Ryzn.
  /** Feet in fluid - the executor switches to its wade gait. */
  public static final int FLAG_FLUID_FEET = 1 << 0;

  /**
   * Source node of a step-up where the landing block is a slab/stair (vanilla auto-step, no jump
   * key).
   */
  public static final int FLAG_STEP_UP_HALF = 1 << 8;

  /**
   * Node occupies the top water cell over non-solid support and should be traversed by surface
   * swimming.
   */
  public static final int FLAG_SWIM_SURFACE = 1 << 9;

  private record PathSnapshot(
      List<BlockPos> points,
      List<BlockPos> keyNodes,
      int[] movementFlags,
      long timeMs,
      int nodesExplored,
      double nanosecondsPerNode) {}

  private static final class ManagedAvoidEntry {
    final int blockX;
    final int blockY;
    final int blockZ;
    final int radius;
    double penalty;
    int ttlSearches;

    ManagedAvoidEntry(int x, int y, int z, int radius, double penalty, int ttlSearches) {
      this.blockX = x;
      this.blockY = y;
      this.blockZ = z;
      this.radius = radius;
      this.penalty = penalty;
      this.ttlSearches = ttlSearches;
    }
  }

  private static final class NativeAvoidZone {
    final int blockX;
    final int blockY;
    final int blockZ;
    final int radiusSq;
    final int maxDeltaY;
    final double penalty;

    NativeAvoidZone(int x, int y, int z, int radiusSq, double penalty, int maxDeltaY) {
      this.blockX = x;
      this.blockY = y;
      this.blockZ = z;
      this.radiusSq = radiusSq;
      this.penalty = penalty;
      this.maxDeltaY = maxDeltaY;
    }
  }

  private static final SearchState<PathSnapshot> search = new SearchState<>();
  private static volatile boolean starting;

  // The native walking search can rotate through 24 neighbor orders when breaking ties.
  private static final int WALK_MOVE_VARIANTS = 24;
  private static final Random variantRng = new Random();

  private static final Object avoidLock = new Object();
  private static final List<ManagedAvoidEntry> transientAvoidEntries = new ArrayList<>(8);

  private static final Object nativeSearchLock = new Object();

  private PathManager() {}

  public static boolean findPath(int startX, int startY, int startZ, int endX, int endY, int endZ) {
    return findPath(startX, startY, startZ, endX, endY, endZ, 500_000);
  }

  public static boolean findPath(
      int startX, int startY, int startZ, int endX, int endY, int endZ, int maxIterations) {
    return findPathInternal(
        new int[][] {{startX, startY, startZ}}, new int[][] {{endX, endY, endZ}}, maxIterations);
  }

  private static synchronized boolean findPathInternal(
      int[][] startPoints, int[][] endPoints, int maxIterations) {
    starting = true;
    try {
      return startSearch(startPoints, endPoints, maxIterations);
    } finally {
      starting = false;
    }
  }

  private static boolean startSearch(int[][] startPoints, int[][] endPoints, int maxIterations) {
    cancelActiveSearch();

    final long generation = search.generation();
    search.finish(generation, null, null);

    if (maxIterations <= 0) {
      search.finish(generation, null, "maxIterations must be > 0");
      return false;
    }

    final String startValidation = validatePoints("Start points", startPoints);
    if (startValidation != null) {
      search.finish(generation, null, startValidation);
      return false;
    }

    final String endValidation = validatePoints("End points", endPoints);
    if (endValidation != null) {
      search.finish(generation, null, endValidation);
      return false;
    }

    final String nativeValidation = validateNativeAvailability();
    if (nativeValidation != null) {
      search.finish(generation, null, nativeValidation);
      return false;
    }

    final String heightValidation = validateHeights(startPoints, endPoints);
    if (heightValidation != null) {
      search.finish(generation, null, heightValidation);
      return false;
    }

    final NativeAvoidZone[] avoidZones = consumeTransientAvoidZones();
    final int[] avoidMeta = encodeAvoidMeta(avoidZones);
    final double[] avoidPenalty = encodeAvoidPenalty(avoidZones);

    final NativePathSearchRequest request =
        new NativePathSearchRequest(
            flattenPoints(startPoints),
            flattenPoints(endPoints),
            maxIterations,
            heuristicWeight(),
            nextVariantOffset(),
            avoidMeta,
            avoidPenalty);

    // Publish the task before execute: the executor may run it inline under saturation.
    final FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              runSearch(generation, request);
              return null;
            });
    search.start(task);
    try {
      Swift.executor.execute(task);
      return true;
    } catch (RuntimeException e) {
      search.finish(
          generation, null, e.getMessage() != null ? e.getMessage() : "Pathfinder could not start");
      return false;
    }
  }

  private static void runSearch(long generation, NativePathSearchRequest request) {
    PathSnapshot snapshot = null;
    String error = null;
    try {
      final NativePathResult result;
      synchronized (nativeSearchLock) {
        if (generation != search.generation()) {
          return;
        }
        result = NativePathfinderBridge.findPath(request);
        if (result == null || result.path().length == 0) {
          error = NativePathfinderBridge.getLastError();
          if (error == null) {
            error = "Pathfinder found no path";
          }
        }
      }
      if (error == null && generation == search.generation()) {
        final List<BlockPos> points = toBlockPosList(result.path());
        snapshot =
            new PathSnapshot(
                points,
                toBlockPosList(result.keyPath().length > 0 ? result.keyPath() : result.path()),
                movementFlags(points, result.pathFlags()),
                result.timeMs(),
                result.nodesExplored(),
                result.nanosecondsPerNode());
      }
    } catch (Exception e) {
      error = e.getMessage() != null ? e.getMessage() : "Pathfinder failed for an unknown reason";
      logger.error("[ryzn] native pathfinding failed", e);
    } finally {
      search.finish(generation, snapshot, error);
    }
  }

  private static int[] movementFlags(List<BlockPos> points, int[] nativeFlags) {
    final int[] flags = java.util.Arrays.copyOf(nativeFlags, points.size());
    for (int i = 1; i < points.size(); i++) {
      final BlockPos previous = points.get(i - 1);
      final BlockPos next = points.get(i);
      if (next.getY() != previous.getY() + 1) {
        continue;
      }
      final Short support = CachedWorld.getBlockCode(next.getX(), next.getY() - 1, next.getZ());
      if (support != null
          && (support & (NativeVoxelFlags.SLAB_BOTTOM | NativeVoxelFlags.STAIRS_BOTTOM)) != 0) {
        flags[i - 1] |= FLAG_STEP_UP_HALF;
      }
    }
    return flags;
  }

  private static int[] flattenPoints(int[][] points) {
    final int[] out = new int[points.length * 3];
    int idx = 0;
    for (int[] p : points) {
      out[idx++] = p[0];
      out[idx++] = p[1];
      out[idx++] = p[2];
    }
    return out;
  }

  private static List<BlockPos> toBlockPosList(int[] flatPath) {
    if (flatPath.length == 0 || flatPath.length % 3 != 0) {
      return List.of();
    }
    final List<BlockPos> out = new ArrayList<>(flatPath.length / 3);
    for (int idx = 0; idx + 2 < flatPath.length; idx += 3) {
      out.add(new BlockPos(flatPath[idx], flatPath[idx + 1], flatPath[idx + 2]));
    }
    return out;
  }

  private static int[] encodeAvoidMeta(NativeAvoidZone[] zones) {
    if (zones.length == 0) {
      return new int[0];
    }
    final int[] meta = new int[zones.length * 5];
    int idx = 0;
    for (NativeAvoidZone z : zones) {
      meta[idx++] = z.blockX;
      meta[idx++] = z.blockY;
      meta[idx++] = z.blockZ;
      meta[idx++] = z.radiusSq;
      meta[idx++] = z.maxDeltaY;
    }
    return meta;
  }

  private static double[] encodeAvoidPenalty(NativeAvoidZone[] zones) {
    if (zones.length == 0) {
      return new double[0];
    }
    final double[] out = new double[zones.length];
    for (int i = 0; i < zones.length; i++) {
      out[i] = zones[i].penalty;
    }
    return out;
  }

  private static String validateNativeAvailability() {
    if (NativePathfinderBridge.isAvailable()) {
      return null;
    }
    final String err = NativePathfinderBridge.getLastError();
    return err != null ? err : "Pathfinder unavailable";
  }

  private static String validatePoints(String label, int[][] points) {
    if (points.length == 0) {
      return label + " are required";
    }
    for (int[] p : points) {
      if (p.length != 3) {
        return label + " must contain [x, y, z] points";
      }
    }
    return null;
  }

  private static String validateHeights(int[][] startPoints, int[][] endPoints) {
    final ClientLevel level = Minecraft.getInstance().level;
    if (level == null) {
      return "World is not loaded";
    }

    final int bottomY = level.getMinY();
    final int inclusiveTopY = level.getMaxY();

    final int minFeetY = bottomY + 1;
    final int maxFeetY = inclusiveTopY - 1;
    for (int[] p : startPoints) {
      if (p[1] < minFeetY || p[1] > maxFeetY) {
        return "Walk start Y must be between " + minFeetY + " and " + maxFeetY;
      }
    }
    for (int[] p : endPoints) {
      if (p[1] < minFeetY || p[1] > maxFeetY) {
        return "Walk end Y must be between " + minFeetY + " and " + maxFeetY;
      }
    }
    return null;
  }

  private static NativeAvoidZone[] consumeTransientAvoidZones() {
    synchronized (avoidLock) {
      if (transientAvoidEntries.isEmpty()) {
        return new NativeAvoidZone[0];
      }

      final List<NativeAvoidZone> zones = new ArrayList<>(transientAvoidEntries.size());
      transientAvoidEntries.removeIf(e -> e.ttlSearches <= 0);

      for (ManagedAvoidEntry entry : transientAvoidEntries) {
        final int radius = Math.max(1, entry.radius);
        zones.add(
            new NativeAvoidZone(
                entry.blockX,
                entry.blockY,
                entry.blockZ,
                radius * radius,
                Math.max(0.0, entry.penalty),
                AVOID_MAX_Y_DIFF));
        entry.ttlSearches--;
      }

      transientAvoidEntries.removeIf(e -> e.ttlSearches <= 0);
      return zones.toArray(new NativeAvoidZone[0]);
    }
  }

  public static boolean isSearching() {
    return starting || search.isSearching();
  }

  public static synchronized void cancelSearch() {
    cancelActiveSearch();
  }

  private static void cancelActiveSearch() {
    final FutureTask<Void> task = search.invalidate();
    if (task != null) {
      task.cancel(true);
      Swift.executor.remove(task);
    }
    NativePathfinderBridge.cancelSearch();
  }

  /** Floor-block coordinates and associated movement flags from one completed search. */
  public record RouteData(int[] dense, int[] keyNodes, int[] movementFlags) {}

  public static RouteData getRouteData() {
    final PathSnapshot snapshot = search.outcome().result();
    if (snapshot == null) {
      return new RouteData(new int[0], new int[0], new int[0]);
    }
    return new RouteData(
        packPoints(snapshot.points(), -1),
        packPoints(snapshot.keyNodes(), -1),
        snapshot.movementFlags().clone());
  }

  private static int[] packPoints(List<BlockPos> points, int offsetY) {
    final int[] result = new int[points.size() * 3];
    int idx = 0;
    for (BlockPos p : points) {
      result[idx++] = p.getX();
      result[idx++] = p.getY() + offsetY;
      result[idx++] = p.getZ();
    }
    return result;
  }

  public static void addTransientAvoidPoint(int x, int y, int z) {
    addTransientAvoidPoint(x, y, z, 2, 36.0, 2);
  }

  public static void addTransientAvoidPoint(
      int x, int y, int z, int radius, double penalty, int ttlSearches) {
    final int clampedRadius = Math.clamp(radius, MIN_AVOID_RADIUS, MAX_AVOID_RADIUS);
    final double clampedPenalty = Math.clamp(penalty, MIN_AVOID_PENALTY, MAX_AVOID_PENALTY);
    final int clampedTtl = Math.clamp(ttlSearches, MIN_AVOID_TTL_SEARCHES, MAX_AVOID_TTL_SEARCHES);

    synchronized (avoidLock) {
      ManagedAvoidEntry existing = null;
      for (ManagedAvoidEntry e : transientAvoidEntries) {
        if (e.blockX == x && e.blockY == y && e.blockZ == z && e.radius == clampedRadius) {
          existing = e;
          break;
        }
      }
      if (existing != null) {
        existing.ttlSearches = Math.max(existing.ttlSearches, clampedTtl);
        existing.penalty = Math.max(existing.penalty, clampedPenalty);
      } else {
        transientAvoidEntries.add(
            new ManagedAvoidEntry(x, y, z, clampedRadius, clampedPenalty, clampedTtl));
      }
    }
  }

  public static int getPathSize() {
    final PathSnapshot s = search.outcome().result();
    return s != null ? s.points().size() : 0;
  }

  public static long getLastTimeMs() {
    final PathSnapshot s = search.outcome().result();
    return s != null ? s.timeMs() : -1L;
  }

  public static int getNodesExplored() {
    final PathSnapshot s = search.outcome().result();
    return s != null ? s.nodesExplored() : 0;
  }

  public static double getNanosecondsPerNode() {
    final PathSnapshot s = search.outcome().result();
    return s != null ? s.nanosecondsPerNode() : 0.0;
  }

  /**
   * The A* heuristic weight sent to the native search (1.0 = optimal; >1 = weighted/greedier, fewer
   * expansions).
   */
  public static double getHeuristicWeight() {
    return heuristicWeight();
  }

  static int nextVariantOffset() {
    return variantRng.nextInt(WALK_MOVE_VARIANTS);
  }

  public static String getLastError() {
    return search.outcome().error();
  }

  public static boolean hasPath() {
    return search.outcome().result() != null;
  }

  public static synchronized void clear() {
    cancelActiveSearch();
    search.finish(search.generation(), null, null);
  }
}
