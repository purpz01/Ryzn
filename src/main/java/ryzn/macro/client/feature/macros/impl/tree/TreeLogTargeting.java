package ryzn.macro.client.feature.macros.impl.tree;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.debug.DebugLog;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class TreeLogTargeting {

  private static final double FACE_AIM_INSET = 0.08;
  // Server block-hit range is 4.5; 4.2 leaves headroom so the bot never out-reaches what a player
  // could do.
  private static final double TREE_BREAK_RANGE = 4.2;
  private static final int TRUNK_ENTRY_BELOW_FEET_BLOCKS = 2;

  /**
   * Largest yaw swing worth making for a "better" log. Beyond this a human would pick something
   * nearer.
   */
  private static final double MAX_COMFORTABLE_TURN_DEGREES = 100.0;

  private static final double COST_DISTANCE_WEIGHT = 1.0;
  private static final double COST_ANGULAR_WEIGHT = 0.08;
  private static final double COST_VERTICAL_STEP_WEIGHT = 0.35;
  private static final double COST_DIAGONAL_ADJACENT_PENALTY = 0.75;
  private static final double COST_NON_ADJACENT_PENALTY = 6.0;
  private static final double COST_SAME_COLUMN_BONUS = 1.25;
  private static final double COST_UPWARD_CONTINUATION_BONUS = 1.0;
  private static final double COST_CROSSHAIR_STABILITY_BONUS = 0.6;
  private static final double COST_FLOOR = 0.05;

  /** Slack on the eye->log segment when gathering bodies to test, and on each body's own box. */
  private static final double ENTITY_SPAN_MARGIN = 1.0;

  private static final double ENTITY_PICK_SLACK = 0.1;

  private final TreeChopAvoidance avoidance;
  private final TreeChopExclusions exclusions;
  private final TreeChopPhase phase;
  private final Supplier<TreeLogType> logType;
  private BlockPos clusterRoot;
  private List<BlockPos> clusterBlocks = List.of();
  private BlockPos lastTracedSelection;

  void setCluster(BlockPos root, List<BlockPos> blocks) {
    clusterRoot = root;
    clusterBlocks = blocks == null ? List.of() : List.copyOf(blocks);
    lastTracedSelection = null;
  }

  void clearCluster() {
    setCluster(null, List.of());
  }

  int breakMaxLogY(LocalPlayer player) {
    return movingBreakMaxY(player) + 2;
  }

  int movingBreakMaxY(LocalPlayer player) {
    return Mth.floor(player.getEyePosition().y);
  }

  /**
   * Live break ceiling, recomputed from where the player is standing right now.
   *
   * <p>This used to be captured once per tree and then frozen. If that first capture happened while
   * the player was still a block or two low - mid-approach, or before settling onto the stand - the
   * ceiling stayed too low for the whole tree and upper logs that were comfortably in reach were
   * never considered. The bot no longer moves during a tree, so a live value is equal to a correct
   * capture and immune to a bad one.
   */
  int effectiveBreakMaxY(LocalPlayer player) {
    return breakMaxLogY(player);
  }

  int effectiveTargetMaxY(LocalPlayer player) {
    return phase.isHigh() ? effectiveBreakMaxY(player) : movingBreakMaxY(player);
  }

  boolean isEligibleLog(int capY, BlockPos log) {
    return log != null && log.getY() <= capY;
  }

  BlockPos selectClosestReachableLog(LocalPlayer player, Level level, BlockPos lastBrokenLog) {
    return selectReachableLog(player, level, lastBrokenLog);
  }

  BlockPos selectNextReachableLog(LocalPlayer player, Level level, BlockPos anchor) {
    return selectReachableLog(player, level, anchor);
  }

  BlockPos selectReachableLog(LocalPlayer player, Level level, BlockPos anchor) {
    if (!phase.isTrunkEntryCleared()) {
      BlockPos entry = selectTrunkEntryLog(player, level);
      if (entry != null && (anchor == null || !entry.equals(anchor))) {
        return entry;
      }
    }
    if (!phase.isHigh()) {
      return selectReachableLogByCost(player, level, anchor, movingBreakMaxY(player), true, false);
    }
    BlockPos trunkLog = selectTrunkColumnReachableLog(player, level, anchor);
    return trunkLog != null
        ? trunkLog
        : selectReachableLogByCost(player, level, anchor, effectiveBreakMaxY(player), false, true);
  }

  BlockPos selectTrunkEntryLog(LocalPlayer player, Level level) {
    if (phase.isTrunkEntryCleared() || clusterRoot == null) {
      return null;
    }
    return lowestReachableLog(
        player.getEyePosition(),
        clusterBlocks,
        log -> isTrunkEntryLog(player, level, log) && isReachableLog(player, level, log));
  }

  static BlockPos lowestReachableLog(Vec3 eye, List<BlockPos> logs, Predicate<BlockPos> eligible) {
    BlockPos best = null;
    double bestScore = Double.MAX_VALUE;
    for (BlockPos log : logs) {
      if (!eligible.test(log)) {
        continue;
      }
      double score = distanceToBlockSqr(eye, log);
      if (best == null
          || log.getY() < best.getY()
          || (log.getY() == best.getY() && score < bestScore)) {
        best = log;
        bestScore = score;
      }
    }
    return best;
  }

  BlockPos contactTargetLog(LocalPlayer player, Level level) {
    BlockPos trunkLog = nearestLiveTrunkColumnLog(player, level);
    if (trunkLog != null) {
      return trunkLog;
    }
    if (isContactableLog(player, level, clusterRoot)) {
      return clusterRoot.immutable();
    }
    return nearestLiveSelectedLog(player, level);
  }

  boolean hasBlockingTrunkEntryLog(LocalPlayer player, Level level) {
    if (player == null || level == null || clusterRoot == null) {
      return false;
    }
    int minimumY = Mth.floor(player.getY()) - TRUNK_ENTRY_BELOW_FEET_BLOCKS;
    int maximumY = movingBreakMaxY(player);
    for (BlockPos log : clusterBlocks) {
      if (!sameColumn(log, clusterRoot) || !isSafeLog(log) || !isSelectedLog(level, log)) {
        continue;
      }
      if (log.getY() >= minimumY && log.getY() <= maximumY) {
        return true;
      }
    }
    return false;
  }

  /**
   * Debug: for the "Repositioning for trunk entry" no-progress loop, describe each same-column
   * trunk-entry candidate and why it is / isn't a selectable break target - reach, line of sight,
   * exclusion, selection. {@link #selectTrunkEntryLog} returning null while {@link
   * #hasBlockingTrunkEntryLog} is true is what spins the loop; this shows which condition fails
   * (vis=false = leaves/log blocking sight, inReach=false = too far, entry=false = out of the Y
   * band or excluded).
   */
  String describeTrunkEntry(LocalPlayer player, Level level) {
    if (player == null || level == null || clusterRoot == null) {
      return "no clusterRoot";
    }
    int minimumY = Mth.floor(player.getY()) - TRUNK_ENTRY_BELOW_FEET_BLOCKS;
    int maximumY = movingBreakMaxY(player);
    Vec3 eye = player.getEyePosition();
    double reach = breakReach(player);
    StringBuilder sb = new StringBuilder();
    sb.append("root=")
        .append(shortPos(clusterRoot))
        .append(" feetY=")
        .append(Mth.floor(player.getY()))
        .append(" yBand[")
        .append(minimumY)
        .append("..")
        .append(maximumY)
        .append("]")
        .append(" reach=")
        .append(String.format("%.2f", reach))
        .append(" centered=")
        .append(phase.isCenteredAtTrunk());
    int shown = 0;
    for (BlockPos log : clusterBlocks) {
      if (!sameColumn(log, clusterRoot) || log.getY() < minimumY || log.getY() > maximumY) {
        continue;
      }
      Vec3 center = aimPointForLog(player, log);
      double dist = Math.sqrt(center.distanceToSqr(eye));
      sb.append(" | ")
          .append(shortPos(log))
          .append(" sel=")
          .append(isSelectedLog(level, log))
          .append(" safe=")
          .append(isSafeLog(log))
          .append(" blocked=")
          .append(exclusions.isLogBlocked(log.asLong()))
          .append(" entry=")
          .append(isTrunkEntryLog(player, level, log))
          .append(" dist=")
          .append(String.format("%.2f", dist))
          .append(" inReach=")
          .append(center.distanceToSqr(eye) <= reach * reach)
          .append(" vis=")
          .append(visible(level, player, eye, center, log));
      if (++shown >= 5) {
        sb.append(" ...");
        break;
      }
    }
    if (shown == 0) {
      sb.append(" (no same-column entry-band logs)");
    }
    return sb.toString();
  }

  /**
   * True when a same-column trunk-entry log is VISIBLE and in the entry Y band but just out of
   * break reach. That means the bot only needs to walk closer - not abandon the stand and repath -
   * which is the acacia reposition loop: Hypixel's block-reach lets the stand finder pick a stand a
   * hair beyond the tree-break cap, so {@link #selectTrunkEntryLog} returns null (out of reach)
   * while {@link #hasBlockingTrunkEntryLog} stays true. Excludes blocked/invisible logs, which ARE
   * a real reason to repath.
   */
  boolean hasApproachableTrunkEntryLog(LocalPlayer player, Level level) {
    if (player == null || level == null || clusterRoot == null) {
      return false;
    }
    int minimumY = Mth.floor(player.getY()) - TRUNK_ENTRY_BELOW_FEET_BLOCKS;
    int maximumY = movingBreakMaxY(player);
    Vec3 eye = player.getEyePosition();
    double reach = breakReach(player);
    for (BlockPos log : clusterBlocks) {
      if (!sameColumn(log, clusterRoot) || log.getY() < minimumY || log.getY() > maximumY) {
        continue;
      }
      if (!isSelectedLog(level, log) || !isSafeLog(log) || exclusions.isLogBlocked(log.asLong())) {
        continue;
      }
      Vec3 center = aimPointForLog(player, log);
      if (center.distanceToSqr(eye) > reach * reach && visible(level, player, eye, center, log)) {
        return true;
      }
    }
    return false;
  }

  boolean isTrunkEntryLog(LocalPlayer player, Level level, BlockPos log) {
    if (player == null || log == null || clusterRoot == null || !isClusterLog(log)) {
      return false;
    }
    if (exclusions.isLogBlocked(log.asLong()) || !isSafeLog(log) || !isSelectedLog(level, log)) {
      return false;
    }
    int entryMinY = Mth.floor(player.getY()) - TRUNK_ENTRY_BELOW_FEET_BLOCKS;
    return log.getY() >= entryMinY && log.getY() <= movingBreakMaxY(player);
  }

  /**
   * Break whatever valid log the crosshair is already on.
   *
   * <p>This used to demand the crosshair rest on the single cheapest log, and reject it otherwise.
   * A live session logged 28 rejections: the camera would be sitting on a perfectly reachable trunk
   * log, refuse it in favour of a "better" one, rotate away toward a block it often could not hit,
   * stall until the crosshair-blocked timeout expired, blacklist it, and leave the top of the tree
   * standing. Breaking what you are looking at is both what a player does and strictly more
   * productive than holding out for the theoretical optimum - the cost function still chooses where
   * to look NEXT, it just no longer vetoes a log already under the crosshair.
   */
  boolean isCrosshairCommitAllowed(
      LocalPlayer player, Level level, BlockPos crosshair, BlockPos anchor) {
    return crosshair != null
        && isEligibleLog(effectiveBreakMaxY(player), crosshair)
        && isReachableLog(player, level, crosshair);
  }

  boolean hasReachableLogAtOrBelow(LocalPlayer player, Level level, int capY, BlockPos ignored) {
    for (BlockPos log : clusterBlocks) {
      if (ignored != null && log.equals(ignored)) {
        continue;
      }
      if (exclusions.isLogBlocked(log.asLong()) || !isEligibleLog(capY, log)) {
        continue;
      }
      if (isReachableLog(player, level, log)) {
        return true;
      }
    }
    return false;
  }

  boolean isCurrentPhaseLog(LocalPlayer player, Level level, BlockPos log) {
    if (log == null || !isEligibleLog(effectiveTargetMaxY(player), log)) {
      return false;
    }
    if (!phase.isTrunkEntryCleared()) {
      BlockPos baseLog = selectTrunkEntryLog(player, level);
      return baseLog != null && log.getY() == baseLog.getY() && isTrunkEntryLog(player, level, log);
    }
    return phase.isHigh() ? isHighLog(player, log) : isLowLog(player, log);
  }

  boolean isChoppable(LocalPlayer player, Level level, BlockPos log) {
    return isReachableLog(player, level, log);
  }

  boolean canStartBreakNow(Minecraft mc, LocalPlayer player, Level level, BlockPos log) {
    if (mc == null || player == null || level == null || log == null) {
      return false;
    }
    if (!isClusterLog(log) || !isSafeLog(log) || !isSelectedLog(level, log)) {
      return false;
    }
    return isEligibleLog(effectiveBreakMaxY(player), log)
        && isReachableLog(player, level, log)
        && hasActualCrosshairHit(mc, level, log);
  }

  boolean isReachableLog(LocalPlayer player, Level level, BlockPos log) {
    if (player == null
        || level == null
        || log == null
        || !isSafeLog(log)
        || !isSelectedLog(level, log)) {
      return false;
    }
    Vec3 eye = player.getEyePosition();
    Vec3 center = aimPointForLog(player, log);
    double reach = breakReach(player);
    return center.distanceToSqr(eye) <= reach * reach && visible(level, player, eye, center, log);
  }

  boolean isSelectedLog(Level level, BlockPos position) {
    return level != null
        && position != null
        && LogScanner.isLog(
            level.getBlockState(position),
            RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).chopLogType());
  }

  boolean isClusterLog(BlockPos position) {
    return position != null && clusterBlocks.contains(position);
  }

  BlockPos crosshairSelectedClusterLog(Minecraft mc, Level level) {
    if (mc == null || level == null) {
      return null;
    }
    HitResult hit = mc.hitResult;
    if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
      return null;
    }
    BlockPos position = blockHit.getBlockPos();
    if (!isClusterLog(position) || !isSafeLog(position) || !isSelectedLog(level, position)) {
      return null;
    }
    LocalPlayer player = mc.player;
    if (player == null) {
      return null;
    }
    double reach = breakReach(player);
    return blockHit.getLocation().distanceToSqr(player.getEyePosition()) <= reach * reach
        ? position.immutable()
        : null;
  }

  boolean hasActualCrosshairHit(Minecraft mc, Level level, BlockPos log) {
    BlockPos hit = crosshairSelectedClusterLog(mc, level);
    return hit != null && hit.equals(log);
  }

  boolean blockedByCrosshair(Minecraft mc) {
    HitResult hit = mc == null ? null : mc.hitResult;
    // A body counts as blocked too. Requiring a BlockHitResult meant an NPC in the way reported the
    // wrong
    // reason AND never fed the blocked-timeout, so nothing ever gave up on the log behind it.
    if (hit instanceof EntityHitResult) {
      return true;
    }
    return hit instanceof BlockHitResult blockHit
        && blockHit.getType() == HitResult.Type.BLOCK
        && (mc.level == null
            || !isClusterLog(blockHit.getBlockPos())
            || !isSelectedLog(mc.level, blockHit.getBlockPos()));
  }

  boolean isSafeLog(BlockPos position) {
    return position != null
        && !avoidance.isAvoidTarget(
            RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).chopLogType(), position);
  }

  double logTargetCost(LocalPlayer player, BlockPos anchor, BlockPos log) {
    double distanceSquared = distanceToBlockSqr(player.getEyePosition(), log);
    double angular = angularDistanceToLog(player, log);
    double cost = distanceSquared * COST_DISTANCE_WEIGHT + angular * COST_ANGULAR_WEIGHT;
    if (anchor != null) {
      cost += Math.abs(log.getY() - anchor.getY()) * COST_VERTICAL_STEP_WEIGHT;
      boolean adjacent = isAdjacentLog(anchor, log);
      if (adjacent && !isFaceAdjacentLog(anchor, log)) {
        cost += COST_DIAGONAL_ADJACENT_PENALTY;
      }
      if (!adjacent) {
        cost += COST_NON_ADJACENT_PENALTY;
      }
      if (sameColumn(log, anchor)) {
        cost -= COST_SAME_COLUMN_BONUS;
        if (log.getY() == anchor.getY() + 1) {
          cost -= COST_UPWARD_CONTINUATION_BONUS;
        }
      }
    }
    Minecraft mc = Minecraft.getInstance();
    if (mc.level != null && hasActualCrosshairHit(mc, mc.level, log)) {
      cost -= COST_CROSSHAIR_STABILITY_BONUS;
    }
    return Math.max(COST_FLOOR, cost);
  }

  static Vec3 aimPointForLog(LocalPlayer player, BlockPos log) {
    return closestFacePoint(player.getEyePosition(), log);
  }

  static boolean isAdjacentLog(BlockPos first, BlockPos second) {
    if (first == null || second == null || first.equals(second)) {
      return false;
    }
    return Math.abs(first.getX() - second.getX()) <= 1
        && Math.abs(first.getY() - second.getY()) <= 1
        && Math.abs(first.getZ() - second.getZ()) <= 1;
  }

  static boolean sameColumn(BlockPos first, BlockPos second) {
    return first != null
        && second != null
        && first.getX() == second.getX()
        && first.getZ() == second.getZ();
  }

  /**
   * Two rules, in order: work the trunk column from the bottom up, then take whatever reachable log
   * needs the least camera movement.
   *
   * <p>That is what the reference footage shows - the trunk disappears upward from the base, then
   * the branches go. The previous version scored every candidate with eight weights (angular,
   * vertical step, diagonal penalty, non-adjacent penalty, same-column bonus, upward-continuation
   * bonus, crosshair-stability bonus, distance) and tiered them face-adjacent / adjacent26 /
   * fallback. It regularly degraded to the "fallback" tier - a log adjacent to nothing - and hopped
   * around the canopy at costs of 19 to 27 while leaving the trunk standing.
   */
  private BlockPos selectReachableLogByCost(
      LocalPlayer player,
      Level level,
      BlockPos anchor,
      int capY,
      boolean lowOnly,
      boolean highOnly) {
    BlockPos bestTrunk = null;
    BlockPos bestOther = null;
    double bestOtherAngle = Double.MAX_VALUE;
    final BlockPos crosshairHit = crosshairSelectedClusterLog(Minecraft.getInstance(), level);
    for (BlockPos log : clusterBlocks) {
      if (anchor != null && log.equals(anchor)) {
        continue;
      }
      if (exclusions.isLogBlocked(log.asLong()) || !isEligibleLog(capY, log)) {
        continue;
      }
      if ((lowOnly && !isLowLog(player, log)) || (highOnly && !isHighLog(player, log))) {
        continue;
      }
      if (!isReachableLog(player, level, log)) {
        continue;
      }
      // Trunk-first, but never at the cost of a spin: a trunk log on the far side of the column
      // would
      // otherwise win outright and swing the camera through 180 degrees at ~510 deg/s. Past the
      // comfort
      // cap it stops being privileged and competes on turn size like everything else.
      if (clusterRoot != null
          && sameColumn(log, clusterRoot)
          && yawChangeToLog(player, log) <= MAX_COMFORTABLE_TURN_DEGREES) {
        if (bestTrunk == null || log.getY() < bestTrunk.getY()) {
          bestTrunk = log;
        }
        continue;
      }
      // Stickiness: a log already under the crosshair counts as zero angle. Without this, "nearest
      // angle"
      // measures from a camera that is itself moving toward the last pick, so choosing A rotates
      // toward
      // A, which makes B the nearest, which rotates back - the target flips every few ticks, the
      // crosshair never settles long enough to start a break, and the tree gets abandoned mid-way.
      double angle =
          crosshairHit != null && crosshairHit.equals(log)
              ? 0.0
              : angularDistanceToLog(player, log);
      if (angle < bestOtherAngle) {
        bestOtherAngle = angle;
        bestOther = log;
      }
    }
    BlockPos chosen = bestTrunk != null ? bestTrunk : bestOther;
    if (chosen != null) {
      traceSelection(chosen, bestTrunk != null ? "trunk" : "nearest-angle");
    }
    return chosen;
  }

  private BlockPos selectTrunkColumnReachableLog(LocalPlayer player, Level level, BlockPos anchor) {
    if (clusterRoot == null) {
      return null;
    }
    Vec3 eye = player.getEyePosition();
    int movingCap = movingBreakMaxY(player);
    int capY = effectiveBreakMaxY(player);
    BlockPos best = null;
    double bestScore = Double.MAX_VALUE;
    for (BlockPos log : clusterBlocks) {
      if (anchor != null && log.equals(anchor)) {
        continue;
      }
      if (exclusions.isLogBlocked(log.asLong()) || !sameColumn(log, clusterRoot)) {
        continue;
      }
      if (log.getY() <= movingCap
          || !isEligibleLog(capY, log)
          || !isReachableLog(player, level, log)) {
        continue;
      }
      double score = (log.getY() - movingCap) * 100.0 + distanceToBlockSqr(eye, log);
      if (score < bestScore
          || (Math.abs(score - bestScore) < 0.001
              && best != null
              && log.asLong() < best.asLong())) {
        best = log;
        bestScore = score;
      }
    }
    return best;
  }

  private BlockPos nearestLiveTrunkColumnLog(LocalPlayer player, Level level) {
    if (clusterRoot == null) {
      return null;
    }
    Vec3 eye = player.getEyePosition();
    BlockPos best = null;
    double bestDistance = Double.MAX_VALUE;
    for (BlockPos log : clusterBlocks) {
      if (!sameColumn(log, clusterRoot) || !isContactableLog(player, level, log)) {
        continue;
      }
      double distance = distanceToBlockSqr(eye, log);
      if (distance < bestDistance
          || (Math.abs(distance - bestDistance) < 0.001
              && best != null
              && log.getY() < best.getY())) {
        best = log;
        bestDistance = distance;
      }
    }
    return best;
  }

  private BlockPos nearestLiveSelectedLog(LocalPlayer player, Level level) {
    Vec3 eye = player.getEyePosition();
    BlockPos best = null;
    double bestDistance = Double.MAX_VALUE;
    int capY = effectiveTargetMaxY(player);
    for (BlockPos log : clusterBlocks) {
      if (!isSafeLog(log) || !isSelectedLog(level, log) || !isEligibleLog(capY, log)) {
        continue;
      }
      double distance = distanceToBlockSqr(eye, log);
      if (distance < bestDistance) {
        best = log;
        bestDistance = distance;
      }
    }
    return best;
  }

  private boolean isContactableLog(LocalPlayer player, Level level, BlockPos log) {
    return log != null
        && isClusterLog(log)
        && isSafeLog(log)
        && isSelectedLog(level, log)
        && isEligibleLog(effectiveBreakMaxY(player), log);
  }

  private boolean visible(
      Level level, LocalPlayer player, Vec3 eye, Vec3 target, BlockPos expected) {
    if (entityBlocks(level, player, eye, target)) {
      return false;
    }
    BlockHitResult hit =
        level.clip(
            new ClipContext(
                eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(expected)) {
      return true;
    }
    return false;
  }

  /**
   * Is a body standing between the eye and this log?
   *
   * <p>Reachability used to ask only {@code level.clip}, which sees blocks and nothing else, so a
   * log behind an NPC stayed "reachable" and kept winning selection. But the gate that actually
   * starts a break reads {@code mc.hitResult}, which IS entity-aware and comes back as an
   * EntityHitResult with a body in front - so {@link #canStartBreakNow} could never be true. The
   * bot aimed at that log forever, holding left click into the NPC, and never got as far as
   * equipping the axe. Hypixel quest NPCs stand right next to the trees they send you to, so this
   * was routine rather than rare.
   */
  private static boolean entityBlocks(Level level, LocalPlayer player, Vec3 eye, Vec3 target) {
    final AABB span = new AABB(eye, target).inflate(ENTITY_SPAN_MARGIN);
    for (Entity entity : level.getEntities(player, span, Entity::isPickable)) {
      if (entity.getBoundingBox().inflate(ENTITY_PICK_SLACK).clip(eye, target).isPresent()) {
        return true;
      }
    }
    return false;
  }

  /** Absolute yaw change needed to face {@code log}, 0-180. */
  private double yawChangeToLog(LocalPlayer player, BlockPos log) {
    Vec3 eye = player.getEyePosition();
    Vec3 aim = aimPointForLog(player, log);
    double horizontal = Math.hypot(aim.x - eye.x, aim.z - eye.z);
    // Directly overhead: yaw is meaningless there and atan2 is pure noise, so never call it a big
    // turn.
    if (horizontal < 0.5) {
      return 0.0;
    }
    return Math.abs(
        Mth.wrapDegrees((float) (bearingYaw(eye.x, eye.z, aim.x, aim.z) - player.getYRot())));
  }

  private double angularDistanceToLog(LocalPlayer player, BlockPos log) {
    Vec3 eye = player.getEyePosition();
    Vec3 aim = aimPointForLog(player, log);
    double yaw =
        Math.abs(
            Mth.wrapDegrees((float) (bearingYaw(eye.x, eye.z, aim.x, aim.z) - player.getYRot())));
    double pitch = Math.abs(targetPitch(eye, aim) - player.getXRot());
    return Math.sqrt(yaw * yaw + pitch * pitch);
  }

  private void traceSelection(BlockPos chosen, String rule) {
    if (!DebugLog.isEnabled(DebugLog.Category.MACRO) || chosen.equals(lastTracedSelection)) {
      return;
    }
    lastTracedSelection = chosen.immutable();
    DebugLog.trace(
        DebugLog.Category.MACRO, "Tree chop: cutting the log at %s (%s)", shortPos(chosen), rule);
  }

  private static boolean isFaceAdjacentLog(BlockPos first, BlockPos second) {
    return first != null
        && second != null
        && Math.abs(first.getX() - second.getX())
                + Math.abs(first.getY() - second.getY())
                + Math.abs(first.getZ() - second.getZ())
            == 1;
  }

  private static boolean isLowLog(LocalPlayer player, BlockPos log) {
    return log != null && log.getY() <= Mth.floor(player.getEyePosition().y);
  }

  private static boolean isHighLog(LocalPlayer player, BlockPos log) {
    return log != null && log.getY() > Mth.floor(player.getEyePosition().y);
  }

  static double distanceToBlockSqr(Vec3 point, BlockPos block) {
    double x = Mth.clamp(point.x, block.getX(), block.getX() + 1.0);
    double y = Mth.clamp(point.y, block.getY(), block.getY() + 1.0);
    double z = Mth.clamp(point.z, block.getZ(), block.getZ() + 1.0);
    return square(point.x - x) + square(point.y - y) + square(point.z - z);
  }

  private static Vec3 closestFacePoint(Vec3 eye, BlockPos block) {
    double minX = block.getX();
    double minY = block.getY();
    double minZ = block.getZ();
    double maxX = minX + 1.0;
    double maxY = minY + 1.0;
    double maxZ = minZ + 1.0;
    double x = Mth.clamp(eye.x, minX + FACE_AIM_INSET, maxX - FACE_AIM_INSET);
    double y = Mth.clamp(eye.y, minY + FACE_AIM_INSET, maxY - FACE_AIM_INSET);
    double z = Mth.clamp(eye.z, minZ + FACE_AIM_INSET, maxZ - FACE_AIM_INSET);
    boolean outsideX = eye.x < minX || eye.x > maxX;
    boolean outsideY = eye.y < minY || eye.y > maxY;
    boolean outsideZ = eye.z < minZ || eye.z > maxZ;
    if (outsideX) {
      x = eye.x < minX ? minX + FACE_AIM_INSET : maxX - FACE_AIM_INSET;
    }
    if (outsideY) {
      y = eye.y < minY ? minY + FACE_AIM_INSET : maxY - FACE_AIM_INSET;
    }
    if (outsideZ) {
      z = eye.z < minZ ? minZ + FACE_AIM_INSET : maxZ - FACE_AIM_INSET;
    }
    if (!outsideX && !outsideY && !outsideZ) {
      double[] distances = {
        Math.abs(eye.x - minX),
        Math.abs(maxX - eye.x),
        Math.abs(eye.y - minY),
        Math.abs(maxY - eye.y),
        Math.abs(eye.z - minZ),
        Math.abs(maxZ - eye.z)
      };
      int face = 0;
      for (int i = 1; i < distances.length; i++) {
        if (distances[i] < distances[face]) {
          face = i;
        }
      }
      switch (face) {
        case 0 -> x = minX + FACE_AIM_INSET;
        case 1 -> x = maxX - FACE_AIM_INSET;
        case 2 -> y = minY + FACE_AIM_INSET;
        case 3 -> y = maxY - FACE_AIM_INSET;
        case 4 -> z = minZ + FACE_AIM_INSET;
        case 5 -> z = maxZ - FACE_AIM_INSET;
        default -> {}
      }
    }
    return new Vec3(x, y, z);
  }

  /**
   * The reach the breaker actually enforces. Shared with {@link TreeStandFinder}: it used to rate
   * stands with {@code blockInteractionRange() + 0.75} (~5.25) while breaking capped at 4.2, so it
   * kept electing stands a whole block too far out - the bot walked to a spot, could not reach a
   * single log, stood there for the contact timeout and repathed. One number or the two disagree
   * again.
   */
  static double breakReach(LocalPlayer player) {
    return Math.min(player.blockInteractionRange() + 0.05, TREE_BREAK_RANGE);
  }

  private static float targetPitch(Vec3 eye, Vec3 target) {
    double dx = target.x - eye.x;
    double dy = target.y - eye.y;
    double dz = target.z - eye.z;
    return (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
  }

  private static double bearingYaw(double eyeX, double eyeZ, double targetX, double targetZ) {
    return Math.toDegrees(Math.atan2(targetZ - eyeZ, targetX - eyeX)) - 90.0;
  }

  private static double square(double value) {
    return value * value;
  }

  private static String shortPos(BlockPos position) {
    return position == null
        ? "null"
        : position.getX() + " " + position.getY() + " " + position.getZ();
  }
}
