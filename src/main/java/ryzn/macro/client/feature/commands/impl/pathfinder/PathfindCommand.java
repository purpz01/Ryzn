package ryzn.macro.client.feature.commands.impl.pathfinder;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.debug.DebugControl;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.commands.api.RyznCommandSource;
import ryzn.macro.client.feature.commands.api.RyznCommands;
import ryzn.macro.client.feature.pathfinder.PathRenderer;
import ryzn.macro.client.feature.pathfinder.path.PathExecutor;
import ryzn.macro.client.feature.pathfinder.path.PathNode;
import ryzn.macro.client.feature.pathfinder.path.PathSearchStart;
import ryzn.macro.client.gui.RyznModulesScreen;
import ryzn.macro.client.util.text.RyznChat;
import ryzn.pathfinding.PathManager;
import ryzn.swift.cache.CachedWorld;
import ryzn.swift.nativepath.NativePathfinderJNI;

/**
 * Client-side {@code /ryzn pathfind <x> <y> <z>} - fires an async native search via {@link
 * PathManager}, decodes the resulting key-node sequence (with annotation bits for step-up
 * detection) into {@link PathRenderer.Waypoint}s for the overlay plus precise {@link PathNode}s for
 * the executor, and hands the route to {@link PathExecutor}. {@code /ryzn pathfind stop} cancels
 * both the search and the executor; {@code /dev debug} toggles the overlay + all debug chat.
 */
public final class PathfindCommand {

  private static final int MAX_ITERATIONS = 500_000;
  private static final int MAX_PENDING_TICKS = 200; // ~10s safety cap on polling
  private static final int MAX_CACHE_READY_TICKS =
      100; // ~5s for cache key settle + disk load + native resync

  // PathManager move-kind mapping for PathRenderer.Waypoint.action.
  private static final int A_WALK = 1;
  private static final int A_JUMP_UP = 3;
  private static final int A_SWIM = 4;

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
  private static boolean nativeReady = false;
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

  private PathfindCommand() {}

  /** The user-facing part: {@code /ryzn pathfind <x> <y> <z>} and {@code stop}. */
  public static void register(CommandDispatcher<RyznCommandSource> dispatcher) {
    dispatcher.register(
        RyznCommands.literal("ryzn")
            .then(
                RyznCommands.literal("pathfind")
                    .then(RyznCommands.literal("stop").executes(PathfindCommand::stopCmd))
                    .then(
                        RyznCommands.literal("settings")
                            .executes(
                                context -> {
                                  RyznModulesScreen.openWalkingOnNextTick();
                                  return 1;
                                }))
                    .then(
                        RyznCommands.argument("x", DoubleArgumentType.doubleArg())
                            .then(
                                RyznCommands.argument("y", DoubleArgumentType.doubleArg())
                                    .then(
                                        RyznCommands.argument("z", DoubleArgumentType.doubleArg())
                                            .executes(PathfindCommand::run))))));
  }

  /** Diagnostics, registered under {@code /dev}: debug output, probes and calibration. */
  public static void registerDev(CommandDispatcher<RyznCommandSource> dispatcher) {
    dispatcher.register(
        RyznCommands.literal("debug")
            .then(RyznCommands.literal("console").executes(PathfindCommand::debugConsoleCmd))
            .executes(PathfindCommand::debugCmd));
    dispatcher.register(
        RyznCommands.literal("pathfind")
            .then(RyznCommands.literal("preview").executes(PathfindCommand::previewCmd)));
  }

  private static int stopCmd(CommandContext<RyznCommandSource> ctx) {
    PathExecutor.instance.stop();
    PathManager.cancelSearch();
    ctx.getSource().sendFeedback(RyznChat.line("§bPathfinder§7: stopped walking."));
    clearSearchState();
    clearDebugOverlayUnlessKept();
    return 1;
  }

  /**
   * GUI entry point - same as {@code /ryzn pathfind <x> <y> <z>} but driven from the Ryzn Start
   * button (no command source). Reports progress to chat via {@link #say}.
   *
   * @param x the goal block X
   * @param y the goal block Y
   * @param z the goal block Z
   */
  public static void startTo(int x, int y, int z) {
    startTo(x, y, z, true, false);
  }

  private static boolean startTo(int x, int y, int z, boolean notify, boolean hotSwap) {
    final Minecraft mc = Minecraft.getInstance();
    final LocalPlayer player = mc.player;
    if (player == null) {
      return false;
    }
    if (!NativePathfinderJNI.isAvailable()) {
      if (notify) {
        RyznChat.send(
            TAG
                + "§cthe engine did not load, so walking routes are off. §7Reason: "
                + NativePathfinderJNI.getLoadError());
      }
      return false;
    }
    nativeReady = true;

    previewThisSearch = false; // macros and the GUI always walk, whatever preview mode says
    return beginNavigation(mc, x, y, z, notify, hotSwap);
  }

  public static boolean startToQuiet(int x, int y, int z) {
    return startTo(x, y, z, false, false);
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
    return startTo(x, y, z, false, true);
  }

  /**
   * GUI entry point - same as {@code /ryzn pathfind stop} (cancels the search and the executor).
   */
  public static void stopNav() {
    stopNav(true);
  }

  private static void stopNav(boolean notify) {
    PathExecutor.instance.stop();
    PathManager.cancelSearch();
    clearSearchState();
    clearDebugOverlayUnlessKept();
    if (notify) {
      RyznChat.send(TAG + "stopped walking.");
    }
  }

  public static void stopNavQuiet() {
    stopNav(false);
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
   * World yaw the live route is walking toward, or {@code Float.NaN} when nothing is driving. Read
   * by macros that hold the head off the path, so they can hand it back before the route resolves
   * onto S.
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

  /**
   * Prints what the cast simulation is being fed, for when the preview draws something impossible.
   */

  /**
   * Walks a fixed pitch spread, one cast each, to record a fit dataset. See {@link BobberSweep}.
   */

  /**
   * Retunes the cast model against every cast recorded this session, scored with the real
   * simulation so the fit sees actual terrain. Fitting outside the game could not: a replica has no
   * lake bed, and in shallow water a steep cast reaches it.
   */

  /** Drops the recorded casts, for when the spot changes and the old ones no longer describe it. */
  private static int debugCmd(CommandContext<RyznCommandSource> ctx) {
    final boolean on = DebugControl.toggle();
    final String sink = DebugLog.isChatEcho() ? "chat + log" : "log only";
    ctx.getSource()
        .sendFeedback(
            RyznChat.line(
                "§eRyzn Trace §7is now "
                    + (on ? "§aon" : "§coff")
                    + "§7 - route overlay plus messages to "
                    + sink
                    + "."));
    return 1;
  }

  /**
   * Toggle whether debug output also echoes to chat. Off is log-file only: every line still lands
   * in {@code logs/latest.log}, so nothing is lost for after-the-fact reading, but per-tick trace
   * output stops burying real chat.
   */
  private static int debugConsoleCmd(CommandContext<RyznCommandSource> ctx) {
    final RyznSettings cfg = RyznSettings.get();
    final boolean logOnly = !cfg.pathfinder().isPathDebugLogOnly(); // flipping to this
    cfg.pathfinder().setPathDebugLogOnly(logOnly);
    cfg.save();
    ctx.getSource()
        .sendFeedback(
            RyznChat.line(
                logOnly
                    ? "§eTrace messages§7 now go to logs/latest.log only. "
                        + "Run §f/dev debug console§7 to see them in chat again."
                    : "§eTrace messages§7 now go to chat and logs/latest.log."));
    return 1;
  }

  private static int run(CommandContext<RyznCommandSource> ctx) {
    final RyznCommandSource src = ctx.getSource();
    final LocalPlayer player = src.getPlayer();
    final ClientLevel level = src.getLevel();
    if (player == null || level == null) {
      src.sendError(RyznChat.line("§bPathfinder§7: §cjoin a world first."));
      return 0;
    }
    if (!ensureNative(src)) {
      return 0;
    }

    // Accept float coords (F3 / copied positions) as well as ints - doubleArg() parses both. Floor
    // each to the block that contains it, since the engine plans over the voxel grid.
    goalX = Mth.floor(DoubleArgumentType.getDouble(ctx, "x"));
    goalY = Mth.floor(DoubleArgumentType.getDouble(ctx, "y"));
    goalZ = Mth.floor(DoubleArgumentType.getDouble(ctx, "z"));

    previewThisSearch = previewMode;
    if (!beginNavigation(Minecraft.getInstance(), goalX, goalY, goalZ, true, false)) {
      final String err = PathManager.getLastError();
      src.sendError(
          RyznChat.line(
              "§bPathfinder§7: §ccould not start the search. §7"
                  + (err != null ? err : "Reason unknown.")));
      return 0;
    }
    return 1;
  }

  /**
   * Preview mode: {@code /ryzn pathfind x y z} plans and DRAWS the route but never walks it.
   *
   * <p>Deliberately not a per-search argument. The point is to look at route shapes one after
   * another - plan, look, plan somewhere else - and re-typing a modifier every time gets in the way
   * of that.
   */
  private static boolean previewMode = false;

  /**
   * Whether the search now in flight is a preview. Latched per search so a macro's route, which
   * comes in through {@link #startTo}, walks normally while preview mode is on.
   */
  private static boolean previewThisSearch = false;

  private static int previewCmd(CommandContext<RyznCommandSource> ctx) {
    previewMode = !previewMode;
    if (previewMode) {
      PathRenderer.setEnabled(true); // no point planning a route you cannot see
    }
    ctx.getSource()
        .sendFeedback(
            RyznChat.line(
                "§bPathfinder preview §7is now "
                    + (previewMode ? "§aon" : "§coff")
                    + "§7 - "
                    + (previewMode
                        ? "§7/ryzn pathfind will draw the route and NOT walk it."
                        : "§7routes will be walked again.")));
    return 1;
  }

  private static boolean beginNavigation(
      Minecraft mc, int x, int y, int z, boolean notify, boolean hotSwap) {
    final LocalPlayer player = mc.player;
    if (player == null) {
      return false;
    }

    if (isSameGoal(x, y, z) && isNavigationActive()) {
      return true;
    }

    goalX = x;
    goalY = y;
    goalZ = z;

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
   * Auto-repath. Called by the executor when it gets stuck / drifts / is obstructed: kick off a
   * fresh async search from the player's CURRENT position to the stored goal (the same poll loop in
   * {@link #tick} then re-arms the executor when it resolves). Returns {@code false} - telling the
   * executor to give up - when the replan budget is spent, we're not in a world, or the native
   * search won't start, so an unreachable goal can't loop forever.
   *
   * @return true when a replan started, false when the executor should give up
   */
  public static boolean requestReplan() {
    return requestReplan("");
  }

  /**
   * As {@link #requestReplan()}, with the human-readable cause the executor is repathing for. It is
   * announced once here rather than at the search-start sites, so a repath reads as one line
   * ("Repathed due to ...") instead of re-announcing the destination it is already walking to.
   *
   * @param reason the cause announced in chat, or null to replan without a chat line
   * @return true when a replan started, false when the executor should give up
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

  /** Lazily loads + verifies the native library, reporting any failure to the player. */
  private static boolean ensureNative(RyznCommandSource src) {
    if (nativeReady) {
      return true;
    }
    if (!NativePathfinderJNI.isAvailable()) {
      final String err = NativePathfinderJNI.getLoadError();
      src.sendError(
          RyznChat.line(
              "§bPathfinder§7: §cthe engine did not load, so walking routes are off. "
                  + "§7Reason: "
                  + err));
      return false;
    }
    nativeReady = true;
    return true;
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

    // Feed the executor a path made of the engine's KEY NODES (smoothed turn points) PLUS the
    // take-off
    // blocks of any *real jumps* (full-block ascents - the executor needs an explicit waypoint
    // there to know when to press the jump key). Half-step ascents (slab / front-of-stair) and
    // all descents are NOT emitted as extra waypoints - vanilla auto-step / gravity handle them
    // and emitting them just adds yaw-targets that make the head jerk on slopes.
    final int[] dense = PathManager.getPathArray();
    final int[] keys = PathManager.getKeyNodesArray();
    final int[] denseFlags = PathManager.getPathFlagsArray();
    final int denseCount = dense.length / 3;
    final int keyCount = keys.length / 3;
    // Diagnostic: dump the planned KEY NODES (turn points) to the debug log so the route shape is
    // inspectable after the fact - the move-trace only shows the smoothed executor position, not
    // where
    // the planner put its turns. Gated on /dev debug (NAV category). Coords are floor-block Y.
    DebugLog.detail(
        DebugLog.Category.NAV, "keynodes: %d (goal %d %d %d)", keyCount, goalX, goalY, goalZ);
    final ClientLevel keyLevel = mc.level;
    for (int k = 0; k < keyCount; k++) {
      final int kx = keys[k * 3];
      final int ky = keys[k * 3 + 1];
      final int kz = keys[k * 3 + 2];
      // Also log the corridor width: distance to the nearest 2-tall WALL in each cardinal direction
      // (99 = clear within range). Reveals whether the route hugs a wall because it has to (narrow)
      // or has open floor to swing wider.
      DebugLog.detail(
          DebugLog.Category.NAV,
          "keynode %d: %d %d %d | wallClear N=%d S=%d E=%d W=%d",
          k,
          kx,
          ky,
          kz,
          wallClearance(keyLevel, kx, ky, kz, 0, -1),
          wallClearance(keyLevel, kx, ky, kz, 0, 1),
          wallClearance(keyLevel, kx, ky, kz, 1, 0),
          wallClearance(keyLevel, kx, ky, kz, -1, 0));
    }
    if (denseCount < 2) {
      if (searchNotify) {
        RyznChat.send(TAG + "§eyou are already there.");
      }
      return;
    }

    // Find which dense indices correspond to engine key nodes. Keys are a subset of dense, in
    // order.
    // keyOrder keeps those dense indices in ascending order so a gap-filler can find the two key
    // nodes that bracket it and snap itself onto the straight chord between them (see the emit
    // loop).
    final java.util.HashSet<Integer> keyDenseIdx = new java.util.HashSet<>(keyCount * 2);
    final java.util.ArrayList<Integer> keyOrder = new java.util.ArrayList<>(keyCount);
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
    // Node-strictness sets the max gap in OPEN space (smaller = denser = less corner-cutting, but
    // robotic if forced everywhere). In a NARROW corridor we always emit dense (gap 1) regardless
    // of
    // the setting, so the polyline traces the corridor and the executor's width-validated carrot
    // can't
    // cut the corner. Tightness is detected from the LIVE world (walls actually beside the cell),
    // NOT
    // the native NEAR_WALL/TIGHT_CORRIDOR flags - those under-fired in real mazes, which is why
    // balanced still clipped while strictness 5 (dense everywhere) didn't. Result: balanced is
    // precise
    // in tight spots and smooth/natural in the open, with no need to touch the slider.
    final ClientLevel level = mc.level;
    final int maxGapBlocks = RyznSettings.get().pathfinder().waypointGapBlocks();
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
        final int action =
            medium == PathNode.Medium.SWIM_SURFACE ? A_SWIM : isRealJump ? A_JUMP_UP : A_WALK;
        // Turning point (engine key node / start / real jump) vs a gap-filler we inserted along a
        // straight run. The overlay highlights the former and shows the latter only as a small
        // marker.
        final boolean keyNode = !isGapFiller;
        double centerY = medium == PathNode.Medium.SWIM_SURFACE ? dy + 1.0 : dy;
        waypoints.add(
            new PathRenderer.Waypoint(dx, dy, dz, centerX, centerY, centerZ, action, keyNode));
        nodes.add(
            PathNode.precise(
                new BlockPos(dx, dy, dz), centerX, centerZ, isRealJump, medium, isGapFiller));
        lastX = dx;
        lastZ = dz;
      }
    }
    final int count = waypoints.size();

    PathRenderer.setPath(waypoints);
    // Perf split (the deciding measurement for the 1ms goal): nodes-explored vs ns-per-node tells
    // us
    // whether the search is expansion-bound (→ weight/hierarchy) or per-node-bound (→ kill the
    // per-node hashmap/edge-scan cost). w = heuristic weight (1.0 = optimal, >1 = greedier/fewer
    // nodes).
    if (searchNotify) {
      dbg(
          mc,
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
    // Goal = the final emitted node (preserves the executor's prior complete=true behaviour, where
    // the
    // last waypoint became the EXACT-mode destination).
    final BlockPos goal = nodes.isEmpty() ? null : nodes.get(nodes.size() - 1).cell();
    if (previewThisSearch) {
      // PathRenderer.setPath above has already drawn it, and nothing ticks once the search is
      // collected, so simply not arming the executor is the whole of preview mode.
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

  private static PathNode.Medium mediumFromFlags(int flags) {
    if ((flags & PathManager.FLAG_SWIM_SURFACE) != 0) {
      return PathNode.Medium.SWIM_SURFACE;
    }
    if ((flags & PathManager.FLAG_FLUID_FEET) != 0) {
      return PathNode.Medium.WADE;
    }
    return PathNode.Medium.LAND;
  }

  /**
   * A cell is "tight" if there's a wall within 1 block in ANY of the 8 horizontal directions at
   * body height - i.e. we're near a wall, where corner-cutting can clip the 0.6-wide hitbox. Tight
   * cells are emitted dense (gap 1), so the executor behaves exactly as node-strictness 5 does
   * there (which the user confirmed works reliably at low speed). Open ground (no wall within 1
   * block) keeps the smooth open-space spacing, so it isn't robotic. Detected from the LIVE world
   * (the native annotation under-fired in real mazes).
   */
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
        // A solid at body height is a genuine corner-clip wall only if it ALSO blocks head height
        // (a 2-tall obstruction). A single-block-tall solid beside the node is a step/tread the
        // body
        // walks onto - stairs, slabs, a slope - not a corridor wall. Flagging those as tight forced
        // the dense, un-snapped staircase that made descents wiggle: every stair node has the next
        // tread up sitting solid at body height diagonally behind it, so the whole run read as
        // tight,
        // emitted gap-1, and skipped the chord straighten. Requiring the head block too leaves real
        // corridor walls tight (still traced densely) while letting clean stairs/slopes straighten.
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
  private static int wallClearance(ClientLevel level, int x, int y, int z, int dx, int dz) {
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

  /**
   * Like {@link RyznChat#send} but only when the debug overlay (/dev debug) is on - diagnostics,
   * not noise.
   */
  private static void dbg(Minecraft mc, String msg) {
    if (PathRenderer.isEnabled()) {
      RyznChat.send(msg);
    }
  }
}
