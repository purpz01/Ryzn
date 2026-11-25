package ryzn.macro.client.feature.macros.impl.tree;

import static ryzn.macro.client.MacroInput.releaseMovementKeys;
import static ryzn.macro.client.feature.macros.impl.tree.TreeChopMacro.REGEN_SKIP_MS;
import static ryzn.macro.client.feature.macros.impl.tree.TreeChopMacro.SCAN_COOLDOWN_TICKS;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.feature.macros.impl.tree.TreeChopMacro.State;
import ryzn.macro.client.util.world.MacroObstruction;

/**
 * @description Advances the existing walk, trunk contact, arrival, and limited repositioning
 *     actions.
 */
final class TreeChopApproach {

  /**
   * Steps per tree. Two, not one: stopping early trades a shorter walk for one more short step in.
   */
  private static final int MAX_NUDGES_PER_TREE = 2;

  private static final double RETARGET_COST_RATIO = 0.60;

  /**
   * A mid-route swap has to be a real saving; below this we are already there and switching just
   * dithers.
   */
  private static final double RETARGET_MIN_COST = 6.0;

  /**
   * How long to tolerate standing on somebody before stepping off them (~0.5 s). Shorter than the
   * interaction norm's relocate: there is nothing to wait out here, the block underfoot is simply
   * unreachable from up there and no amount of patience changes that.
   */
  private static final int STACKED_NUDGE_TICKS = 10;

  /** Stop this far from the trunk column centre when stepping the last bit into break reach. */
  private static final double TRUNK_ENTRY_ARRIVE_RADIUS = 1.1;

  private static final int CONTACT_TIMEOUT_TICKS = 20 * 3;

  private static final int MAX_RECONTACT_ATTEMPTS = 40;

  private static final int ARRIVE_NO_LOG_TICKS = 20;

  private static final int NUDGE_TIMEOUT_TICKS = 30;

  private static final int NUDGE_MAX_BLOCKS = 2;

  private final TreeChopMacro treeChop;

  TreeChopApproach(TreeChopMacro treeChop) {
    this.treeChop = treeChop;
  }

  void tickPathToCluster(Minecraft mc, LocalPlayer player, Level level, Options opts) {
    BlockPos targetLog = treeChop.clusterTarget.base();
    BlockPos standPos = treeChop.clusterTarget.stand();
    List<BlockPos> targetClusterBlocks = treeChop.clusterTarget.blocks();
    if (targetLog == null || standPos == null) {
      treeChop.toScan(0);
      return;
    }
    final TreeLogType type = treeChop.chopLogType();
    if (treeChop.clusterTarget.lostApproachLog(
        log -> level.hasChunkAt(log) && !treeChop.targeting.isSelectedLog(level, log))) {
      long until = System.currentTimeMillis() + REGEN_SKIP_MS;
      for (BlockPos log : targetClusterBlocks) {
        treeChop.exclusions.skipRegeneratingTree(log.asLong(), until);
      }
      treeChop.exclusions.skipRegeneratingTree(targetLog.asLong(), until);
      treeChop.treeSelection.markTargetClusterHandled();
      treeChop.abortNav();
      treeChop.toScan(0);
      treeChop.activity = "Tree being cut, picking another";
      treeChop.consecutiveLostTrees++;
      treeChop.treeSelection.tickFindCluster(mc, player, level);
      return;
    }
    if (treeChop.isAvoidRoute(type, player, standPos)) {
      treeChop.activity =
          "Route crosses the " + TreeChopMacro.avoidLabel(type) + " box, picking another tree";
      treeChop.debugAvoidSkip(mc, type, "route crosses avoid box", standPos);
      treeChop.exclusions.markUnreachable(targetLog.asLong());
      treeChop.abortNav();
      treeChop.toScan(0);
      return;
    }
    // Two different questions. "Is anything left?" is the right one at the trunk mid-chop; on the
    // way there
    // it let the bot finish a walk to a tree another player had already felled down to a stump.
    boolean ownCutWithLogsLeft =
        treeChop.clusterTarget.lastBrokenLog() != null
            && targetClusterBlocks.stream()
                .anyMatch(log -> treeChop.targeting.isSelectedLog(level, log));
    if (!ownCutWithLogsLeft
        && (!treeChop.clusterScanner.targetStillDense(player, level, targetLog, targetClusterBlocks)
            || treeChop.clusterScanner.routeTargetMostlyGone(level, targetClusterBlocks))) {
      treeChop.activity = "Tree gone, picking another";
      treeChop.consecutiveLostTrees++;
      treeChop.treeSelection.markTargetClusterHandled();
      treeChop.abortNav();
      treeChop.toScan(0);
      return;
    }
    if (retargetCloserTrunk(player, level)) {
      return;
    }

    if (stopEarlyAtTree(player, level, opts)) {
      return;
    }

    if (!treeChop.navFinished()) {
      if (treeChop.gaze.update(player, level, targetLog)) {
        treeChop.motion.setRouteGaze(true);
        treeChop.motion.setWatchLook(player, targetLog);
        treeChop.releaseChopOnly(opts);
      } else {
        treeChop.releaseChop(opts);
      }
      return;
    }
    treeChop.motion.setRouteGaze(false);
    treeChop.releaseChopOnly(opts);
    // The search came back with nothing. Burn this stand NOW rather than letting the arrival checks
    // below
    // decide we are "close" and spend a second trying to walk somewhere unreachable, because the
    // retry
    // lands on the SAME stand and the whole cycle repeats: log 2026-08-27 asked for
    // goal(-484,121,-48)
    // from start(-484,119,-47) - two blocks straight up, which is not a jump - roughly twenty times
    // over
    // 51 s. repathToAlternateStand blocks the stand, tries another, and gives the trunk up when
    // they run
    // out, which is exactly the give-up that was missing.
    if (treeChop.navigation.answeredNoPath() && !treeChop.clusterTarget.playerIsAtStand(player)) {
      repathToAlternateStand(player, level, opts, "No path to that stand, trying another");
      return;
    }
    if (!treeChop.clusterTarget.playerIsAtStand(player)) {
      if (!treeChop.collectionMode && treeChop.clusterTarget.playerIsCloseToStand(player)) {
        finishApproach(opts);
        return;
      }
      if (treeChop.clusterTarget.playerIsCloseToStand(player)
          && treeChop.contactProgress.keepClosingOnStand(ARRIVE_NO_LOG_TICKS)) {
        // No step-jump: stands sit at the foot of the trunk now, so a jump here only ever means
        // hopping onto the tree itself.
        treeChop.motion.driveTowardStand(opts, player, standPos, 0.35, false);
        treeChop.activity = "Closing on tree stand";
        return;
      }
      repathToAlternateStand(player, level, opts, "Missed tree stand, repathing");
      return;
    }
    finishApproach(opts);
  }

  void finishApproach(Options opts) {
    treeChop.contactProgress.resetArrival();
    releaseMovementKeys(opts);
    treeChop.contactProgress.resetContact();
    treeChop.gaze.release();
    treeChop.motion.setRouteGaze(false);
    treeChop.state = State.CONTACT_TREE;
    treeChop.activity = "Contacting trunk";
  }

  /**
   * End the walk within one block of a live trunk face, with a log reachable from there.
   *
   * <p>Chopping starts here and the stand becomes wherever we stopped, so the bot works the face it
   * walked up to instead of continuing round the trunk to the tile the stand finder liked. Anything
   * the shorter stand cannot reach is what {@link #tryNudge} is for.
   */
  boolean stopEarlyAtTree(LocalPlayer player, Level level, Options opts) {
    // Once per tree. Contact can decide the spot we stopped at is no good and repath to a fresh
    // stand; with
    // no latch the next tick would stop early at the same place again and the two would trade
    // forever.
    if (treeChop.stoppedEarlyThisTree || !player.onGround()) {
      return false;
    }
    final BlockPos targetLog = treeChop.clusterTarget.base();
    if (targetLog == null) {
      return false;
    }
    if (!closeEnoughToTrunk(player, level)) {
      return false;
    }
    if (treeChop.targeting.selectClosestReachableLog(player, level, null) == null) {
      return false;
    }
    treeChop.abortNav();
    treeChop.stoppedEarlyThisTree = true;
    treeChop.gaze.release();
    treeChop.motion.setRouteGaze(false);
    releaseMovementKeys(opts);
    treeChop.clusterTarget.moveStand(player.blockPosition().immutable());
    treeChop.contactProgress.resetArrival();
    treeChop.contactProgress.resetContact();
    treeChop.state = State.CONTACT_TREE;
    treeChop.activity = "In reach, chopping from here";
    return true;
  }

  boolean closeEnoughToTrunk(LocalPlayer player, Level level) {
    return treeChop.clusterTarget.withinOneBlockOfTrunk(
        player.getX(), player.getZ(), log -> treeChop.targeting.isSelectedLog(level, log));
  }

  /**
   * Swap targets mid-walk when a much nearer trunk turns up.
   *
   * <p>The route cursor is sticky by design - it is what stops the bot dithering between two trees
   * - but the index keeps finding trees while we walk (they regrow, and the sweep only publishes on
   * each wrap), so a tree right beside us could sit unhandled while the bot marched past it. The
   * ratio and the minimum cost together mean only a clearly cheaper tree, seen while there is still
   * real walking left, wins the swap.
   */
  boolean retargetCloserTrunk(LocalPlayer player, Level level) {
    final BlockPos current = treeChop.route.current();
    if (current == null) {
      return false;
    }
    final double currentCost =
        LogScanner.cost(current, player.getX(), player.getY(), player.getZ());
    if (currentCost <= RETARGET_MIN_COST) {
      return false;
    }
    final BlockPos closer = treeChop.treeSelection.nearestUnhandledRouteTrunk(player, level);
    if (closer == null || closer.equals(current)) {
      return false;
    }
    if (LogScanner.cost(closer, player.getX(), player.getY(), player.getZ())
        > currentCost * RETARGET_COST_RATIO) {
      return false;
    }
    treeChop.abortNav();
    treeChop.toScan(0);
    treeChop.route.select(closer);
    treeChop.activity =
        "Closer tree at " + closer.getX() + " " + closer.getY() + " " + closer.getZ();
    return true;
  }

  void tickContactTree(LocalPlayer player, Level level, Options opts) {
    treeChop.abortNav();
    BlockPos targetLog = treeChop.clusterTarget.base();
    final List<BlockPos> targetClusterBlocks = treeChop.clusterTarget.blocks();
    if (targetLog == null) {
      treeChop.toScan(0);
      return;
    }
    if (treeChop.clusterTarget.lastBrokenLog() == null && !closeEnoughToTrunk(player, level)) {
      treeChop.releaseChopOnly(opts);
      treeChop.motion.driveTowardStand(opts, player, treeChop.clusterTarget.stand(), 0.1, false);
      treeChop.contactProgress.advanceContact();
      if (treeChop.contactProgress.contactTimedOut(CONTACT_TIMEOUT_TICKS)) {
        repathToAlternateStand(player, level, opts, "Cannot get close to trunk, repositioning");
        return;
      }
      treeChop.activity = "Moving within one block of trunk";
      return;
    }
    treeChop.chopActions.updateChopPhase(player, level, opts);
    if (treeChop.chopPhase.isHigh()) {
      // High-log phase never re-contacts: stand still and break upward from ARRIVE.
      releaseMovementKeys(opts);
      treeChop.state = State.ARRIVE_AT_CLUSTER;
      tickArriveAtCluster(Minecraft.getInstance(), player, level, opts);
      return;
    }

    final BlockPos contactLog = treeChop.targeting.contactTargetLog(player, level);
    if (contactLog == null) {
      if (treeChop.clusterScanner.targetStillDense(player, level, targetLog, targetClusterBlocks)) {
        repathToAlternateStand(player, level, opts, "No reachable tree face, repathing");
        return;
      }
      treeChop.treeSelection.finishTreeWithoutPickup(
          Minecraft.getInstance(), player, level, opts, "Tree done, moving on");
      return;
    }

    final BlockPos entryLog = treeChop.chopActions.selectTrunkEntryLog(player, level);
    driveTowardLogOrStop(opts, player, contactLog);
    treeChop.contactProgress.advanceContact();
    if (entryLog != null) {
      treeChop.motion.setLookTarget(
          player, entryLog, treeChop.motion.contactAimPoint(player, entryLog));
      treeChop.chopPhase.beginTrunkEntryClearing();
      if (treeChop.chopActions.tryCommitCrosshairLog(
          Minecraft.getInstance(),
          player,
          level,
          opts,
          treeChop.targeting.crosshairSelectedClusterLog(Minecraft.getInstance(), level))) {
        return;
      }
      // The only branch in this state with no way out. A body between the eye and the trunk owns
      // mc.hitResult, so the crosshair never reports a log and the commit above can never fire -
      // reach
      // and block line of sight both still say the entry log is fine, because neither looks at
      // entities.
      // That is the quest-NPC freeze: Kelly stands in front of the spruce, the bot presses into her
      // and
      // stares forever, chopped 0. Time out onto another stand like every other stuck path here
      // does.
      if (treeChop.contactProgress.contactTimedOut(CONTACT_TIMEOUT_TICKS)) {
        repathToAlternateStand(player, level, opts, "Trunk entry blocked, repositioning");
        return;
      }
      treeChop.chopActions.noteCandidateAim(entryLog, Minecraft.getInstance());
      treeChop.activity = "Clearing trunk entry";
      return;
    }
    if (!treeChop.chopPhase.isTrunkEntryCleared()) {
      if (treeChop.targeting.hasBlockingTrunkEntryLog(player, level)) {
        // If the entry log is visible but just beyond break reach, walk the last bit in
        // (driveToward
        // above is already steering onto the column) instead of abandoning the stand and repathing
        // to
        // another equally-far one - that far-stand repath was the acacia "Repositioning for trunk
        // entry" loop. contactTimedOut backstops a stand we genuinely can't close on.
        if (!treeChop.contactProgress.contactTimedOut(CONTACT_TIMEOUT_TICKS)
            && treeChop.targeting.hasApproachableTrunkEntryLog(player, level)) {
          // Actually take the step. driveTowardLogOrStop above only plants the feet - the "stand
          // still and fell it with the camera" refactor emptied it - so this branch believed it was
          // being walked in when nothing was moving, and the bot stared at an out-of-reach trunk
          // for
          // the whole timeout before repathing to another stand just as far away.
          treeChop.motion.driveTowardHorizontal(
              opts,
              player,
              targetLog.getX() + 0.5,
              targetLog.getZ() + 0.5,
              TRUNK_ENTRY_ARRIVE_RADIUS);
          treeChop.activity = "Approaching trunk entry";
          return;
        }
        treeChop.traceTrunkEntryBlocked(player, level);
        repathToAlternateStand(player, level, opts, "Repositioning for trunk entry");
        return;
      }
      treeChop.chopPhase.completeTrunkEntry();
    } else {
      final BlockPos crosshairLog =
          treeChop.targeting.crosshairSelectedClusterLog(Minecraft.getInstance(), level);
      if (treeChop.targeting.isCrosshairCommitAllowed(
              player, level, crosshairLog, treeChop.clusterTarget.lastBrokenLog())
          && treeChop.chopActions.tryCommitCrosshairLog(
              Minecraft.getInstance(), player, level, opts, crosshairLog)) {
        return;
      }
    }

    treeChop.motion.setLookTarget(
        player, contactLog, treeChop.motion.contactAimPoint(player, contactLog));

    // driveTowardLogOrStop above marks the phase centered once we are on the column centre; only a
    // hard timeout
    // makes us settle for wherever we ended up so the bot reaches the middle of the coord, not just
    // its edge.
    final boolean contact = treeChop.chopPhase.isCenteredAtTrunk();
    if (contact || treeChop.contactProgress.contactTimedOut(CONTACT_TIMEOUT_TICKS)) {
      releaseMovementKeys(opts);
      treeChop.clusterTarget.moveStand(player.blockPosition().immutable());
      treeChop.exclusions.clearTargetBlocks();
      treeChop.targetState.clearCommit();
      treeChop.contactProgress.resetArrival();
      treeChop.activity = contact ? "Centered on trunk" : "Using fixed stand";
      treeChop.state = State.ARRIVE_AT_CLUSTER;
      tickArriveAtCluster(Minecraft.getInstance(), player, level, opts);
      return;
    }

    treeChop.activity = "Walking onto trunk center";
  }

  void tickArriveAtCluster(Minecraft mc, LocalPlayer player, Level level, Options opts) {
    BlockPos targetLog = treeChop.clusterTarget.base();
    final List<BlockPos> targetClusterBlocks = treeChop.clusterTarget.blocks();
    if (targetLog == null) {
      releaseMovementKeys(opts);
      treeChop.toScan(0);
      return;
    }
    if (nudgeOffStackedBody(player, level, opts, targetLog)) {
      return;
    }
    if (treeChop.chopActions.plantedAfterCut()) {
      treeChop.nudgeTarget = null;
    }
    if (treeChop.nudgeTarget != null) {
      final boolean arrived =
          treeChop.motion.driveTowardHorizontal(
              opts,
              player,
              treeChop.nudgeTarget.getX() + 0.5,
              treeChop.nudgeTarget.getZ() + 0.5,
              0.30);
      if (arrived || ++treeChop.nudgeTicks > NUDGE_TIMEOUT_TICKS) {
        treeChop.nudgeTarget = null;
        releaseMovementKeys(opts);
        treeChop.activity = "Resuming chop";
      }
      return;
    }
    treeChop.chopActions.ensureBreakMaxY(player);
    treeChop.chopActions.updateChopPhase(player, level, opts);
    // Low/center phases keep pressing into the trunk centre; the high phase stands still. Movement
    // is
    // owned here so target aiming below never has to touch the keys.
    if (treeChop.chopActions.plantedAfterCut()) {
      releaseMovementKeys(opts);
    } else {
      driveTowardLogOrStop(opts, player, targetLog);
    }
    // Break whatever is in reach for the current phase: low logs first, then up the column once
    // centered.
    if (treeChop.chopActions.beginBreakIfReachable(mc, player, level, opts)) {
      return;
    }
    if (treeChop.chopActions.plantedAfterCut()) {
      if (!treeChop.chopPhase.isHigh()
          && treeChop.chopActions.hasReachableHighLog(player, level, null)) {
        treeChop.chopPhase.beginHighPhase();
        if (treeChop.chopActions.beginBreakIfReachable(mc, player, level, opts)) {
          return;
        }
      }
      treeChop.treeSelection.finishTreeWithoutPickup(
          mc, player, level, opts, "Logs in reach cleared, moving on");
      return;
    }
    // Nothing in reach for the current phase. Before the high phase the tree is NOT done: either
    // keep
    // walking onto the trunk coord, or hold position while the high-phase latch confirms, so every
    // low
    // log is cleared and the upward break still happens. Only the latched high phase may finish the
    // tree.
    if (!treeChop.chopPhase.isHigh()
        && treeChop.clusterScanner.targetStillDense(
            player, level, targetLog, targetClusterBlocks)) {
      if (!atTrunkColumn(player)) {
        if (treeChop.contactProgress.tryRecontact(MAX_RECONTACT_ATTEMPTS)) {
          treeChop.state = State.CONTACT_TREE;
          tickContactTree(player, level, opts);
          return;
        }
        repathToAlternateStand(player, level, opts, "Could not reach trunk, repathing");
        return;
      } else if (treeChop.chopActions.hasReachableHighLog(player, level, null)) {
        treeChop.activity = "Centered, about to look up";
        return;
      }
      // Nothing in reach from here, but the tree still has logs. Try one short step that puts more
      // of
      // them in reach before giving up the stand entirely - a repath costs a full walk to a new
      // stand.
      if (tryNudge(player, level)) {
        return;
      }
      repathToAlternateStand(player, level, opts, "No reachable log from stand, repathing");
      return;
    }
    // High phase runs out of reach too, and that is exactly when a step back buys an angle on the
    // top of
    // the trunk. Gating the nudge to the low phase meant it never fired on the logs that actually
    // needed it.
    if (treeChop.clusterScanner.targetStillDense(player, level, targetLog, targetClusterBlocks)
        && tryNudge(player, level)) {
      return;
    }
    treeChop.treeSelection.finishTreeWithoutPickup(mc, player, level, opts, "Tree done, moving on");
  }

  /**
   * True when the player is standing in the target trunk's own column (i.e. on the route coord).
   */
  boolean atTrunkColumn(LocalPlayer player) {
    if (treeChop.chopPhase.isCenteredAtTrunk()) {
      return true;
    }
    BlockPos targetLog = treeChop.clusterTarget.base();
    if (targetLog == null) {
      return false;
    }
    final double dx = (targetLog.getX() + 0.5) - player.getX();
    final double dz = (targetLog.getZ() + 0.5) - player.getZ();
    return dx * dx + dz * dz <= 0.7 * 0.7;
  }

  /**
   * Step off anybody we are standing on before trying to break anything. A body under the bot lifts
   * it onto their hitbox, and from up there the log it is committed to is simply out of reach - the
   * swing lands on nothing and the commit burns its whole timeout. Hypixel quest NPCs make this
   * routine rather than rare: Kelly stands two blocks from the spruce the quest sends you to (log
   * 2026-08-27, 15:41:58 - one tree in 103 s, against two trees in 8 s on the NPC-free oak).
   *
   * <p>Returns true when a nudge is in progress and the caller should do nothing else this tick.
   * The destination is picked once and held: re-picking every tick with a moving body underfoot
   * just jitters.
   */
  boolean nudgeOffStackedBody(LocalPlayer player, Level level, Options opts, BlockPos log) {
    if (!(level instanceof ClientLevel clientLevel) || log == null) {
      return false;
    }
    if (!MacroObstruction.isAnyoneStackedOn(clientLevel, player)) {
      treeChop.stackedTicks = 0;
      return false;
    }
    if (++treeChop.stackedTicks < STACKED_NUDGE_TICKS) {
      return false;
    }
    treeChop.stackedTicks = 0;
    if (treeChop.nudgeTarget != null) {
      return true;
    } // already stepping off; let the existing nudge finish
    final BlockPos step =
        MacroObstruction.findSidestep(clientLevel, player, log, Vec3.atCenterOf(log), null);
    // Nowhere within break reach is clear. Say so and let the caller run its own blocked path
    // rather
    // than stand on them forever.
    if (step == null) {
      return false;
    }
    treeChop.releaseChop(opts);
    treeChop.nudgeTarget = step;
    treeChop.nudgeTicks = 0;
    treeChop.activity = "Standing on someone, stepping aside";
    return true;
  }

  void repathToAlternateStand(LocalPlayer player, Level level, Options opts, String reason) {
    treeChop.releaseChop(opts);
    treeChop.activity = reason;
    BlockPos standPos = treeChop.clusterTarget.stand();
    List<BlockPos> targetClusterBlocks = treeChop.clusterTarget.blocks();
    BlockPos targetLog = treeChop.clusterTarget.base();
    if (standPos != null) {
      treeChop.exclusions.blockStand(standPos.asLong());
    }
    final BlockPos nextStand =
        treeChop.standFinder.findAlternate(level, player, targetClusterBlocks, targetLog);
    if (nextStand != null) {
      treeChop.clusterTarget.moveStand(nextStand);
      treeChop.clusterTarget.snapshotApproach(log -> treeChop.targeting.isSelectedLog(level, log));
      treeChop.exclusions.clearBlockedLogs();
      treeChop.targetState.clearAimCandidate();
      treeChop.contactProgress.resetArrival();
      treeChop.abortNav();
      treeChop.issueNav(nextStand.getX(), nextStand.getY(), nextStand.getZ());
      treeChop.state = State.PATH_TO_CLUSTER;
      return;
    }
    if (targetLog != null) {
      treeChop.exclusions.markUnreachable(targetLog.asLong());
    }
    treeChop.treeSelection.skipRouteTrunk();
    // Multi-log mode: we cut what we could reach low on this tree; mark it handled so we don't
    // revisit it.
    if (!treeChop.isTreecapMode()) {
      treeChop.treeSelection.markTargetClusterHandled();
    }
    treeChop.abortNav();
    treeChop.toScan(SCAN_COOLDOWN_TICKS);
  }

  /** A short step in, and only from a spot that has genuinely run dry. See MAX_NUDGES_PER_TREE. */
  boolean tryNudge(LocalPlayer player, Level level) {
    if (treeChop.nudgesUsedThisTree >= MAX_NUDGES_PER_TREE) {
      return false;
    }
    final BlockPos spot =
        treeChop.standFinder.findNudge(
            level,
            player,
            treeChop.clusterTarget.blocks(),
            treeChop.targeting.effectiveBreakMaxY(player),
            NUDGE_MAX_BLOCKS);
    if (spot == null) {
      return false;
    }
    treeChop.nudgesUsedThisTree++;
    treeChop.nudgeTarget = spot;
    treeChop.nudgeTicks = 0;
    treeChop.activity = "Stepping in to reach remaining logs";
    return true;
  }

  void driveTowardLogOrStop(Options opts, LocalPlayer player, BlockPos log) {
    treeChop.motion.driveTowardLogOrStop(opts, player, log);
  }
}
