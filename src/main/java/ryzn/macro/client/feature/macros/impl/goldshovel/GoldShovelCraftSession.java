package ryzn.macro.client.feature.macros.impl.goldshovel;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

final class GoldShovelCraftSession {

  private boolean guiOpened;
  private boolean closeRequested;

  @Setter(AccessLevel.PACKAGE)
  private int step;

  @Setter(AccessLevel.PACKAGE)
  private int pendingGridSlotIndex = -1;

  @Setter(AccessLevel.PACKAGE)
  private int returnSlotIndex = -1;

  @Getter(AccessLevel.PACKAGE)
  private boolean crafted;

  private int outputWaitTicks;
  private boolean outputQuickMoveTried;
  private boolean outputPickupFallbackTried;

  @Getter(AccessLevel.PACKAGE)
  private boolean hotbarMoveRequested;

  void markGuiOpened() {
    guiOpened = true;
  }

  boolean wasGuiOpened() {
    return guiOpened;
  }

  void clearGuiOpened() {
    guiOpened = false;
  }

  boolean shouldRequestClose(int stateTicks) {
    if (!closeRequested || stateTicks % 20 == 0) {
      closeRequested = true;
      return true;
    }
    return false;
  }

  void resetCloseRequest() {
    closeRequested = false;
  }

  int step() {
    return step;
  }

  int pendingGridSlotIndex() {
    return pendingGridSlotIndex;
  }

  int returnSlotIndex() {
    return returnSlotIndex;
  }

  int outputWaitTicks() {
    return outputWaitTicks;
  }

  void markCrafted() {
    crafted = true;
    step = 9;
  }

  void markHotbarMoveRequested() {
    hotbarMoveRequested = true;
  }

  void resetHotbarMoveRequest() {
    hotbarMoveRequested = false;
  }

  void resetProgress() {
    step = 0;
    pendingGridSlotIndex = -1;
    returnSlotIndex = -1;
    outputWaitTicks = 0;
    outputQuickMoveTried = false;
    outputPickupFallbackTried = false;
  }

  void resetForOpenCraft() {
    guiOpened = false;
    closeRequested = false;
    crafted = false;
    hotbarMoveRequested = false;
    resetProgress();
  }

  void reset() {
    resetForOpenCraft();
  }
}
