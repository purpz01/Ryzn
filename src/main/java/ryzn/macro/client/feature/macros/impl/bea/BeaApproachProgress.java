package ryzn.macro.client.feature.macros.impl.bea;

final class BeaApproachProgress {

  private int stuckTicks;
  private int repathCooldownTicks;
  private double bestDistanceSq = Double.MAX_VALUE;

  boolean shouldRepath(
      double distanceSq,
      double progressMargin,
      double farDistanceSq,
      int stuckLimit,
      int repathCooldown) {
    if (distanceSq + progressMargin < bestDistanceSq) {
      stuckTicks = 0;
    } else {
      stuckTicks++;
    }
    bestDistanceSq = distanceSq;

    if (repathCooldownTicks > 0) {
      repathCooldownTicks--;
    }
    if ((distanceSq > farDistanceSq || stuckTicks > stuckLimit) && repathCooldownTicks <= 0) {
      repathCooldownTicks = repathCooldown;
      return true;
    }
    return false;
  }

  int stuckTicks() {
    return stuckTicks;
  }

  void resetProgress() {
    stuckTicks = 0;
    bestDistanceSq = Double.MAX_VALUE;
  }

  void reset() {
    resetProgress();
    repathCooldownTicks = 0;
  }
}
