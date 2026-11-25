package ryzn.macro.client.feature.macros.impl.tree;

import lombok.AccessLevel;
import lombok.Getter;

final class TreeChopPhase {

  @Getter(AccessLevel.PACKAGE)
  private boolean centeredAtTrunk;

  @Getter(AccessLevel.PACKAGE)
  private boolean trunkEntryCleared;

  @Getter(AccessLevel.PACKAGE)
  private boolean clearingTrunkEntry;

  @Getter(AccessLevel.PACKAGE)
  private boolean high;

  private int noLowLogTicks;
  private int maxBreakLogY = Integer.MAX_VALUE;

  void markCenteredAtTrunk() {
    centeredAtTrunk = true;
  }

  void clearCenteredAtTrunk() {
    centeredAtTrunk = false;
  }

  void beginTrunkEntryClearing() {
    clearingTrunkEntry = true;
  }

  void completeTrunkEntry() {
    clearingTrunkEntry = false;
    trunkEntryCleared = true;
  }

  boolean highPhaseConfirmed(boolean hasReachableLowLog, int confirmationTicks) {
    if (high) {
      return false;
    }
    if (!trunkEntryCleared || hasReachableLowLog) {
      noLowLogTicks = 0;
      return false;
    }
    return ++noLowLogTicks >= confirmationTicks;
  }

  void beginHighPhase() {
    high = true;
  }

  void captureBreakMaxY(int maxY) {
    if (maxBreakLogY == Integer.MAX_VALUE) {
      maxBreakLogY = maxY;
    }
  }

  void clearBreakMaxY() {
    maxBreakLogY = Integer.MAX_VALUE;
  }

  void resetHighPhase() {
    high = false;
    noLowLogTicks = 0;
  }

  void reset() {
    centeredAtTrunk = false;
    trunkEntryCleared = false;
    clearingTrunkEntry = false;
    clearBreakMaxY();
    resetHighPhase();
  }
}
