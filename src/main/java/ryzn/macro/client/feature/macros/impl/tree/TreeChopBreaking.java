package ryzn.macro.client.feature.macros.impl.tree;

import static ryzn.macro.client.MacroInput.releaseMovementKeys;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.macros.impl.tree.TreeChopMacro.State;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.mixin.accessor.MultiPlayerGameModeAccessor;

/**
 * @description Advances log commits and the planted low/high cutting phases through the existing
 *     targeting and motion collaborators.
 */
final class TreeChopBreaking {

  private static final int HIGH_PHASE_CONFIRM_TICKS = 4;

  private static final double TARGET_SWITCH_COST_RATIO = 0.80;

  private static final int CROSSHAIR_BLOCKED_TIMEOUT_TICKS = 30;

  private final TreeChopMacro treeChop;

  TreeChopBreaking(TreeChopMacro treeChop) {
    this.treeChop = treeChop;
  }

  void tickBreakOneLog(Minecraft mc, LocalPlayer player, Level level, Options opts) {
    final BlockPos targetLog = treeChop.clusterTarget.base();
    final BlockPos committedLog = treeChop.targetState.committedLog();
    if (committedLog == null) {
      treeChop.state = State.ARRIVE_AT_CLUSTER;
      return;
    }
    // A body under the bot lifts it onto their hitbox and the committed log goes out of reach, so
    // the
    // swing lands on nothing until the commit times out. Step off first; the commit survives the
    // step.
    if (treeChop.approachActions.nudgeOffStackedBody(player, level, opts, committedLog)) {
      return;
    }
    updateChopPhase(player, level, opts);
    if (!treeChop.targeting.isSelectedLog(level, committedLog)) {
      final BlockPos broken = committedLog.immutable();
      treeChop.chopped++;
      treeChop.consecutiveLostTrees = 0;
      treeChop.clusterTarget.recordBrokenLog(broken);
      treeChop.contactProgress.resetRecontacts();
      if (treeChop.isTreecapMode()) {
        treeChop.treeSelection.finishTreeWithoutPickup(
            mc, player, level, opts, "Treecap cut, moving on");
        return;
      }
      if (treeChop.chopPhase.isClearingTrunkEntry()) {
        final BlockPos nextEntry = selectTrunkEntryLog(player, level);
        if (nextEntry != null) {
          if (!treeChop.equipTool(mc, player)) {
            treeChop.releaseChop(opts);
            return;
          }
          commitToLog(player, nextEntry);
          if (plantedAfterCut()) {
            releaseMovementKeys(opts);
          } else {
            treeChop.approachActions.driveTowardLogOrStop(opts, player, nextEntry);
          }
          treeChop.state = State.BREAK_ONE_LOG;
          treeChop.activity = "Clearing trunk entry";
          return;
        }
        treeChop.chopPhase.completeTrunkEntry();
        treeChop.chopPhase.clearCenteredAtTrunk();
        treeChop.targetState.clearCommit();
        treeChop.contactProgress.resetContact();
        treeChop.activity = "Entering trunk center";
        treeChop.state = plantedAfterCut() ? State.ARRIVE_AT_CLUSTER : State.CONTACT_TREE;
        return;
      }
      if (commitNextReachableLog(mc, player, level, opts, broken)) {
        return;
      }
      treeChop.releaseChop(opts);
      treeChop.targetState.clearCommit();
      treeChop.contactProgress.resetArrival();
      treeChop.state = State.ARRIVE_AT_CLUSTER;
      return;
    }
    if (treeChop.targetState.commitTimedOut(treeChop.chopBreakTimeoutTicks())) {
      treeChop.exclusions.blockLog(committedLog.asLong());
      treeChop.releaseChop(opts);
      treeChop.targetState.clearCommit();
      treeChop.activity = "Break timed out, trying another log";
      treeChop.state =
          treeChop.chopPhase.isClearingTrunkEntry() && !plantedAfterCut()
              ? State.CONTACT_TREE
              : State.ARRIVE_AT_CLUSTER;
      return;
    }
    if (!treeChop.targeting.isChoppable(player, level, committedLog)) {
      treeChop.exclusions.blockLog(committedLog.asLong());
      treeChop.targetState.clearCommit();
      treeChop.state =
          treeChop.chopPhase.isClearingTrunkEntry() && !plantedAfterCut()
              ? State.CONTACT_TREE
              : State.ARRIVE_AT_CLUSTER;
      return;
    }

    if (plantedAfterCut()) {
      releaseMovementKeys(opts);
    } else if (treeChop.chopPhase.isClearingTrunkEntry()
        && !treeChop.chopPhase.isCenteredAtTrunk()) {
      treeChop.approachActions.driveTowardLogOrStop(opts, player, committedLog);
    } else {
      // Low phase keeps pressing into the trunk centre (crouched while close); the high phase
      // stands
      // still and uncrouched. driveTowardLogOrStop resolves both from the phase latch.
      treeChop.approachActions.driveTowardLogOrStop(
          opts, player, targetLog != null ? targetLog : committedLog);
    }
    if (!treeChop.chopPhase.isClearingTrunkEntry()) {
      refreshGlideTarget(player, level, committedLog);
    }
    final BlockPos immediateCrosshairLog =
        treeChop.targeting.crosshairSelectedClusterLog(mc, level);
    if (immediateCrosshairLog != null
        && !immediateCrosshairLog.equals(committedLog)
        && treeChop.targeting.isCrosshairCommitAllowed(
            player, level, immediateCrosshairLog, committedLog)
        && tryCommitCrosshairLog(mc, player, level, opts, immediateCrosshairLog)) {
      return;
    }
    // Tick glide: aim at the log being broken, already drifting toward the one queued after it.
    treeChop.motion.setLookTarget(
        player,
        committedLog,
        treeChop.motion.leadAimPoint(
            player,
            committedLog,
            treeChop.targetState.glideLog(),
            treeChop.targetState.commitAge()));
    final BlockPos crosshairLog = treeChop.targeting.crosshairSelectedClusterLog(mc, level);
    if (crosshairLog != null
        && crosshairLog.equals(committedLog)
        && treeChop.targeting.canStartBreakNow(mc, player, level, committedLog)
        && mc.gameMode instanceof MultiPlayerGameModeAccessor breaking
        && treeChop.targetState.recordMiningProgress(
            breaking.ryzn$destroyBlockPos(), breaking.ryzn$destroyProgress())) {
      treeChop.targetState.recordCrosshairHit();
      treeChop.activity = "Breaking crosshair log";
    } else if (treeChop.targetState.crosshairBlockedTooLong(CROSSHAIR_BLOCKED_TIMEOUT_TICKS)) {
      treeChop.exclusions.blockLog(committedLog.asLong());
      treeChop.targetState.clearCommit();
      treeChop.activity =
          treeChop.targeting.blockedByCrosshair(mc)
              ? "Blocked by non-log"
              : "Crosshair blocked, trying another log";
      treeChop.state = State.ARRIVE_AT_CLUSTER;
    }
  }

  BlockPos selectTrunkEntryLog(LocalPlayer player, Level level) {
    return treeChop.targeting.selectTrunkEntryLog(player, level);
  }

  /** Capture the per-tree break cap once, when the bot first starts breaking the current tree. */
  void ensureBreakMaxY(LocalPlayer player) {
    treeChop.chopPhase.captureBreakMaxY(treeChop.targeting.breakMaxLogY(player));
  }

  boolean beginBreakIfReachable(Minecraft mc, LocalPlayer player, Level level, Options opts) {
    final BlockPos crosshairLog = treeChop.targeting.crosshairSelectedClusterLog(mc, level);
    if (treeChop.targeting.isCrosshairCommitAllowed(
            player, level, crosshairLog, treeChop.clusterTarget.lastBrokenLog())
        && tryCommitCrosshairLog(mc, player, level, opts, crosshairLog)) {
      return true;
    }
    final BlockPos best =
        treeChop.targeting.selectClosestReachableLog(
            player, level, treeChop.clusterTarget.lastBrokenLog());
    if (best == null) {
      return false;
    }
    treeChop.motion.setLookTarget(player, best, TreeLogTargeting.aimPointForLog(player, best));
    if (treeChop.targeting.hasActualCrosshairHit(mc, level, best)
        && treeChop.targeting.canStartBreakNow(mc, player, level, best)) {
      if (!treeChop.equipTool(mc, player)) {
        treeChop.releaseChop(opts);
        return true;
      }
      beginBreakingLog(mc, player, opts, best);
      return true;
    }
    treeChop.activity =
        treeChop.targeting.blockedByCrosshair(mc) ? "Blocked by non-log" : "Aiming for closest log";
    noteCandidateAim(best, mc);
    return true;
  }

  void beginBreakingLog(Minecraft mc, LocalPlayer player, Options opts, BlockPos log) {
    commitToLog(player, log);
    treeChop.abortNav();
    if (treeChop.chopPhase.isHigh()) {
      releaseMovementKeys(opts);
    }
    treeChop.state = State.BREAK_ONE_LOG;
  }

  boolean tryCommitCrosshairLog(
      Minecraft mc, LocalPlayer player, Level level, Options opts, BlockPos log) {
    if (!treeChop.targeting.canStartBreakNow(mc, player, level, log)) {
      return false;
    }
    if (!treeChop.equipTool(mc, player)) {
      treeChop.releaseChop(opts);
      return true;
    }
    beginBreakingLog(mc, player, opts, log);
    treeChop.activity = "Breaking crosshair log";
    return true;
  }

  boolean commitNextReachableLog(
      Minecraft mc, LocalPlayer player, Level level, Options opts, BlockPos anchor) {
    final BlockPos glideLog = treeChop.targetState.glideLog();
    BlockPos next = isReadyGlideLog(player, level, glideLog) ? glideLog : null;
    if (next == null) {
      next = treeChop.targeting.selectNextReachableLog(player, level, anchor);
    }
    if (next == null) {
      return false;
    }
    if (!treeChop.equipTool(mc, player)) {
      treeChop.releaseChop(opts);
      return true;
    }
    commitToLog(player, next);
    if (treeChop.chopPhase.isHigh()) {
      releaseMovementKeys(opts);
    }
    treeChop.state = State.BREAK_ONE_LOG;
    treeChop.activity =
        TreeLogTargeting.isAdjacentLog(anchor, next)
            ? "Sweeping to adjacent log"
            : "Sweeping to next log";
    return true;
  }

  void refreshGlideTarget(LocalPlayer player, Level level, BlockPos anchor) {
    final BlockPos candidate = treeChop.targeting.selectNextReachableLog(player, level, anchor);
    final BlockPos glideLog = treeChop.targetState.glideLog();
    if (!isReadyGlideLog(player, level, glideLog)) {
      treeChop.targetState.setGlideLog(candidate);
      return;
    }
    if (candidate == null || candidate.equals(glideLog)) {
      return;
    }
    // Keep the current glide target unless the candidate is a strict upgrade: adjacency gained, or
    // the
    // cost improves by a meaningful margin. This prevents per-tick target thrash and camera
    // twitching.
    final boolean candidateAdjacent =
        anchor != null && TreeLogTargeting.isAdjacentLog(anchor, candidate);
    final boolean currentAdjacent =
        anchor != null && TreeLogTargeting.isAdjacentLog(anchor, glideLog);
    if ((candidateAdjacent && !currentAdjacent)
        || treeChop.targeting.logTargetCost(player, anchor, candidate)
            < treeChop.targeting.logTargetCost(player, anchor, glideLog)
                * TARGET_SWITCH_COST_RATIO) {
      treeChop.targetState.setGlideLog(candidate);
    }
  }

  boolean isReadyGlideLog(LocalPlayer player, Level level, BlockPos log) {
    final BlockPos committedLog = treeChop.targetState.committedLog();
    return log != null
        && !treeChop.exclusions.isLogBlocked(log.asLong())
        && !log.equals(committedLog)
        && treeChop.targeting.isCurrentPhaseLog(player, level, log)
        && treeChop.targeting.isReachableLog(player, level, log);
  }

  void commitToLog(LocalPlayer player, BlockPos log) {
    treeChop.targetState.commit(log);
    treeChop.activity = "Breaking crosshair log";
    treeChop.motion.setLookTarget(player, log, TreeLogTargeting.aimPointForLog(player, log));
  }

  void noteCandidateAim(BlockPos candidate, Minecraft mc) {
    if (candidate == null) {
      return;
    }
    if (treeChop.state == State.PATH_TO_CLUSTER && WalkingNavigation.isNavigationActive()) {
      return;
    }
    if (treeChop.targetState.noteAimCandidate(candidate, CROSSHAIR_BLOCKED_TIMEOUT_TICKS)) {
      treeChop.exclusions.blockLog(candidate.asLong());
      treeChop.activity =
          treeChop.targeting.blockedByCrosshair(mc)
              ? "Blocked by non-log"
              : "Crosshair blocked, trying another log";
    }
  }

  boolean plantedAfterCut() {
    return treeChop.clusterTarget.lastBrokenLog() != null;
  }

  /** High log: above eye level. Only selectable once the high phase has latched. */
  boolean isHighLog(LocalPlayer player, BlockPos log) {
    return log != null && log.getY() > treeChop.targeting.movingBreakMaxY(player);
  }

  boolean hasReachableLowLog(LocalPlayer player, Level level, BlockPos ignored) {
    return treeChop.targeting.hasReachableLogAtOrBelow(
        player, level, treeChop.targeting.movingBreakMaxY(player), ignored);
  }

  boolean hasReachableHighLog(LocalPlayer player, Level level, BlockPos ignored) {
    final int capY = treeChop.targeting.effectiveBreakMaxY(player);
    for (BlockPos log : treeChop.clusterTarget.blocks()) {
      if (ignored != null && log.equals(ignored)) {
        continue;
      }
      if (treeChop.exclusions.isLogBlocked(log.asLong())) {
        continue;
      }
      if (!isHighLog(player, log)) {
        continue;
      }
      if (!treeChop.targeting.isEligibleLog(capY, log)) {
        continue;
      }
      if (treeChop.targeting.isReachableLog(player, level, log)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Advance the per-tree phase latch. The high phase begins only once no reachable low log has
   * existed for HIGH_PHASE_CONFIRM_TICKS in a row (so a transient raycast miss never skips the low
   * phase), the player is centered on the trunk column, and a high log is actually reachable from
   * there.
   */
  void updateChopPhase(LocalPlayer player, Level level, Options opts) {
    if (treeChop.chopPhase.isHigh()) {
      return;
    }
    final boolean hasLowLog =
        treeChop.chopPhase.isTrunkEntryCleared() && hasReachableLowLog(player, level, null);
    if (!treeChop.chopPhase.highPhaseConfirmed(hasLowLog, HIGH_PHASE_CONFIRM_TICKS)) {
      return;
    }
    if (!treeChop.approachActions.atTrunkColumn(player)) {
      return;
    } // center-entry: keep moving onto the coord first
    if (!hasReachableHighLog(player, level, null)) {
      return;
    }
    treeChop.chopPhase.beginHighPhase();
    releaseMovementKeys(opts);
    treeChop.activity = "Breaking high logs";
    DebugLog.trace(
        DebugLog.Category.MACRO,
        "Tree chop: ground logs are done - starting on the ones higher up");
  }
}
