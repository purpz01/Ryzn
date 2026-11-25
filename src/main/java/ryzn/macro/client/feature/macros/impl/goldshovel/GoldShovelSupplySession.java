package ryzn.macro.client.feature.macros.impl.goldshovel;

import lombok.AccessLevel;
import lombok.Getter;
import ryzn.macro.client.input.ClickConfirm;

/**
 * State for one supply run. The "click requested" markers are windows, not latches: Hypixel drops
 * clicks aimed at a menu page it is still swapping, and a permanent latch turned every dropped
 * click into a hang because the step blocked its own retry. Each marker lapses after {@link
 * #CLICK_CONFIRM_TICKS} so the click is simply sent again, while the observed checks still end the
 * step the moment it really worked.
 */
final class GoldShovelSupplySession {

  private static final int CLICK_CONFIRM_TICKS = 30;
  private static final int NO_BASELINE = -1;

  private final ClickConfirm stickClick = new ClickConfirm();
  private final ClickConfirm expBottleOptions = new ClickConfirm();
  private final ClickConfirm expBottleStackClick = new ClickConfirm();

  private boolean lumberGuiOpened;
  private boolean librarianGuiOpened;
  private boolean tradingOptionsOpened;

  @Getter(AccessLevel.PACKAGE)
  private boolean expBottleSwapRequested;

  // One Librarian visit: the axe leg buys three stacks, so purchases are observed, not just sent.
  private int expBottleBaseline = NO_BASELINE;
  private int tradingOptionsReopens;

  boolean isStickClickRequested() {
    return stickClick.awaiting(0);
  }

  void markStickClickRequested() {
    stickClick.sent(0, CLICK_CONFIRM_TICKS);
  }

  void resetStickClickRequest() {
    stickClick.clear();
  }

  boolean isExpBottleOptionsRequested() {
    return expBottleOptions.awaiting(0);
  }

  void markExpBottleOptionsRequested() {
    expBottleOptions.sent(0, CLICK_CONFIRM_TICKS);
  }

  void resetExpBottleOptionsRequest() {
    expBottleOptions.clear();
  }

  /**
   * Held open against the bottle count, so a purchase that landed frees the next click immediately.
   */
  boolean isExpBottleStackClickRequested(int bottlesHeld) {
    return expBottleStackClick.awaiting(bottlesHeld);
  }

  void markExpBottleStackClickRequested(int bottlesHeld) {
    expBottleStackClick.sent(bottlesHeld, CLICK_CONFIRM_TICKS);
  }

  /**
   * Starts one Librarian visit; the stack count bought is measured against the bottles held here.
   */
  void beginExpBottleVisit() {
    expBottleBaseline = NO_BASELINE;
    tradingOptionsReopens = 0;
    expBottleStackClick.clear();
  }

  /** Taken on the first purchase tick, when the player is known to be loaded. */
  void captureExpBottleBaseline(int bottlesHeld) {
    if (expBottleBaseline == NO_BASELINE) {
      expBottleBaseline = bottlesHeld;
    }
  }

  int expBottleBaseline() {
    return expBottleBaseline;
  }

  int expBottleStacksBought(int bottlesHeld) {
    if (expBottleBaseline == NO_BASELINE) {
      return 0;
    }
    return Math.max(0, (bottlesHeld - expBottleBaseline) / 64);
  }

  int noteTradingOptionsReopen() {
    return ++tradingOptionsReopens;
  }

  void markExpBottleSwapRequested() {
    expBottleSwapRequested = true;
  }

  void resetExpBottleSwapRequest() {
    expBottleSwapRequested = false;
  }

  boolean wasLumberGuiOpened() {
    return lumberGuiOpened;
  }

  void markLumberGuiOpened() {
    lumberGuiOpened = true;
  }

  void clearLumberGuiOpened() {
    lumberGuiOpened = false;
  }

  boolean wasLibrarianGuiOpened() {
    return librarianGuiOpened;
  }

  void markLibrarianGuiOpened() {
    librarianGuiOpened = true;
  }

  boolean wereTradingOptionsOpened() {
    return tradingOptionsOpened;
  }

  void markTradingOptionsOpened() {
    tradingOptionsOpened = true;
  }

  void clearLibrarianScreens() {
    librarianGuiOpened = false;
    tradingOptionsOpened = false;
  }

  void reset() {
    stickClick.clear();
    expBottleOptions.clear();
    expBottleStackClick.clear();
    lumberGuiOpened = false;
    librarianGuiOpened = false;
    tradingOptionsOpened = false;
    expBottleSwapRequested = false;
    expBottleBaseline = NO_BASELINE;
    tradingOptionsReopens = 0;
  }
}
