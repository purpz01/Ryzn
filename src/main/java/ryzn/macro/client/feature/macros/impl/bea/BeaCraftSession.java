package ryzn.macro.client.feature.macros.impl.bea;

/**
 * Bea's craft bookkeeping: how many blocks are made, and the Craft Item screen's open/close state.
 */
final class BeaCraftSession {

  private int coalBlocksCrafted;
  private int goldBlocksCrafted;
  private boolean guiOpened;
  private boolean closeRequested;

  int coalBlocksCrafted() {
    return coalBlocksCrafted;
  }

  int goldBlocksCrafted() {
    return goldBlocksCrafted;
  }

  void recordCoalBlockCrafted() {
    coalBlocksCrafted++;
  }

  void recordGoldBlockCrafted() {
    goldBlocksCrafted++;
  }

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

  void reset() {
    coalBlocksCrafted = 0;
    goldBlocksCrafted = 0;
    guiOpened = false;
    closeRequested = false;
  }
}
