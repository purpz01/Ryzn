package ryzn.macro.client.feature.macros.impl.wheat;

final class WheatSweepProgress {

  private int waypointIndex;
  private int stallTicks;
  private double bestDistanceSqr = Double.MAX_VALUE;

  int currentIndex(int waypointCount) {
    if (waypointCount <= 0 || waypointIndex >= waypointCount) {
      waypointIndex = 0;
    }
    return waypointIndex;
  }

  void advance(int waypointCount) {
    waypointIndex = waypointCount <= 0 ? 0 : (waypointIndex + 1) % waypointCount;
  }

  boolean stalled(double distanceSqr, double improvementMargin, int maxStallTicks) {
    if (distanceSqr + improvementMargin < bestDistanceSqr) {
      bestDistanceSqr = distanceSqr;
      stallTicks = 0;
      return false;
    }
    return ++stallTicks > maxStallTicks;
  }

  void resetMovement() {
    stallTicks = 0;
    bestDistanceSqr = Double.MAX_VALUE;
  }

  void reset() {
    waypointIndex = 0;
    resetMovement();
  }
}
