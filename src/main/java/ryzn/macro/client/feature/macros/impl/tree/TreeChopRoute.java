package ryzn.macro.client.feature.macros.impl.tree;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Owns the current live trunk, handled columns, and approach retry progress. */
final class TreeChopRoute {

  private final Set<Long> handled = new HashSet<>();

  private BlockPos current;
  private int approachAttempts;
  private double bestApproachDistance = Double.MAX_VALUE;

  BlockPos current() {
    return current;
  }

  void select(BlockPos trunk) {
    current = trunk;
    resetApproach();
  }

  boolean isHandled(BlockPos trunk) {
    return trunk != null && handled.contains(trunk.asLong());
  }

  int handledCount() {
    return handled.size();
  }

  void clearHandled() {
    handled.clear();
  }

  void resetApproachAttempts() {
    approachAttempts = 0;
  }

  boolean approachLimitExceeded(double distance, double minimumProgress, int maximumAttempts) {
    if (distance < bestApproachDistance - minimumProgress) {
      bestApproachDistance = distance;
      approachAttempts = 0;
      return false;
    }
    return ++approachAttempts > maximumAttempts;
  }

  void completeCurrent() {
    if (current != null) {
      handled.add(current.asLong());
    }
    current = null;
    resetApproach();
  }

  void reset() {
    current = null;
    resetApproach();
    handled.clear();
  }

  private void resetApproach() {
    approachAttempts = 0;
    bestApproachDistance = Double.MAX_VALUE;
  }
}
