package ryzn.macro.client.feature.macros.impl.tree;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class TreeChopExclusions {

  private final Map<Long, Long> regeneratingUntilMs = new HashMap<>();
  private final Set<Long> unreachableClusters = new HashSet<>();
  private final Set<Long> blockedLogs = new HashSet<>();
  private final Set<Long> blockedStands = new HashSet<>();
  private final Set<Long> blockedStandView = Collections.unmodifiableSet(blockedStands);
  private final Set<Long> temporarilySkippedClusters = new HashSet<>();
  private final Set<Long> loggedAvoidPositions = new HashSet<>();

  void markUnreachable(long clusterKey) {
    unreachableClusters.add(clusterKey);
  }

  void blockLog(long logKey) {
    blockedLogs.add(logKey);
  }

  boolean isLogBlocked(long logKey) {
    return blockedLogs.contains(logKey);
  }

  void clearBlockedLogs() {
    blockedLogs.clear();
  }

  void blockStand(long standKey) {
    blockedStands.add(standKey);
  }

  Set<Long> blockedStandKeys() {
    return blockedStandView;
  }

  void clearTargetBlocks() {
    blockedLogs.clear();
    blockedStands.clear();
  }

  /**
   * Park a tree that answered with "you cannot damage a tree while it is regenerating" until {@code
   * untilMs}. Regrowth is on a server timer, so the only useful reaction is to leave it alone for a
   * while - the old skip set was never read by anything, which is why the bot kept walking back to
   * the same regrowing tree.
   */
  void skipRegeneratingTree(long treeKey, long untilMs) {
    regeneratingUntilMs.put(treeKey, untilMs);
  }

  boolean isRegenerating(long treeKey, long nowMs) {
    final Long until = regeneratingUntilMs.get(treeKey);
    if (until == null) {
      return false;
    }
    if (nowMs >= until) {
      regeneratingUntilMs.remove(treeKey);
      return false;
    }
    return true;
  }

  void clearTemporarySkips() {
    temporarilySkippedClusters.clear();
  }

  boolean firstAvoidLog(long positionKey) {
    return loggedAvoidPositions.add(positionKey);
  }

  void clearAvoidLogs() {
    loggedAvoidPositions.clear();
  }

  void reset() {
    regeneratingUntilMs.clear();
    unreachableClusters.clear();
    blockedLogs.clear();
    blockedStands.clear();
    temporarilySkippedClusters.clear();
    loggedAvoidPositions.clear();
  }
}
