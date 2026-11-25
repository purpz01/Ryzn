package ryzn.macro.client.feature.macros.impl.tree;

import static ryzn.macro.client.MacroInput.releaseMovementKeys;
import static ryzn.macro.client.feature.macros.impl.tree.TreeChopMacro.SCAN_COOLDOWN_TICKS;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.feature.macros.impl.tree.TreeChopMacro.State;

/**
 * @description Selects live route trunks and hands completed trees to the next existing route
 *     candidate.
 */
final class TreeChopSelection {

  private static final int ROUTE_APPROACH_MAX_ATTEMPTS = 8;

  private static final double ROUTE_GONE_RADIUS = 7.0;

  private static final float TRAVEL_PITCH_DEGREES = 12.0f;

  private static final int COVERAGE_SCAN_COOLDOWN_TICKS = 8;

  private static final int POST_CHOP_COOLDOWN = 0;

  private final TreeChopMacro treeChop;

  TreeChopSelection(TreeChopMacro treeChop) {
    this.treeChop = treeChop;
  }

  void tickFindCluster(Minecraft mc, LocalPlayer player, Level level) {
    if (treeChop.scanCooldown > 0) {
      treeChop.scanCooldown--;
      return;
    }
    tickFindRouteTrunk(mc, player, level);
  }

  /**
   * Live-route driver: pick the nearest unhandled stacked trunk and hand off to the normal chop
   * pipeline.
   */
  void tickFindRouteTrunk(Minecraft mc, LocalPlayer player, Level level) {
    BlockPos routeTrunk = treeChop.route.current();
    if (routeTrunk == null) {
      routeTrunk = nearestUnhandledRouteTrunk(player, level);
      treeChop.route.select(routeTrunk);
      if (routeTrunk == null) {
        // All currently detected trunks were handled this pass; loop so regrown trees are seen.
        treeChop.route.clearHandled();
        routeTrunk = nearestUnhandledRouteTrunk(player, level);
        treeChop.route.select(routeTrunk);
        if (routeTrunk == null) {
          treeChop.scanCooldown = SCAN_COOLDOWN_TICKS;
          return;
        }
      }
    }

    treeChop.activity =
        "Routing to tree at "
            + routeTrunk.getX()
            + " "
            + routeTrunk.getY()
            + " "
            + routeTrunk.getZ();

    final LogCluster cluster = treeChop.clusterScanner.buildRouteCluster(level, routeTrunk);
    if (cluster == null && level.hasChunkAt(routeTrunk)) {
      treeChop.activity = "No tall connected trunk, picking another";
      skipRouteTrunk();
      treeChop.scanCooldown = 0;
      return;
    }
    if (cluster != null) {
      final BlockPos trunkRoot = treeChop.clusterScanner.routeTrunkRoot(cluster, routeTrunk);
      if (trunkRoot == null) {
        skipRouteTrunk();
        treeChop.scanCooldown = 0;
        return;
      }
      final BlockPos stand = treeChop.standFinder.findForCluster(level, player, cluster, trunkRoot);
      if (stand != null) {
        treeChop.clusterTarget.select(trunkRoot, stand, cluster.blocks());
        treeChop.targeting.setCluster(
            treeChop.clusterTarget.base(), treeChop.clusterTarget.blocks());
        treeChop.exclusions.clearTargetBlocks();
        treeChop.targetState.clearAimCandidate();
        treeChop.contactProgress.reset();
        treeChop.chopPhase.reset();
        treeChop.route.resetApproachAttempts();
        treeChop.activity =
            "Pathing to tree at "
                + routeTrunk.getX()
                + " "
                + routeTrunk.getY()
                + " "
                + routeTrunk.getZ();
        TreeChopMacro.say(
            mc,
            "§bTree Chop§7: §fwalking to the next tree at "
                + routeTrunk.getX()
                + " "
                + routeTrunk.getY()
                + " "
                + routeTrunk.getZ()
                + ".");
        TreeChopRenderer.publish(List.of(cluster), trunkRoot);
        treeChop.state = State.PATH_TO_CLUSTER;
        if (!treeChop.approachActions.stopEarlyAtTree(player, level, mc.options)) {
          treeChop.issueNav(stand.getX(), stand.getY(), stand.getZ());
        }
        return;
      }
    }

    final double horiz = horizontalDistanceToColumn(player, routeTrunk);
    if (cluster == null && horiz <= ROUTE_GONE_RADIUS) {
      treeChop.activity =
          "Tree gone at "
              + routeTrunk.getX()
              + " "
              + routeTrunk.getY()
              + " "
              + routeTrunk.getZ()
              + ", next";
      skipRouteTrunk();
      treeChop.scanCooldown = 0;
      return;
    }
    if (treeChop.route.approachLimitExceeded(horiz, 0.5, ROUTE_APPROACH_MAX_ATTEMPTS)) {
      treeChop.activity =
          "Cannot reach tree at "
              + routeTrunk.getX()
              + " "
              + routeTrunk.getY()
              + " "
              + routeTrunk.getZ()
              + ", next";
      skipRouteTrunk();
      treeChop.scanCooldown = SCAN_COOLDOWN_TICKS;
      return;
    }

    final BlockPos approach = treeChop.standFinder.findAround(level, player, routeTrunk, 4);
    final BlockPos navTo = approach != null ? approach : routeTrunk;
    treeChop.issueNav(navTo.getX(), navTo.getY(), navTo.getZ());
    if (treeChop.navigation.isIssued()) {
      treeChop.state = State.SCAN_COVERAGE_AREA;
    } else {
      treeChop.scanCooldown = COVERAGE_SCAN_COOLDOWN_TICKS;
    }
  }

  /**
   * Nearest live trunk from the index. The published list can be up to one sweep old, so each
   * candidate is re-checked against the world before it is offered - one block read, versus the
   * full-box rescan this replaces.
   *
   * <p>A trunk foot with AIR under it is what somebody else's half-felled tree leaves behind: a run
   * of logs hanging in the canopy with no ground beneath to stand on. The index only asks for four
   * logs in a column, so those float straight back into the candidate list, and the bot burned its
   * approach attempts trying to path up at one. A tree that grew there is standing on something.
   */
  BlockPos nearestUnhandledRouteTrunk(LocalPlayer player, Level level) {
    return nearestUnhandledRouteTrunk(player, level, null);
  }

  BlockPos nearestUnhandledRouteTrunk(LocalPlayer player, Level level, BlockPos excludedTrunk) {
    final TreeLogType type = treeChop.chopLogType();
    final long now = System.currentTimeMillis();
    BlockPos best = null;
    double bestCost = Double.MAX_VALUE;
    for (BlockPos pos : treeChop.index.roots()) {
      if (treeChop.route.isHandled(pos) || pos.equals(excludedTrunk)) {
        continue;
      }
      if (level.getBlockState(pos.below()).isAir()) {
        continue;
      }
      if (treeChop.exclusions.isRegenerating(pos.asLong(), now)) {
        continue;
      }
      if (!LogScanner.isLog(level.getBlockState(pos), type)) {
        continue;
      }
      final double cost = LogScanner.cost(pos, player.getX(), player.getY(), player.getZ());
      if (cost < bestCost) {
        bestCost = cost;
        best = pos;
      }
    }
    return best;
  }

  static double horizontalDistanceToColumn(LocalPlayer player, BlockPos trunk) {
    final double dx = (trunk.getX() + 0.5) - player.getX();
    final double dz = (trunk.getZ() + 0.5) - player.getZ();
    return Math.sqrt(dx * dx + dz * dz);
  }

  /**
   * Mark the current route trunk done (gone/unreachable) and clear the cursor so we pick the next
   * nearest.
   */
  void skipRouteTrunk() {
    treeChop.index.forget(treeChop.route.current());
    treeChop.route.completeCurrent();
  }

  /**
   * Advance the route after a tree is chopped/concluded so the next FIND_CLUSTER picks the next
   * nearest.
   */
  void advanceRoute() {
    treeChop.index.forget(treeChop.route.current());
    treeChop.route.completeCurrent();
  }

  void tickScanCoverageArea(Minecraft mc, LocalPlayer player, Options opts) {
    treeChop.releaseChop(opts);
    // This walk has no cluster behind it yet, so the PATH_TO_CLUSTER staleness checks never see it.
    // Watch
    // the one block we do know about: the trunk root. Gone means somebody else got there first.
    final BlockPos trunk = treeChop.route.current();
    final Level level = mc.level;
    if (trunk != null
        && level != null
        && !LogScanner.isLog(level.getBlockState(trunk), treeChop.chopLogType())) {
      treeChop.activity = "Tree gone, picking another";
      treeChop.abortNav();
      skipRouteTrunk();
      treeChop.scanCooldown = 0;
      treeChop.state = State.FIND_CLUSTER;
      return;
    }
    if (!treeChop.navFinished()) {
      return;
    }
    treeChop.scanCooldown = 0;
    treeChop.state = State.FIND_CLUSTER;
  }

  void tickMarkClusterDone(Minecraft mc, LocalPlayer player, Level level, Options opts) {
    releaseMovementKeys(opts);
    treeChop.releaseChop(opts);
    treeChop.targetState.clearCommit();
    finishTreeWithoutPickup(mc, player, level, opts, "Tree done, moving on");
  }

  /**
   * Handoff between trees: the feet stay planted for a jittered beat while the camera comes down
   * off the canopy and swings onto the next trunk, then normal target selection resumes. The index
   * is already warm by now, so FIND_CLUSTER no longer sweeps the box - this pause is the whole gap,
   * and it is spent turning.
   */
  void tickMoveToNext(LocalPlayer player, Level level, Options opts) {
    if (treeChop.handoffTicks > 0) {
      treeChop.handoffTicks--;
      releaseMovementKeys(opts);
      treeChop.releaseChopOnly(opts);
      if (treeChop.route.current() == null) {
        final BlockPos next = nearestUnhandledRouteTrunk(player, level);
        treeChop.route.select(next);
      }
      final BlockPos next = treeChop.route.current();
      if (next != null) {
        treeChop.motion.setTravelLook(player, next, TRAVEL_PITCH_DEGREES);
      }
      return;
    }
    treeChop.toScan(POST_CHOP_COOLDOWN);
  }

  void finishTreeWithoutPickup(
      Minecraft mc, LocalPlayer player, Level level, Options opts, String nextActivity) {
    PathfindCommand.stopNavQuiet();
    treeChop.navigation.reset();
    releaseMovementKeys(opts);
    treeChop.releaseChop(opts);
    markTargetClusterHandled();
    treeChop.targetState.clearCommit();
    treeChop.chopPhase.clearBreakMaxY();
    treeChop.chopPhase.resetHighPhase();
    treeChop.activity = nextActivity;
    final BlockPos nextTree = treeChop.preparedNextTree;
    treeChop.toScan(0);
    if (nextTree != null) {
      treeChop.route.select(nextTree);
    }
    tickFindCluster(mc, player, level);
  }

  void markTargetClusterHandled() {
    advanceRoute();
  }
}
