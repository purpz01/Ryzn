package ryzn.macro.client.feature.macros.impl.foragingquest;

import ryzn.macro.client.input.ClickConfirm;

/**
 * Where the log-buying loop is up to. Batches bought are DERIVED from what the inventory actually
 * gained since this purchase started, never from how many clicks were sent - Hypixel drops clicks
 * aimed at a menu page it is still swapping, so a click-counted batch can be one that never
 * happened. Which page is open is not tracked here at all: the menu title is the truth, and a
 * second copy of it only ever desyncs.
 */
final class ForagingLogPurchaseProgress {

  static final int BATCH_SIZE = 64;

  private static final int UNSET = -1;

  private final ClickConfirm delivery = new ClickConfirm();

  private int purchaseIndex;
  private int baselineCount = UNSET;

  int purchaseIndex() {
    return purchaseIndex;
  }

  boolean isComplete(int purchaseCount) {
    return purchaseIndex >= purchaseCount;
  }

  /** Remember what the inventory already held before this purchase. Ignored once set. */
  void captureBaseline(int ownedCount) {
    if (baselineCount == UNSET) {
      baselineCount = ownedCount;
    }
  }

  /** Full batches delivered for this purchase so far. */
  int batchesFrom(int ownedCount) {
    if (baselineCount == UNSET) {
      return 0;
    }
    return Math.max(0, (ownedCount - baselineCount) / BATCH_SIZE);
  }

  void markPurchaseSent(int ownedCount, int ceilingTicks) {
    delivery.sent(ownedCount, ceilingTicks);
  }

  /**
   * True while a sent purchase has not reached the inventory yet - holds off paying twice for one
   * batch.
   */
  boolean awaitDelivery(int ownedCount) {
    return delivery.awaiting(ownedCount);
  }

  void advancePurchase() {
    purchaseIndex++;
    baselineCount = UNSET;
    delivery.clear();
  }

  void reset() {
    purchaseIndex = 0;
    baselineCount = UNSET;
    delivery.clear();
  }
}
