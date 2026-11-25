package ryzn.macro.client.feature.macros.impl.wheat;

final class WheatPathAttempts {

  private int retryCooldownTicks;
  private int attempts;

  void recordRequest(int cooldownTicks) {
    retryCooldownTicks = cooldownTicks;
    attempts++;
  }

  boolean waitForRetry() {
    if (retryCooldownTicks <= 0) {
      return false;
    }
    retryCooldownTicks--;
    return true;
  }

  boolean exhausted(int maximumAttempts) {
    return attempts >= maximumAttempts;
  }

  int count() {
    return attempts;
  }

  void reset() {
    retryCooldownTicks = 0;
    attempts = 0;
  }
}
