package ryzn.macro.client.feature.macros.impl.wheat;

import ryzn.macro.client.input.GuiActionTiming;

final class WheatMerchantActionTiming {

  private int npcClickCooldownTicks;
  private int npcClickAttempts;
  private int menuClickCooldownTicks;
  private int reopenAttempts;

  void enterInteraction() {
    npcClickCooldownTicks = 0;
    npcClickAttempts = 0;
  }

  void recordNpcClick(int cooldownTicks) {
    npcClickAttempts++;
    npcClickCooldownTicks = cooldownTicks;
  }

  boolean waitForNpcClick() {
    if (npcClickCooldownTicks <= 0) {
      return false;
    }
    npcClickCooldownTicks--;
    return true;
  }

  int npcClickAttempts() {
    return npcClickAttempts;
  }

  void enterMenu() {
    // NOT zero: the first click would otherwise land on the tick the menu appears, before it
    // accepts input.
    menuClickCooldownTicks = GuiActionTiming.firstMenuClickDelay();
  }

  void armMenuClick(int cooldownTicks) {
    menuClickCooldownTicks = cooldownTicks;
  }

  boolean waitForMenuClick() {
    if (menuClickCooldownTicks <= 0) {
      return false;
    }
    menuClickCooldownTicks--;
    return true;
  }

  boolean tryReopen(int maximumAttempts) {
    return ++reopenAttempts <= maximumAttempts;
  }

  void resetReopens() {
    reopenAttempts = 0;
  }

  void reset() {
    npcClickCooldownTicks = 0;
    npcClickAttempts = 0;
    menuClickCooldownTicks = 0;
    reopenAttempts = 0;
  }
}
