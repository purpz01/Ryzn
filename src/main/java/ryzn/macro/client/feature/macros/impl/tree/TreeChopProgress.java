package ryzn.macro.client.feature.macros.impl.tree;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import ryzn.macro.client.feature.macros.impl.tree.TreeChopMacro.State;

/**
 * @description Applies the existing per-tree progress watchdog and timed stuck-tree skip.
 */
final class TreeChopProgress {

  /** Long enough to work the rest of the area, short enough that the tree comes back later. */
  private static final long STUCK_TREE_SKIP_MS = 90_000L;

  /** Walking plus standing on one tree this long without a log also gives it up. */
  private static final int STUCK_ON_TREE_TICKS = 20 * 60;

  /**
   * At the tree (lining up, standing, chopping) this long without a single log coming off means the
   * tree cannot be cut from anywhere we can stand - typically a jungle trunk seen from a ledge
   * above it. Every local retry (recontact, nudge, alternate stand) resets its own timer, so
   * without an overall one a tree like that could hold the bot indefinitely.
   */
  private static final int STUCK_AT_TREE_TICKS = 20 * 8;

  private final TreeChopMacro treeChop;

  TreeChopProgress(TreeChopMacro treeChop) {
    this.treeChop = treeChop;
  }

  /**
   * Gives the current tree up when no log has come off it for too long, whatever the reason. Any
   * log of the tree disappearing counts as progress (ours or another player's), so a tree that is
   * being cut is never abandoned.
   *
   * @return true when the tree was given up this tick
   */
  boolean checkTreeProgress(Minecraft mc, Level level, Options opts) {
    final BlockPos tree = treeChop.clusterTarget.base();
    final boolean onTree =
        treeChop.state == State.PATH_TO_CLUSTER
            || treeChop.state == State.CONTACT_TREE
            || treeChop.state == State.ARRIVE_AT_CLUSTER
            || treeChop.state == State.BREAK_ONE_LOG;
    if (tree == null || !onTree) {
      treeChop.watchedTree = null;
      return false;
    }
    final int logsLeft = countLogsLeft(level);
    if (!tree.equals(treeChop.watchedTree)
        || treeChop.chopped != treeChop.watchedChopped
        || logsLeft < treeChop.watchedLogsLeft) {
      treeChop.watchedTree = tree;
      treeChop.watchedChopped = treeChop.chopped;
      treeChop.watchedLogsLeft = logsLeft;
      treeChop.ticksOnTreeWithoutLog = 0;
      treeChop.ticksAtTreeWithoutLog = 0;
      return false;
    }
    treeChop.ticksOnTreeWithoutLog++;
    if (treeChop.state != State.PATH_TO_CLUSTER) {
      treeChop.ticksAtTreeWithoutLog++;
    }
    final String reason;
    if (treeChop.ticksAtTreeWithoutLog > STUCK_AT_TREE_TICKS) {
      reason = "Could not cut this tree from here, picking another";
    } else if (treeChop.ticksOnTreeWithoutLog > STUCK_ON_TREE_TICKS) {
      reason = "Tree took too long to reach, picking another";
    } else {
      return false;
    }
    abandonStuckTree(mc, opts, tree, reason);
    return true;
  }

  int countLogsLeft(Level level) {
    int left = 0;
    for (BlockPos log : treeChop.clusterTarget.blocks()) {
      if (level.hasChunkAt(log) && treeChop.targeting.isSelectedLog(level, log)) {
        left++;
      }
    }
    return left;
  }

  /**
   * Parks the tree on the same timed skip a regenerating tree uses, so it is left alone for a while
   * and then becomes a candidate again - a permanent skip could, over a long session, run a small
   * island out of trees.
   */
  void abandonStuckTree(Minecraft mc, Options opts, BlockPos tree, String reason) {
    final long until = System.currentTimeMillis() + STUCK_TREE_SKIP_MS;
    for (BlockPos log : treeChop.clusterTarget.blocks()) {
      treeChop.exclusions.skipRegeneratingTree(log.asLong(), until);
    }
    treeChop.exclusions.skipRegeneratingTree(tree.asLong(), until);
    final BlockPos routeTrunk = treeChop.route.current();
    if (routeTrunk != null) {
      treeChop.exclusions.skipRegeneratingTree(routeTrunk.asLong(), until);
    }
    treeChop.watchedTree = null;
    treeChop.releaseChop(opts);
    treeChop.treeSelection.skipRouteTrunk();
    treeChop.abortNav();
    treeChop.toScan(0);
    treeChop.activity = reason;
    TreeChopMacro.say(
        mc,
        "§bTree Chop§7: §e"
            + reason
            + " §7(stuck at "
            + tree.getX()
            + " "
            + tree.getY()
            + " "
            + tree.getZ()
            + ").");
  }
}
