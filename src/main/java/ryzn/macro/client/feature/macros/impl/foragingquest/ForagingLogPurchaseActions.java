package ryzn.macro.client.feature.macros.impl.foragingquest;

import static ryzn.macro.client.util.inventory.MacroSlots.clickSlot;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import ryzn.macro.client.feature.macros.impl.tree.TreeLogType;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.input.MacroActionTiming;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class ForagingLogPurchaseActions {

  enum Result {
    WORKING,
    COMPLETE,
    FAILED
  }

  record Outcome(Result result, String message, boolean screenOpened) {

    static Outcome working() {
      return new Outcome(Result.WORKING, null, true);
    }

    static Outcome status(String message) {
      return new Outcome(Result.WORKING, message, true);
    }

    static Outcome complete() {
      return new Outcome(Result.COMPLETE, null, true);
    }

    static Outcome failed(String message) {
      return new Outcome(Result.FAILED, message, false);
    }
  }

  private record Purchase(TreeLogType type, Item item, int batches, String label) {}

  private static final Purchase[] purchases = {
    new Purchase(TreeLogType.OAK, Items.OAK_LOG, 1, "Oak Logs"),
    new Purchase(TreeLogType.BIRCH, Items.BIRCH_LOG, 1, "Birch Logs"),
    new Purchase(TreeLogType.SPRUCE, Items.SPRUCE_LOG, 2, "Spruce Logs"),
    new Purchase(TreeLogType.DARK_OAK, Items.DARK_OAK_LOG, 4, "Dark Oak Logs"),
    new Purchase(TreeLogType.ACACIA, Items.ACACIA_LOG, 8, "Acacia Logs"),
    new Purchase(TreeLogType.JUNGLE, Items.JUNGLE_LOG, 8, "Jungle Logs")
  };

  /** How long a sent purchase may take to land in the inventory before the buy click is retried. */
  private static final int DELIVERY_CEILING_TICKS = 40;

  private final ForagingLogPurchaseProgress progress;
  private final ForagingMenuSlots menuSlots;
  private final ForagingInventoryItems inventoryItems;
  private final MacroActionTiming actionTiming;

  /**
   * One step of the buy loop, driven entirely by two things the server tells us: which page is open
   * (the menu title) and how many logs we hold. Nothing is assumed to have worked because a click
   * was sent - a click Hypixel dropped mid page-swap leaves both unchanged, so the same step simply
   * runs again after the delay instead of stranding the loop on a page it never opened.
   */
  Outcome tick(Minecraft mc, LocalPlayer player, boolean timedOut) {
    if (timedOut) {
      return Outcome.failed("Timed out buying logs");
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)) {
      return Outcome.failed("Lumber Merchant GUI closed");
    }
    if (progress.isComplete(purchases.length)) {
      return Outcome.complete();
    }
    if (actionTiming.waitForMenuClick()) {
      return Outcome.working();
    }
    if (mc.gameMode == null) {
      return Outcome.failed("Interaction manager missing");
    }

    Purchase purchase = purchases[progress.purchaseIndex()];
    AbstractContainerMenu menu = handled.getMenu();
    int owned = inventoryItems.countLogs(player, purchase.type());
    progress.captureBaseline(owned);
    int batchesDone = progress.batchesFrom(owned);
    boolean onOptions = menuSlots.isTradingOptionsScreen(mc);

    if (batchesDone >= purchase.batches()) {
      if (!onOptions) {
        progress.advancePurchase();
        return Outcome.status(
            progress.isComplete(purchases.length) ? "Finished log purchases" : "Next log purchase");
      }
      Slot back = menuSlots.backSlot(menu, player);
      if (back == null) {
        return Outcome.status("Searching for Back");
      }
      clickSlot(mc, menu, back.index, 0, ContainerInput.PICKUP, player);
      armClickDelay();
      return Outcome.working();
    }

    if (!onOptions) {
      Slot entry = menuSlots.logEntry(menu, player, purchase.type(), purchase.item());
      if (entry == null) {
        return Outcome.status("Searching for " + purchase.label());
      }
      clickSlot(mc, menu, entry.index, 1, ContainerInput.PICKUP, player);
      armClickDelay();
      return Outcome.status("Opening " + purchase.label() + " x64");
    }

    if (progress.awaitDelivery(owned)) {
      return Outcome.status(
          "Waiting for " + purchase.label() + " (" + batchesDone + "/" + purchase.batches() + ")");
    }
    Slot option = menuSlots.logX64Option(menu, player, purchase.type(), purchase.item());
    if (option == null) {
      return Outcome.status("Searching x64 " + purchase.label());
    }
    clickSlot(mc, menu, option.index, 0, ContainerInput.PICKUP, player);
    progress.markPurchaseSent(owned, DELIVERY_CEILING_TICKS);
    armClickDelay();
    return Outcome.status(
        "Buying " + purchase.label() + " x64 (" + batchesDone + "/" + purchase.batches() + ")");
  }

  private void armClickDelay() {
    actionTiming.armMenuClick(GuiActionTiming.randomDelay());
  }
}
