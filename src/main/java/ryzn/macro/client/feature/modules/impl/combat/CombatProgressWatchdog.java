package ryzn.macro.client.feature.modules.impl.combat;

import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;

final class CombatProgressWatchdog {

  enum Outcome {
    NONE,
    RECOVER,
    GIVE_UP
  }

  private UUID targetId;
  private double closestDistance = Double.MAX_VALUE;
  private int noProgressTicks;
  private int recoveries;

  @Getter(AccessLevel.PACKAGE)
  private boolean stalled;

  Outcome update(
      UUID currentTargetId,
      double distance,
      boolean hittable,
      boolean monitorProgress,
      double progressEpsilon,
      int stallTicks,
      int giveUpWindows) {
    if (!currentTargetId.equals(targetId)) {
      targetId = currentTargetId;
      closestDistance = distance;
      noProgressTicks = 0;
      recoveries = 0;
      stalled = false;
      return Outcome.NONE;
    }
    if (hittable || distance <= closestDistance - progressEpsilon) {
      if (distance < closestDistance) {
        closestDistance = distance;
      }
      noProgressTicks = 0;
      recoveries = 0;
      stalled = false;
      return Outcome.NONE;
    }
    if (!monitorProgress) {
      noProgressTicks = 0;
      stalled = false;
      return Outcome.NONE;
    }
    if (++noProgressTicks < stallTicks) {
      return Outcome.NONE;
    }

    noProgressTicks = 0;
    stalled = true;
    return ++recoveries >= giveUpWindows ? Outcome.GIVE_UP : Outcome.RECOVER;
  }

  int noProgressTicks() {
    return noProgressTicks;
  }

  void clearStalled() {
    stalled = false;
  }

  void reset() {
    targetId = null;
    closestDistance = Double.MAX_VALUE;
    noProgressTicks = 0;
    recoveries = 0;
    stalled = false;
  }
}
