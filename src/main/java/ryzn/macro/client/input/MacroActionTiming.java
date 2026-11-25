package ryzn.macro.client.input;

import lombok.Getter;

public final class MacroActionTiming {

  @Getter private boolean pathRequested;
  private int interactionTicks;
  private int menuClickTicks;
  private int useTicks;

  public void markPathRequested() {
    pathRequested = true;
  }

  public void clearPathRequest() {
    pathRequested = false;
  }

  public void armInteraction(int ticks) {
    interactionTicks = ticks;
  }

  public boolean waitForInteraction() {
    if (interactionTicks <= 0) {
      return false;
    }
    interactionTicks--;
    return true;
  }

  public void armMenuClick(int ticks) {
    menuClickTicks = ticks;
  }

  public boolean waitForMenuClick() {
    if (menuClickTicks <= 0) {
      return false;
    }
    menuClickTicks--;
    return true;
  }

  public boolean advanceUseHold(int holdTicks) {
    return useTicks++ < holdTicks;
  }

  public void armUse(int ticks) {
    useTicks = ticks;
  }

  public boolean waitForUse() {
    if (useTicks <= 0) {
      return false;
    }
    useTicks--;
    return true;
  }

  public void enterState() {
    pathRequested = false;
    interactionTicks = 0;
    // NOT zero: a state that opens a menu would otherwise click on the very tick the screen
    // appears.
    menuClickTicks = GuiActionTiming.firstMenuClickDelay();
    useTicks = 0;
  }
}
