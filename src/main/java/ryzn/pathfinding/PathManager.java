package ryzn.pathfinding;

import com.chattriggers.ctjs.api.world.pathfinding.NativePathResult;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
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

/**
 * for "compute me a route": kick off a search on a worker thread, expose the latest result via
 * getters. This class does NOT walk the bot; that's the executor's job (see PathExecutor).
 *
 * <p>Trimmed from the upstream: Hypixel area detection (the ryzn project dropped Hypixel-specific
 * integrations), and CT TabList helpers.
 */
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

  private static volatile PathSnapshot currentPath = null;
  private static volatile int[] currentPathFlags = new int[0];
  @Getter private static volatile String lastError = null;
  private static final SearchActivity searchActivity = new SearchActivity();

  /**
   * Anti-detection: the native rotates its neighbour scan order by this offset, so equal-cost ties
   * resolve to a different (equally short) route each search. Same cost, different nodes - a grind
   * route walked 50 times stops tracing one identical polyline for server-side analysis to latch
   * onto.
   */
  private static final int WALK_MOVE_VARIANTS =
      24; // native WALK_MOVES.length; it mods by len anyway

  private static final Random variantRng = new Random();
  // Per-phase timings (ns) from the last search, for the /pathfind debug breakdown. See
  // NativePathResult.

  private static final Object avoidLock = new Object();
  private static final List<ManagedAvoidEntry> transientAvoidEntries = new ArrayList<>(8);

  private static volatile Future<?> currentTask = null;
  private static final AtomicInteger searchId = new AtomicInteger(0);
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

  private static boolean findPathInternal(
      int[][] startPoints, int[][] endPoints, int maxIterations) {
    searchActivity.beginStart();
    try {
      return startPath(startPoints, endPoints, maxIterations);
    } finally {
      searchActivity.endStart();
    }
  }

  private static boolean startPath(int[][] startPoints, int[][] endPoints, int maxIterations) {
    cancelActiveSearch();

    lastError = null;
    currentPath = null;
    currentPathFlags = new int[0];

    if (maxIterations <= 0) {
      lastError = "maxIterations must be > 0";
      return false;
    }

    final String startValidation = validatePoints("Start points", startPoints);
    if (startValidation != null) {
      lastError = startValidation;
      return false;
    }

    final String endValidation = validatePoints("End points", endPoints);
    if (endValidation != null) {
      lastError = endValidation;
      return false;
    }

    final String nativeValidation = validateNativeAvailability();
    if (nativeValidation != null) {
      lastError = nativeValidation;
      return false;
    }

    final String heightValidation = validateHeights(startPoints, endPoints);
    if (heightValidation != null) {
      lastError = heightValidation;
      return false;
    }

    final NativeAvoidZone[] avoidZones = consumeTransientAvoidZones();
    final int[] avoidMeta = encodeAvoidMeta(avoidZones);
    final double[] avoidPenalty = encodeAvoidPenalty(avoidZones);

    final int currentId = searchId.incrementAndGet();
    searchActivity.markRunning();
    NativePathfinderBridge.cancelSearch();

    final int[] startFlat = flattenPoints(startPoints);
    final int[] endFlat = flattenPoints(endPoints);

    try {
      currentTask =
          Swift.executor.submit(
              () -> {
                try {
                  final NativePathResult result;
                  synchronized (nativeSearchLock) {
                    result =
                        NativePathfinderBridge.findPath(
                            new NativePathSearchRequest(
                                startFlat,
                                endFlat,
                                maxIterations,
                                heuristicWeight(),
                                nextVariantOffset(),
                                avoidMeta,
                                avoidPenalty));
                  }

                  if (searchId.get() != currentId) {
                    return;
                  }

                  if (result != null && result.path().length > 0) {
                    final List<BlockPos> points = toBlockPosList(result.path());
                    final List<BlockPos> keyNodes =
                        toBlockPosList(
                            result.keyPath().length > 0 ? result.keyPath() : result.path());

                    currentPath =
                        new PathSnapshot(
                            points,
                            keyNodes,
                            result.timeMs(),
                            result.nodesExplored(),
                            result.nanosecondsPerNode());
                    currentPathFlags = movementFlags(points, result.pathFlags());

                    lastError = null;
                  } else {
                    currentPath = null;
                    currentPathFlags = new int[0];
                    final String err = NativePathfinderBridge.getLastError();
                    lastError = err != null ? err : "Pathfinder found no path";
                  }
                } catch (Exception e) {
                  if (searchId.get() == currentId) {
                    lastError =
                        e.getMessage() != null
                            ? e.getMessage()
                            : "Pathfinder failed for an unknown reason";
                    logger.error("[ryzn] native pathfinding failed", e);
                  }
                } finally {
                  if (searchId.get() == currentId) {
                    searchActivity.finishRunning();
                    currentTask = null;
                  }
                }
              });
    } catch (Exception e) {
      if (searchId.get() == currentId) {
        searchActivity.finishRunning();
        lastError = e.getMessage() != null ? e.getMessage() : "Pathfinder could not start a search";
      }
      return false;
    }
    return true;
  }

  record DetachedAttempt<T>(T result, boolean retryable) {}

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
    return searchActivity.isBusy();
  }

  public static void cancelSearch() {
    searchActivity.cancelAll();
    cancelActiveSearch();
  }

  private static void cancelActiveSearch() {
    final Future<?> t = currentTask;
    if (t != null) {
      t.cancel(true);
    }
    currentTask = null;
    searchId.incrementAndGet();
    searchActivity.finishRunning();
    NativePathfinderBridge.cancelSearch();
  }

  static final class SearchActivity {
    private final AtomicInteger starts = new AtomicInteger();
    private volatile boolean running;

    void beginStart() {
      starts.incrementAndGet();
    }

    void endStart() {
      starts.updateAndGet(value -> Math.max(0, value - 1));
    }

    void markRunning() {
      running = true;
    }

    void finishRunning() {
      running = false;
    }

    void cancelAll() {
      starts.set(0);
      running = false;
    }

    boolean isBusy() {
      return starts.get() > 0 || running;
    }
  }

  public static int[] getPathArray() {
    final PathSnapshot snapshot = currentPath;
    if (snapshot == null) {
      return new int[0];
    }
    return packPoints(snapshot.points(), -1);
  }

  public static int[] getKeyNodesArray() {
    final PathSnapshot snapshot = currentPath;
    if (snapshot == null) {
      return new int[0];
    }
    return packPoints(snapshot.keyNodes(), -1);
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

  public static int[] getPathFlagsArray() {
    return currentPathFlags;
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
    final PathSnapshot s = currentPath;
    return s != null ? s.points().size() : 0;
  }

  public static long getLastTimeMs() {
    final PathSnapshot s = currentPath;
    return s != null ? s.timeMs() : -1L;
  }

  public static int getNodesExplored() {
    final PathSnapshot s = currentPath;
    return s != null ? s.nodesExplored() : 0;
  }

  public static double getNanosecondsPerNode() {
    final PathSnapshot s = currentPath;
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

  public static boolean hasPath() {
    return currentPath != null;
  }

  public static void clear() {
    cancelSearch();
    currentPath = null;
    currentPathFlags = new int[0];
    lastError = null;
  }
}
