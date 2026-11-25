package ryzn.macro.client.feature.pathfinder;

import java.util.List;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.pathfinder.path.PathExecutor;
import ryzn.macro.client.feature.pathfinder.path.PathNode;
import ryzn.macro.client.feature.pathfinder.path.PathSearchStart;
import ryzn.macro.client.util.text.RyznChat;
import ryzn.pathfinding.PathManager;
import ryzn.swift.cache.CachedWorld;
import ryzn.swift.nativepath.NativePathfinderJNI;

/** Client-thread entry point for planning, driving, and stopping the shared walking route. */
public final class WalkingNavigation {
  private static final int MAX_ITERATIONS = 500_000;
  private static final int MAX_PENDING_TICKS = 200; // ~10s safety cap on polling
  private static final int MAX_CACHE_READY_TICKS =
      100; // ~5s for cache key settle + disk load + native resync

  // Auto-repath budget. The executor calls requestReplan() when it gets stuck / drifts / is
  // obstructed;
  // we recompute a fresh route from the player's current position to the stored goal. Capped per
  // navigation (reset on a new /ryzn pathfind command and on arrival) so a genuinely unreachable
  // goal can't
  // loop forever - once the budget is spent, requestReplan() returns false and the executor gives
  // up.
  private static final int MAX_REPLANS = 48;
  private static int replanCount = 0;

  private static boolean searchActive = false;
  private static boolean waitingForCache = false;
  private static boolean pendingResetReplans = true;
  private static int pendingTicks = 0;
  private static boolean searchNotify = true;

  /**
   * Aqua tag, white body. The body default in {@link RyznChat} is grey, so the §f is not redundant.
   */
  private static final String TAG = "§bPathfinder§7: §f";

  /**
   * Non-null while the in-flight search is an auto-repath: its human-readable cause, already
   * announced. Suppresses the "Pathing to" / "preparing cache" lines so a repath reports itself
   * exactly once.
   */
  private static String replanReason = null;

  // Combat hot-swap: when true the in-flight search arms via PathExecutor.hotSwapPath (no executor
  // stop, keys
  // stay held) and the previous route keeps driving until it resolves. Set only by startToHotSwap;
  // every other
  // entry (normal /pathfind, executor replan) clears it, so /pathfind stop/restart behaviour is
  // unchanged.
  private static boolean pendingHotSwap = false;

  private static int goalX;
  private static int goalY;
  private static int goalZ;

  // Monotonic counter bumped whenever a search resolves with NO usable path (no route found, or it
  // timed
  // out). Read-only signal for callers like the combat module, which polls it to blacklist a target
  // it
  // repeatedly fails to reach. Bumping it never alters pathing - the engine is untouched.
  @Getter private static volatile int navFailSeq = 0;

  private static boolean previewThisSearch;

  private WalkingNavigation() {}

  /** Starts a command request, optionally drawing the resulting route without driving it. */
  public static boolean startFromCommand(int x, int y, int z, boolean preview) {
    return beginNavigation(Minecraft.getInstance(), x, y, z, true, false, preview);
  }

  private static boolean startWalking(int x, int y, int z, boolean hotSwap) {
    final Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || !NativePathfinderJNI.isAvailable()) {
      return false;
    }
    return beginNavigation(mc, x, y, z, false, hotSwap, false);
  }

  /** Queues a walk to a block goal. True means the request was accepted for planning. */
  public static boolean startToQuiet(int x, int y, int z) {
    return startWalking(x, y, z, false);
  }

  /**
   * Combat-only: queue a new route that HOT-SWAPS into the running executor when it resolves,
   * without stopping the current route first (the body keeps moving, no {@code keys=none} during
   * the recompute).
   *
   * @param x the goal block X
   * @param y the goal block Y
   * @param z the goal block Z
   * @return true when the search started
   */
  public static boolean startToHotSwap(int x, int y, int z) {
    return startWalking(x, y, z, true);
  }

  /** Cancels the pending search, releases executor input, and applies overlay retention. */
  public static void stopNavQuiet() {
    PathExecutor.instance.stop();
    PathManager.cancelSearch();
    clearSearchState();
    clearDebugOverlayUnlessKept();
  }

  /**
   * Crouch-walk the live route (and never sprint). Callers re-assert it every tick.
   *
   * @param on whether the route is walked crouched
   */
  public static void setSneak(boolean on) {
    PathExecutor.instance.setSneak(on);
  }

  public static boolean isNavigationActive() {
    return searchActive || PathManager.isSearching() || PathExecutor.instance.isActive();
  }

  /**
   * True only while a resolved route is DRIVING the body. Unlike {@link #isNavigationActive()} this
   * is false while a search is still in flight, so a caller can tell "my search turned into a
   * route" from "my search has not reported back yet".
   *
   * @return true while a resolved route drives the body
   */
  public static boolean isRouteDriving() {
    return PathExecutor.instance.isActive();
  }

  /**
   * World yaw the live route is walking toward, or {@code Float.NaN} while the executor is idle.
   *
   * @return the route's heading yaw, or {@code Float.NaN} when nothing is driving
   */
  public static float routeHeadingYaw() {
    return PathExecutor.instance.getRouteHeadingYaw();
  }

  /**
   * Smoothed sibling of {@link #routeHeadingYaw()}, taken from the look-path curve instead of the
   * node the cursor sits on. Prefer this in a take/release gate; the node bearing steps at corners.
   *
   * @return the smoothed heading yaw, or {@code Float.NaN} when nothing is driving
   */
  public static float lookPathHeadingYaw() {
    return PathExecutor.instance.getLookPathHeadingYaw();
  }

  private static boolean beginNavigation(
      Minecraft mc, int x, int y, int z, boolean notify, boolean hotSwap, boolean preview) {
    final LocalPlayer player = mc.player;
    if (player == null) {
      return false;
    }

    if (isSameGoal(x, y, z) && isNavigationActive() && previewThisSearch == preview) {
      return true;
    }

    goalX = x;
    goalY = y;
    goalZ = z;

    previewThisSearch = preview;
    pendingHotSwap = hotSwap;
    if (!hotSwap) {
      PathExecutor.instance
          .stop(); // normal: stop the live route (releases keys) before the new search
    }
    PathManager.cancelSearch();
    replanCount = 0;

    return waitForCacheThenSearch(mc, notify, true);
  }

  private static boolean queueSearchFromCurrentPosition(
      Minecraft mc, boolean notify, boolean resetReplans) {
    final LocalPlayer player = mc.player;
    if (player == null) {
      return false;
    }

    BlockPos start =
        PathSearchStart.groundedFeetCell(
            player.blockPosition(), player.mainSupportingBlockPos, player.onGround());
    final int sx = start.getX();
    final int sy = start.getY();
    final int sz = start.getZ();

    PathManager.cancelSearch();

    // Reconcile the goal's chunk from the live world before searching: after a warp the client may
    // have it
    // loaded while Ryzn missed its (cleared) initial packet, which otherwise makes every search
    // to a goal
    // in that chunk return no-path until the player happens to move. No-op if already cached or not
    // loaded.
    CachedWorld.forceSyncGoalChunk(goalX >> 4, goalZ >> 4);

    final BlockPos goal =
        PathSearchStart.standableGoalCell(
            new BlockPos(goalX, goalY, goalZ),
            CachedWorld.getBlockCode(goalX, goalY, goalZ),
            CachedWorld.getBlockCode(goalX, goalY + 1, goalZ));
    if (goal.getY() != goalY) {
      DebugLog.detail(
          DebugLog.Category.NAV,
          "goal %d %d %d is not standable; searching to %d %d %d",
          goalX,
          goalY,
          goalZ,
          goal.getX(),
          goal.getY(),
          goal.getZ());
    }

    final boolean queued =
        PathManager.findPath(sx, sy, sz, goal.getX(), goal.getY(), goal.getZ(), MAX_ITERATIONS);
    if (!queued) {
      final String err = PathManager.getLastError();
      if (notify) {
        RyznChat.send(
            TAG + "§ccould not start the search. §7" + (err != null ? err : "Reason unknown."));
      }
      clearSearchState();
      return false;
    }

    searchActive = true;
    waitingForCache = false;
    searchNotify = notify;
    pendingTicks = 0;
    if (resetReplans) {
      replanCount = 0;
    }
    if (notify && replanReason == null) {
      RyznChat.send(
          TAG
              + "walking to "
              + goalX
              + " "
              + goalY
              + " "
              + goalZ
              + ". §7Run §f/ryzn pathfind stop§7 to cancel.");
    }
    return true;
  }

  private static boolean waitForCacheThenSearch(
      Minecraft mc, boolean notify, boolean resetReplans) {
    searchActive = true;
    waitingForCache = true;
    pendingResetReplans = resetReplans;
    searchNotify = notify;
    pendingTicks = 0;

    CachedWorld.prepareForPathSearch(mc);
    if (CachedWorld.isPathSearchReady()) {
      return queueSearchFromCurrentPosition(mc, notify, resetReplans);
    }

    if (notify && replanReason == null) {
      RyznChat.send(
          TAG
              + "loading the blocks around you before planning a route to "
              + goalX
              + " "
              + goalY
              + " "
              + goalZ
              + "...");
    }
    DebugLog.detail(
        DebugLog.Category.NAV,
        "waiting for the world cache to fill: %s",
        CachedWorld.getDiagnostics());
    return true;
  }

  private static boolean isSameGoal(int x, int y, int z) {
    return goalX == x && goalY == y && goalZ == z;
  }

  private static void clearSearchState() {
    searchActive = false;
    waitingForCache = false;
    replanReason = null;
    pendingResetReplans = true;
    pendingTicks = 0;
  }

  /**
   * Requests a new route to the stored goal from the player's current position. The executor keeps
   * its current route until the search resolves. Returns false when the replan cannot be queued.
   */
  public static boolean requestReplan(String reason) {
    final Minecraft mc = Minecraft.getInstance();
    final LocalPlayer player = mc.player;
    if (player == null || !NativePathfinderJNI.isAvailable()) {
      return false;
    }
    if (++replanCount > MAX_REPLANS) {
      return false;
    }
    replanReason = (reason == null || reason.isBlank()) ? "a blocked route" : reason;
    if (searchNotify && reason != null) {
      RyznChat.send(TAG + "re-planning the route - " + replanReason + ".");
    }
    // Hot-swap, so a replan retargets the live route instead of stopping it. The full stop path
    // releases
    // every movement key (askReplan) and then re-arms through startPath, which resets the rotation
    // controller too - a guaranteed freeze of at least a tick, measured up to 3s in the logs, and
    // the
    // reason a wedge replan reads as a stutter rather than a correction. hotSwapPrecisePath falls
    // back to
    // a clean start on its own when nothing is running, which covers the give-up-and-restart case.
    pendingHotSwap = true;
    PathManager.cancelSearch();
    return waitForCacheThenSearch(mc, searchNotify, false);
  }

  /**
   * True while a search is in flight. The executor checks this so a failsafe that keeps firing
   * during the couple of ticks a replan takes asks once instead of once per tick (which used to
   * burn the whole {@link #MAX_REPLANS} budget in under a second).
   *
   * @return true while a search is in flight
   */
  public static boolean isSearchPending() {
    return searchActive;
  }

  /** The executor reached the goal - clear the replan budget for the next obstruction. */
  public static void notifyArrived() {
    replanCount = 0;
    clearDebugOverlayUnlessKept();
    if (searchNotify) {
      RyznChat.send(TAG + "arrived at " + goalX + " " + goalY + " " + goalZ + ".");
    }
  }

  /**
   * When a route ends, drop the debug overlay's drawn path unless the user opted to keep it on
   * screen. The overlay toggle itself stays on, so the next route re-populates it.
   */
  private static void clearDebugOverlayUnlessKept() {
    if (!RyznSettings.get().pathfinder().isPathDebugKeepAfterFinish()) {
      PathRenderer.clear();
    }
  }

  /**
   * Polled once per client tick by the registered pathfinder callback.
   *
   * @param mc the client whose player the route drives
   */
  public static void tick(Minecraft mc) {
    if (!searchActive) {
      return;
    }
    if (waitingForCache) {
      CachedWorld.prepareForPathSearch(mc);
      if (CachedWorld.isPathSearchReady()) {
        queueSearchFromCurrentPosition(mc, searchNotify, pendingResetReplans);
        return;
      }
      if (++pendingTicks > MAX_CACHE_READY_TICKS) {
        final String diag = CachedWorld.getDiagnostics();
        if (searchNotify) {
          RyznChat.send(
              TAG
                  + "§cthe blocks around the route never finished loading, so no route was "
                  + "planned. §7Walk closer to the goal and try again. §8("
                  + diag
                  + ")");
        }
        DebugLog.error(
            DebugLog.Category.NAV,
            "Gave up waiting for the world around you to load, so no path was planned (%s)",
            diag);
        PathManager.cancelSearch();
        clearSearchState();
      }
      return;
    }
    if (PathManager.isSearching()) {
      if (++pendingTicks > MAX_PENDING_TICKS) {
        final String diag = CachedWorld.getDiagnostics();
        if (searchNotify) {
          RyznChat.send(
              TAG
                  + "§cthe route search took too long and was cancelled. §7Pick a closer "
                  + "goal and try again. §8("
                  + diag
                  + ")");
        }
        DebugLog.error(
            DebugLog.Category.NAV, "Path search took too long and was cancelled (%s)", diag);
        navFailSeq++;
        PathManager.cancelSearch();
        clearSearchState();
      }
      return;
    }

    // Search finished - collect the result.
    clearSearchState();
    if (!PathManager.hasPath()) {
      navFailSeq++;
      final String err = PathManager.getLastError();
      final LocalPlayer self = mc.player;
      final String probe =
          self == null
              ? "no player"
              : CachedWorld.describeProbe(
                  Mth.floor(self.getX()),
                  Mth.floor(self.getY()),
                  Mth.floor(self.getZ()),
                  goalX,
                  goalY,
                  goalZ);
      DebugLog.error(
          DebugLog.Category.NAV,
          "%s (world cache: %s; %s)",
          err != null ? err : "Pathfinder found no path",
          CachedWorld.getDiagnostics(),
          probe);
      if (searchNotify) {
        RyznChat.send(
            TAG
                + "§c"
                + (err != null ? err : "Pathfinder found no path")
                + ". §7Pick a spot you could walk to yourself, or move closer first.");
      }
      return;
    }

    final PathManager.RouteData route = PathManager.getRouteData();
    final int[] keys = route.keyNodes();
    final int denseCount = route.dense().length / 3;
    final int keyCount = keys.length / 3;
    final ClientLevel keyLevel = mc.level;
    // Corridor probes read the live world and are only needed in the navigation trace.
    if (DebugLog.isEnabled(DebugLog.Category.NAV)) {
      DebugLog.detail(
          DebugLog.Category.NAV, "keynodes: %d (goal %d %d %d)", keyCount, goalX, goalY, goalZ);
      for (int k = 0; k < keyCount; k++) {
        final int kx = keys[k * 3];
        final int ky = keys[k * 3 + 1];
        final int kz = keys[k * 3 + 2];
        // Also log the corridor width: distance to the nearest 2-tall WALL in each cardinal
        // direction
        // (99 = clear within range). Reveals whether the route hugs a wall because it has to
        // (narrow)
        // or has open floor to swing wider.
        DebugLog.detail(
            DebugLog.Category.NAV,
            "keynode %d: %d %d %d | wallClear N=%d S=%d E=%d W=%d",
            k,
            kx,
            ky,
            kz,
            WalkingRouteBuilder.wallClearance(keyLevel, kx, ky, kz, 0, -1),
            WalkingRouteBuilder.wallClearance(keyLevel, kx, ky, kz, 0, 1),
            WalkingRouteBuilder.wallClearance(keyLevel, kx, ky, kz, 1, 0),
            WalkingRouteBuilder.wallClearance(keyLevel, kx, ky, kz, -1, 0));
      }
    }
    if (denseCount < 2) {
      if (searchNotify) {
        RyznChat.send(TAG + "§eyou are already there.");
      }
      return;
    }

    final WalkingRouteBuilder.Route walkingRoute =
        WalkingRouteBuilder.build(
            mc.level, route, RyznSettings.get().pathfinder().waypointGapBlocks());
    final List<PathNode> nodes = walkingRoute.nodes();
    final int count = walkingRoute.waypoints().size();
    PathRenderer.setPath(walkingRoute.waypoints());

    if (searchNotify && PathRenderer.isEnabled()) {
      RyznChat.send(
          String.format(
              "§8[pf] %d path / %d key | %d explored, %.0f ns/node, %d ms | w=%.2f | %s",
              PathManager.getPathSize(),
              count,
              PathManager.getNodesExplored(),
              PathManager.getNanosecondsPerNode(),
              PathManager.getLastTimeMs(),
              PathManager.getHeuristicWeight(),
              CachedWorld.getCacheStats()));
    }
    // The executor uses the final emitted cell for exact arrival.
    final BlockPos goal = nodes.isEmpty() ? null : nodes.get(nodes.size() - 1).cell();
    if (previewThisSearch) {
      // Preview publishes the overlay without starting movement.
      RyznChat.send(
          TAG
              + "drew the route to "
              + goalX
              + " "
              + goalY
              + " "
              + goalZ
              + " §7("
              + count
              + " nodes, "
              + PathManager.getPathSize()
              + " steps). "
              + "Not walking it - §b/dev pathfind preview§7 to turn preview off.");
      return;
    }
    if (pendingHotSwap) {
      PathExecutor.instance.hotSwapPrecisePath(nodes, goal);
    } else {
      PathExecutor.instance.startPrecisePath(nodes, goal);
    }
  }
}
