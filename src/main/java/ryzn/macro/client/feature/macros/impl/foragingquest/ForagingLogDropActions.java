package ryzn.macro.client.feature.macros.impl.foragingquest;

import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.feature.macros.impl.tree.TreeLogType;
import ryzn.macro.client.feature.rotation.api.LookController;
import ryzn.macro.client.util.inventory.InventoryHotbarTransfer;
import ryzn.macro.client.util.inventory.MacroSlots;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class ForagingLogDropActions {

  enum Result {
    WORKING,
    COMPLETE,
    FAILED
  }

  record Outcome(Result result, String message, boolean screenOpened) {}

  private static final int PICKUP_TIMEOUT_TICKS = 20 * 8;

  private final ForagingInventoryItems items;
  private final Supplier<LookController> look;
  private int hotbarSlot = -1;
  private int countBeforeDrop;
  private int dropTicks;
  private boolean dropped;
  private boolean sawCountDecrease;

  Outcome tick(Minecraft mc, LocalPlayer player, TreeLogType type) {
    if (InventoryHotbarTransfer.isBusy()) {
      return working("Moving " + type.displayName() + " log to hotbar", true);
    }
    if (mc.gui.screen() != null) {
      return new Outcome(Result.FAILED, "Unexpected screen while preparing log drop", false);
    }
    if (dropped) {
      dropTicks++;
      final int currentCount = items.countLogs(player, type);
      if (currentCount < countBeforeDrop) {
        sawCountDecrease = true;
      }
      if (pickupComplete(countBeforeDrop, currentCount, sawCountDecrease)) {
        return new Outcome(Result.COMPLETE, null, false);
      }
      return new Outcome(
          dropTicks >= PICKUP_TIMEOUT_TICKS ? Result.FAILED : Result.WORKING,
          dropTicks >= PICKUP_TIMEOUT_TICKS
              ? "Timed out picking up " + type.displayName() + " log"
              : "Waiting to pick up " + type.displayName() + " log",
          false);
    }

    if (hotbarSlot < 0) {
      hotbarSlot = items.matchingHotbarSlot(player, type.displayName() + " log");
      if (hotbarSlot >= 0) {
        MacroInput.hotbarKeyClick(mc, hotbarSlot);
        return working("Selecting " + type.displayName() + " log", false);
      }
      int inventorySlot = items.matchingInventorySlot(player, type.displayName() + " log");
      if (inventorySlot < 0) {
        return new Outcome(Result.FAILED, "No " + type.displayName() + " log in inventory", false);
      }
      hotbarSlot = items.spareHotbarSlot(player);
      if (!MacroSlots.swapInventoryToHotbar(mc, player, inventorySlot, hotbarSlot)) {
        return new Outcome(
            Result.FAILED, "Could not move " + type.displayName() + " log to hotbar", true);
      }
      return working("Moving " + type.displayName() + " log to hotbar", true);
    }

    if (player.getInventory().getSelectedSlot() != hotbarSlot) {
      MacroInput.hotbarKeyClick(mc, hotbarSlot);
      return working("Selecting " + type.displayName() + " log", false);
    }
    if (!type.matchesItem(player.getMainHandItem())
        && !items.matchesLogSearch(
            type,
            ryzn.macro.client.util.inventory.MacroItems.stackSearchText(
                player.getMainHandItem()))) {
      return new Outcome(
          Result.FAILED, "Wrong item selected instead of " + type.displayName() + " log", false);
    }
    if (!look.get().aimDown(player)) {
      return working("Looking down", false);
    }

    countBeforeDrop = items.countLogs(player, type);
    MacroInput.dropItemKeyClick(mc);
    dropped = true;
    return working("Dropping " + type.displayName() + " log", false);
  }

  void reset() {
    hotbarSlot = -1;
    countBeforeDrop = 0;
    dropTicks = 0;
    dropped = false;
    sawCountDecrease = false;
  }

  static boolean pickupComplete(int countBeforeDrop, int currentCount, boolean sawCountDecrease) {
    return sawCountDecrease && currentCount >= countBeforeDrop;
  }

  private static Outcome working(String message, boolean screenOpened) {
    return new Outcome(Result.WORKING, message, screenOpened);
  }
}
