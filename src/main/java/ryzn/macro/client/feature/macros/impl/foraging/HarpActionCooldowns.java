package ryzn.macro.client.feature.macros.impl.foraging;

final class HarpActionCooldowns {

  private int interactionTicks;
  private int menuClickTicks;

  void armInteraction(int ticks) {
    interactionTicks = ticks;
  }

  boolean waitForInteraction() {
    if (interactionTicks <= 0) {
      return false;
    }
    interactionTicks--;
    return true;
  }

  void armMenuClick(int ticks) {
    menuClickTicks = ticks;
  }

  boolean waitForMenuClick() {
    if (menuClickTicks <= 0) {
      return false;
    }
    menuClickTicks--;
    return true;
  }

  void reset() {
    interactionTicks = 0;
    menuClickTicks = 0;
  }
}
