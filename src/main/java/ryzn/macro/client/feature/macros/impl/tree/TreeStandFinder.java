package ryzn.macro.client.feature.macros.impl.tree;

import static ryzn.macro.client.util.world.MacroPos.distanceToBlockCenterSqr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.pathfinder.PathRenderer;
import ryzn.macro.client.util.text.RyznChat;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class TreeStandFinder {

  private static final int[] yOffsets = {0, -1, 1, -2, 2};

  /**
   * How far above / below the trunk foot a stand may sit.
   *
   * <p>Without this the finder climbed the tree. Candidates are ranked by how many logs they can
   * reach, and a spot on a branch six blocks up sees far more of the trunk than anything on the
   * ground, so it always won; {@link #standable} accepts it because a branch log below a gap really
   * is solid-below/air-at. The native pathfinder then correctly refuses to route into a canopy, the
   * stand gets blacklisted, and {@code findAlternate} hands back an even higher one. Logs from a
   * live run show the goal climbing a block per retry (y 76 -> 81 while the player stood at 74) for
   * five or six seconds per tree - which was the whole "takes forever to get to the next tree".
   * Feet belong on the ground next to the trunk.
   */
  // Zero, not one: a stand a block above the trunk foot is usually on a root or a raised branch,
  // and getting
  // there means a step-jump onto the tree. Feet stay at or below the foot of the trunk.
  private static final int STAND_MAX_ABOVE_BASE = 0;

  private static final int STAND_MAX_BELOW_BASE = 2;

  /** Extra logs a step must unlock before it is worth taking. */
  private static final int MIN_NUDGE_GAIN = 2;

  private final TreeChopAvoidance avoidance;
  private final TreeChopExclusions exclusions;

  BlockPos findForCluster(Level level, LocalPlayer player, LogCluster cluster, BlockPos trunkRoot) {
    List<BlockPos> logs = cluster.blocks();
    int baseY = trunkRoot.getY();
    BlockPos anyAdjacent =
        bestAdjacentForSeeds(level, player, footLogs(logs, baseY), logs, Set.of(), baseY);
    if (anyAdjacent != null) {
      return anyAdjacent;
    }

    debugFallback(trunkRoot);
    List<BlockPos> foot = liveSafeLogs(level, footLogs(logs, baseY));
    BlockPos wide = findAround(level, player, trunkRoot, 3, Set.of(), baseY, foot);
    if (wide != null) {
      return wide;
    }
    BlockPos centroid =
        new BlockPos(Mth.floor(cluster.centroidX()), baseY, Mth.floor(cluster.centroidZ()));
    return findAround(level, player, centroid, 6, Set.of(), baseY, foot);
  }

  BlockPos findAlternate(Level level, LocalPlayer player, List<BlockPos> logs, BlockPos trunkRoot) {
    int baseY = trunkRoot != null ? trunkRoot.getY() : lowestY(logs);
    BlockPos adjacent =
        bestAdjacentForSeeds(
            level, player, footLogs(logs, baseY), logs, exclusions.blockedStandKeys(), baseY);
    return adjacent != null ? adjacent : findWider(level, player, logs, baseY);
  }

  /**
   * A short step that brings more of the tree into reach, or null if standing still is already as
   * good.
   *
   * <p>Only called once the current spot has run out of reachable logs, so this never competes with
   * chopping - it is the alternative to abandoning the stand and pathfinding to a new one, which is
   * what used to happen and cost a full walk. Candidates are limited to {@code maxOffset} blocks
   * away and must beat the current position's reachable-log count outright.
   */
  BlockPos findNudge(
      Level level, LocalPlayer player, List<BlockPos> logs, int capY, int maxOffset) {
    List<BlockPos> live = liveSafeLogs(level, logs);
    if (live.isEmpty()) {
      return null;
    }
    double eyeHeight = player.getEyePosition().y - player.getY();
    double reach = TreeLogTargeting.breakReach(player);
    int feetX = Mth.floor(player.getX());
    int feetY = Mth.floor(player.getY());
    int feetZ = Mth.floor(player.getZ());
    int here =
        reachableLogCount(
            level, player, live, new BlockPos(feetX, feetY, feetZ), eyeHeight, reach, capY);
    // Walking somewhere to reach one extra log costs more than the log is worth, and a step that
    // small
    // reads as fidgeting. Only move when it opens up a real pocket of the tree.
    final int required = here + MIN_NUDGE_GAIN;

    BlockPos best = null;
    int bestCount = 0;
    double bestDistance = Double.MAX_VALUE;
    for (int dx = -maxOffset; dx <= maxOffset; dx++) {
      for (int dz = -maxOffset; dz <= maxOffset; dz++) {
        if (dx == 0 && dz == 0) {
          continue;
        }
        for (int dy = -1; dy <= 1; dy++) {
          int x = feetX + dx;
          int y = feetY + dy;
          int z = feetZ + dz;
          BlockPos spot = new BlockPos(x, y, z);
          if (!isSafe(spot) || blocksRoute(player, spot) || !standable(level, x, y, z)) {
            continue;
          }
          int count = reachableLogCount(level, player, live, spot, eyeHeight, reach, capY);
          if (count < required) {
            continue;
          }
          double distance = dx * dx + dz * dz;
          if (count > bestCount || (count == bestCount && distance < bestDistance)) {
            best = spot;
            bestCount = count;
            bestDistance = distance;
          }
        }
      }
    }
    return best;
  }

  /** Logs at the foot of the trunk - the only ones worth standing next to. */
  private static List<BlockPos> footLogs(List<BlockPos> logs, int baseY) {
    List<BlockPos> foot = new ArrayList<>();
    if (logs == null) {
      return foot;
    }
    for (BlockPos log : logs) {
      if (log.getY() <= baseY + STAND_MAX_ABOVE_BASE
          && log.getY() >= baseY - STAND_MAX_BELOW_BASE) {
        foot.add(log);
      }
    }
    return foot;
  }

  private static int lowestY(List<BlockPos> logs) {
    int lowest = Integer.MAX_VALUE;
    if (logs != null) {
      for (BlockPos log : logs) {
        lowest = Math.min(lowest, log.getY());
      }
    }
    return lowest == Integer.MAX_VALUE ? 0 : lowest;
  }

  BlockPos findAround(Level level, LocalPlayer player, BlockPos base, int maxRadius) {
    return findAround(level, player, base, maxRadius, Set.of(), base.getY(), List.of(base));
  }

  private BlockPos findAround(
      Level level,
      LocalPlayer player,
      BlockPos base,
      int maxRadius,
      Set<Long> excludedStands,
      int baseY,
      List<BlockPos> foot) {
    Vec3 position = player.position();
    BlockPos best = null;
    double bestDistance = Double.MAX_VALUE;
    for (int radius = 1; radius <= maxRadius; radius++) {
      for (int dy : yOffsets) {
        int y = base.getY() + dy;
        if (y > baseY + STAND_MAX_ABOVE_BASE || y < baseY - STAND_MAX_BELOW_BASE) {
          continue;
        }
        for (int ox = -radius; ox <= radius; ox++) {
          for (int oz = -radius; oz <= radius; oz++) {
            if (Math.abs(ox) != radius && Math.abs(oz) != radius) {
              continue;
            }
            int x = base.getX() + ox;
            int z = base.getZ() + oz;
            BlockPos candidate = new BlockPos(x, y, z);
            if (excludedStands.contains(candidate.asLong())
                || !isSafe(candidate)
                || blocksRoute(player, candidate)
                || !nearTrunkFace(candidate, foot)
                || !standable(level, x, y, z)) {
              continue;
            }
            double dx = (x + 0.5) - position.x;
            double dz = (z + 0.5) - position.z;
            double dyy = y - position.y;
            double lx = x - base.getX();
            double lz = z - base.getZ();
            double distance = dx * dx + dz * dz + dyy * dyy + (lx * lx + lz * lz) * 0.2;
            if (distance < bestDistance) {
              bestDistance = distance;
              best = candidate;
            }
          }
        }
      }
    }
    return best;
  }

  private static boolean nearTrunkFace(BlockPos stand, List<BlockPos> foot) {
    for (BlockPos log : foot) {
      double dx = Math.max(Math.abs(stand.getX() - log.getX()) - 0.5, 0);
      double dz = Math.max(Math.abs(stand.getZ() - log.getZ()) - 0.5, 0);
      if (dx * dx + dz * dz <= 1.0) {
        return true;
      }
    }
    return false;
  }

  private BlockPos bestAdjacentForSeeds(
      Level level,
      LocalPlayer player,
      List<BlockPos> seedLogs,
      List<BlockPos> logs,
      Set<Long> excludedStands,
      int baseY) {
    Vec3 playerPos = player.position();
    double eyeHeight = player.getEyePosition().y - player.getY();
    double reach = TreeLogTargeting.breakReach(player);
    List<BlockPos> live = liveSafeLogs(level, logs);
    List<BlockPos> seeds = liveSafeLogs(level, seedLogs);
    if (live.isEmpty() || seeds.isEmpty()) {
      return null;
    }

    Set<Long> seen = new HashSet<>();
    BlockPos best = null;
    double bestScore = Double.MAX_VALUE;
    for (BlockPos log : seeds) {
      for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (dx == 0 && dz == 0) {
            continue;
          }
          for (int dy : yOffsets) {
            int x = log.getX() + dx;
            int y = log.getY() + dy;
            int z = log.getZ() + dz;
            if (y > baseY + STAND_MAX_ABOVE_BASE || y < baseY - STAND_MAX_BELOW_BASE) {
              continue;
            }
            BlockPos stand = new BlockPos(x, y, z);
            if (!seen.add(stand.asLong()) || excludedStands.contains(stand.asLong())) {
              continue;
            }
            if (!isSafe(stand) || blocksRoute(player, stand) || !standable(level, x, y, z)) {
              continue;
            }

            int count =
                reachableLogCount(
                    level, player, live, stand, eyeHeight, reach, Mth.floor(y + eyeHeight));
            if (count == 0) {
              continue;
            }
            double walk =
                Math.sqrt(
                    square((x + 0.5) - playerPos.x)
                        + square(y - playerPos.y)
                        + square((z + 0.5) - playerPos.z));
            double score = walk;
            if (score < bestScore) {
              best = stand;
              bestScore = score;
            }
          }
        }
      }
    }
    return best;
  }

  private List<BlockPos> liveSafeLogs(Level level, List<BlockPos> logs) {
    List<BlockPos> live = new ArrayList<>();
    if (logs == null) {
      return live;
    }
    for (BlockPos log : logs) {
      if (isSafe(log) && isSelectedLog(level, log)) {
        live.add(log);
      }
    }
    return live;
  }

  private int reachableLogCount(
      Level level,
      LocalPlayer player,
      List<BlockPos> live,
      BlockPos stand,
      double eyeHeight,
      double reach,
      int capY) {
    Vec3 eye = new Vec3(stand.getX() + 0.5, stand.getY() + eyeHeight, stand.getZ() + 0.5);
    double reachSquared = reach * reach;
    int count = 0;
    for (BlockPos log : live) {
      if (log.getY() > capY) {
        continue;
      }
      if (distanceToBlockCenterSqr(eye.x, eye.y, eye.z, log) > reachSquared) {
        continue;
      }
      if (visible(level, player, eye, Vec3.atCenterOf(log), log, live)) {
        count++;
      }
    }
    return count;
  }

  private BlockPos findWider(Level level, LocalPlayer player, List<BlockPos> logs, int baseY) {
    BlockPos best = null;
    double bestScore = Double.MAX_VALUE;
    double eyeHeight = player.getEyePosition().y - player.getY();
    double reach = TreeLogTargeting.breakReach(player);
    List<BlockPos> foot = liveSafeLogs(level, footLogs(logs, baseY));
    for (BlockPos log : foot) {
      if (!isSafe(log) || !isSelectedLog(level, log)) {
        continue;
      }
      BlockPos stand =
          findAround(level, player, log, 4, exclusions.blockedStandKeys(), baseY, foot);
      if (stand == null) {
        continue;
      }
      Vec3 eye = new Vec3(stand.getX() + 0.5, stand.getY() + eyeHeight, stand.getZ() + 0.5);
      int capY = stand.getY() + 4;
      boolean reaches = false;
      for (BlockPos target : logs) {
        if (!isSafe(target) || !isSelectedLog(level, target) || target.getY() > capY) {
          continue;
        }
        if (distanceToBlockCenterSqr(eye.x, eye.y, eye.z, target) > reach * reach) {
          continue;
        }
        if (visible(level, player, eye, Vec3.atCenterOf(target), target, logs)) {
          reaches = true;
          break;
        }
      }
      if (!reaches) {
        continue;
      }
      double score =
          distanceToBlockCenterSqr(player.getX(), player.getY(), player.getZ(), stand)
              + centerDistanceSquared(stand, log) * 0.2;
      if (score < bestScore) {
        bestScore = score;
        best = stand;
      }
    }
    return best;
  }

  private boolean visible(
      Level level,
      LocalPlayer player,
      Vec3 eye,
      Vec3 target,
      BlockPos expected,
      List<BlockPos> logs) {
    BlockHitResult hit =
        level.clip(
            new ClipContext(
                eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(expected)) {
      return true;
    }
    return logs.contains(hit.getBlockPos()) && isSelectedLog(level, hit.getBlockPos());
  }

  private boolean isSafe(BlockPos position) {
    return position != null
        && !avoidance.isAvoidTarget(
            RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).chopLogType(), position);
  }

  private boolean blocksRoute(LocalPlayer player, BlockPos target) {
    return avoidance.blocksRoute(
        RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).chopLogType(),
        player.getX(),
        player.getZ(),
        target);
  }

  private static boolean isSelectedLog(Level level, BlockPos position) {
    return level != null
        && position != null
        && LogScanner.isLog(
            level.getBlockState(position),
            RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).chopLogType());
  }

  private static double centerDistanceSquared(BlockPos first, BlockPos second) {
    return square(first.getX() - second.getX())
        + square(first.getY() - second.getY())
        + square(first.getZ() - second.getZ());
  }

  private static double square(double value) {
    return value * value;
  }

  private static boolean standable(Level level, int x, int y, int z) {
    BlockPos floor = new BlockPos(x, y - 1, z);
    return !level.getBlockState(floor).is(BlockTags.LOGS)
        && !level.getBlockState(floor).is(BlockTags.LEAVES)
        && solid(level, x, y - 1, z)
        && !solid(level, x, y, z)
        && !solid(level, x, y + 1, z);
  }

  private static boolean solid(Level level, int x, int y, int z) {
    BlockPos position = new BlockPos(x, y, z);
    return !level.getBlockState(position).getCollisionShape(level, position).isEmpty();
  }

  private void debugFallback(BlockPos base) {
    if (!PathRenderer.isEnabled() || base == null) {
      return;
    }
    if (!exclusions.firstAvoidLog(base.asLong() * 31 + 7)) {
      return;
    }
    RyznChat.send(
        "§bTree Chop§7: §8nowhere to stand beside the log at "
            + base.getX()
            + " "
            + base.getY()
            + " "
            + base.getZ()
            + " - widening the search.");
  }
}
