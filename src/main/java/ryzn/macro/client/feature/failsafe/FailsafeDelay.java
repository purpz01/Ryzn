package ryzn.macro.client.feature.failsafe;

import java.util.function.IntSupplier;

final class FailsafeDelay {
  private long conditionSince = Long.MIN_VALUE;
  private int sampledExtraDelay;
  private boolean latched;

  boolean update(boolean condition, long tick, int delayTicks) {
    return update(condition, tick, delayTicks, () -> 0);
  }

  boolean update(boolean condition, long tick, int delayTicks, IntSupplier extraDelay) {
    if (!condition) {
      reset();
      return false;
    }
    if (latched) {
      return false;
    }
    if (conditionSince == Long.MIN_VALUE) {
      conditionSince = tick;
      sampledExtraDelay = Math.max(0, extraDelay.getAsInt());
      if (delayTicks + sampledExtraDelay > 0) {
        return false;
      }
    }
    if (tick - conditionSince < Math.max(0, delayTicks) + sampledExtraDelay) {
      return false;
    }
    latched = true;
    return true;
  }

  void reset() {
    conditionSince = Long.MIN_VALUE;
    sampledExtraDelay = 0;
    latched = false;
  }
}
