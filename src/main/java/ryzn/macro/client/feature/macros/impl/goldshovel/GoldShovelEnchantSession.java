package ryzn.macro.client.feature.macros.impl.goldshovel;

import lombok.AccessLevel;
import lombok.Getter;
import net.minecraft.core.BlockPos;
import ryzn.macro.client.input.ClickConfirm;

/**
 * State for one enchant-table visit. The three "click requested" markers are windows, not latches:
 * Hypixel drops clicks aimed at a menu page it is still swapping, and a permanent latch turned
 * every dropped click into a hang - the step blocked its own retry until the state timed out. Each
 * marker now lapses after {@link #CLICK_CONFIRM_TICKS}, so a click that produced nothing is simply
 * sent again while the observed checks (tool in the slot, Efficiency page open, confirmation seen)
 * still end the step the moment it really worked. One session covers both table visits: the
 * shovel's Efficiency II and the axe's Efficiency V run the same states, so each state resets only
 * its own marker on entry.
 */
final class GoldShovelEnchantSession {

  private static final int CLICK_CONFIRM_TICKS = 30;

  private final ClickConfirm toolInsertion = new ClickConfirm();
  private final ClickConfirm efficiencyClick = new ClickConfirm();
  private final ClickConfirm enchantLevelClick = new ClickConfirm();

  private boolean guiOpened;
  private boolean closeRequested;
  private BlockPos tablePos;

  @Getter(AccessLevel.PACKAGE)
  private boolean enchantConfirmed;

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

  BlockPos tablePos() {
    return tablePos;
  }

  void rememberTable(BlockPos tablePos) {
    this.tablePos = tablePos;
  }

  void clearTable() {
    tablePos = null;
  }

  /**
   * True while the sent insert click is still expected to land - ticks down, so a lost click
   * retries.
   */
  boolean isToolInsertionRequested() {
    return toolInsertion.awaiting(0);
  }

  void markToolInsertionRequested() {
    toolInsertion.sent(0, CLICK_CONFIRM_TICKS);
  }

  void resetToolInsertionRequest() {
    toolInsertion.clear();
  }

  boolean isEfficiencyClickRequested() {
    return efficiencyClick.awaiting(0);
  }

  void markEfficiencyClickRequested() {
    efficiencyClick.sent(0, CLICK_CONFIRM_TICKS);
  }

  void resetEfficiencyClickRequest() {
    efficiencyClick.clear();
  }

  boolean isEnchantLevelClickRequested() {
    return enchantLevelClick.awaiting(0);
  }

  void markEnchantLevelClickRequested() {
    enchantLevelClick.sent(0, CLICK_CONFIRM_TICKS);
  }

  void markEnchantConfirmed() {
    enchantConfirmed = true;
  }

  void resetForEnchantLevel() {
    enchantLevelClick.clear();
    enchantConfirmed = false;
  }

  void reset() {
    guiOpened = false;
    closeRequested = false;
    tablePos = null;
    toolInsertion.clear();
    efficiencyClick.clear();
    enchantLevelClick.clear();
    enchantConfirmed = false;
  }
}
