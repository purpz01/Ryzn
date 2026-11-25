package ryzn.macro.client.feature.modules.impl.combat;

final class CombatTraceCadence {
  private int attackTicks;
  private int modeTicks;

  boolean shouldTraceAttack(boolean enabled, boolean fired, int interval) {
    if (!enabled) {
      return false;
    }
    return fired || ++attackTicks % interval == 0;
  }

  boolean shouldTraceMode(boolean enabled, int interval) {
    return enabled && ++modeTicks % interval == 0;
  }

  void reset() {
    attackTicks = 0;
    modeTicks = 0;
  }
}
