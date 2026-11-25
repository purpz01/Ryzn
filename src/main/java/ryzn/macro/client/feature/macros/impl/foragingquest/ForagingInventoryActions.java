package ryzn.macro.client.feature.macros.impl.foragingquest;

import static ryzn.macro.client.MacroInput.selectHotbarSlot;
import static ryzn.macro.client.MacroInput.setUseHeld;
import static ryzn.macro.client.util.gui.MacroScreens.closeCurrentScreen;
import static ryzn.macro.client.util.inventory.MacroSlots.clickSlot;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import ryzn.macro.client.input.ClickConfirm;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.input.MacroActionTiming;
import ryzn.macro.client.util.inventory.MacroSlots;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class ForagingInventoryActions {

  enum Result {
    WORKING,
    COMPLETE,
    FAILED
  }

  record Outcome(Result result, String message, boolean screenOpened) {

    static Outcome working() {
      return new Outcome(Result.WORKING, null, false);
    }

    static Outcome status(String message) {
      return new Outcome(Result.WORKING, message, false);
    }

    static Outcome screenOpened(String message) {
      return new Outcome(Result.WORKING, message, true);
    }

    static Outcome complete() {
      return new Outcome(Result.COMPLETE, null, false);
    }

    static Outcome completeWithOwnedScreen() {
      return new Outcome(Result.COMPLETE, null, true);
    }

    static Outcome failed(String message) {
      return new Outcome(Result.FAILED, message, false);
    }

    Outcome withOwnedScreen() {
      return screenOpened ? this : new Outcome(result, message, true);
    }
  }

  /**
   * How long a claimed item may take to reach the inventory before the click is treated as lost.
   */
  private static final int PICKUP_CEILING_TICKS = 30;

  private final ForagingMenuSlots menuSlots;
  private final ForagingInventoryItems inventoryItems;
  private final MacroActionTiming actionTiming;
  private final ClickConfirm pickup = new ClickConfirm();
  private boolean clicked;
  private boolean used;

  /**
   * Take a named item out of a menu. Done means the item is in the inventory, not that a click was
   * sent - a click Hypixel dropped is re-sent while its slot is still on the page instead of
   * closing the GUI on an item we never got. Past the ceiling, a click that produced no item but
   * whose slot is gone is still taken as done, so a reward that lands somewhere other than the
   * inventory cannot hang the macro.
   */
  Outcome clickGuiItem(
      Minecraft mc, LocalPlayer player, String itemName, boolean closeAfter, boolean timedOut) {
    if (timedOut) {
      return Outcome.failed(itemName + " click timed out");
    }
    boolean owned = inventoryItems.hasItem(player, itemName);
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)) {
      return owned || clicked
          ? Outcome.completeWithOwnedScreen()
          : Outcome.failed("GUI closed before " + itemName);
    }
    if (actionTiming.waitForMenuClick()) {
      return Outcome.working().withOwnedScreen();
    }
    if (owned) {
      if (closeAfter) {
        closeCurrentScreen(mc);
      }
      if (closeAfter && mc.gui.screen() != null) {
        return Outcome.working().withOwnedScreen();
      }
      return Outcome.completeWithOwnedScreen();
    }
    if (pickup.awaiting(0)) {
      return Outcome.status("Waiting for " + itemName).withOwnedScreen();
    }
    Slot slot = menuSlots.containerSlotByName(handled.getMenu(), player, itemName);
    if (slot == null) {
      if (!clicked) {
        return Outcome.status("Searching for " + itemName).withOwnedScreen();
      }
      if (closeAfter) {
        closeCurrentScreen(mc);
      }
      return Outcome.completeWithOwnedScreen();
    }
    clickSlot(mc, handled.getMenu(), slot.index, 0, ContainerInput.PICKUP, player);
    clicked = true;
    pickup.sent(0, PICKUP_CEILING_TICKS);
    armClickDelay();
    return Outcome.status("Clicked " + itemName).withOwnedScreen();
  }

  /**
   * Claim the reward item in the middle of a popup (e.g. the Lumber Jack axe) and let the menu
   * close itself; if it lingers, close it. Matches the reward by position, not name, so the exact
   * axe name does not matter - which also means the claim can only be confirmed by the popup
   * reacting (closing itself or dropping the item off the page), never by an inventory lookup.
   * Force-closing is the ceiling fallback, not step one.
   */
  Outcome claimReward(Minecraft mc, LocalPlayer player, boolean timedOut) {
    if (timedOut) {
      return Outcome.failed("Reward claim timed out");
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)) {
      return clicked ? Outcome.complete() : Outcome.failed("Reward GUI closed before claim");
    }
    if (actionTiming.waitForMenuClick()) {
      return Outcome.working().withOwnedScreen();
    }
    Slot slot = menuSlots.claimableRewardSlot(handled.getMenu(), player);
    if (clicked) {
      if (pickup.awaiting(slot == null ? 1 : 0)) {
        return Outcome.status("Waiting for reward").withOwnedScreen();
      }
      closeCurrentScreen(mc);
      return mc.gui.screen() == null
          ? Outcome.completeWithOwnedScreen()
          : Outcome.working().withOwnedScreen();
    }
    if (slot == null) {
      return Outcome.status("Finding reward item").withOwnedScreen();
    }
    clickSlot(mc, handled.getMenu(), slot.index, 0, ContainerInput.PICKUP, player);
    clicked = true;
    pickup.sent(0, PICKUP_CEILING_TICKS);
    armClickDelay();
    return Outcome.status("Claiming reward").withOwnedScreen();
  }

  Outcome useInventoryItem(Minecraft mc, LocalPlayer player, String itemNeedle, boolean timedOut) {
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return Outcome.working();
    }
    if (timedOut) {
      return Outcome.failed("Timed out using " + itemNeedle);
    }
    if (!equipHandItem(mc, player, itemNeedle)) {
      // Hypixel eats a consumable the tick the use lands, so once use has been pressed the item
      // GOING MISSING is the confirmation it worked - not a reason to keep hunting for it until
      // the state times out (which restarted the whole NPC step on an already-used travel scroll).
      return used ? Outcome.complete() : Outcome.status("Finding " + itemNeedle);
    }
    if (actionTiming.advanceUseHold(5)) {
      if (mc.options != null) {
        setUseHeld(mc.options, true);
        used = true;
      }
      return Outcome.status("Using " + itemNeedle);
    }
    return Outcome.complete();
  }

  boolean equipEmptyHand(LocalPlayer player) {
    int slot = inventoryItems.emptyHotbarSlot(player);
    if (slot < 0) {
      return false;
    }
    selectHotbarSlot(player, slot);
    return true;
  }

  /**
   * A right-click the NPC does not consume falls through to using the held item, and on a claimed
   * armour reward that wears it. Selects a hotbar slot without armour; true when it switched.
   */
  boolean moveHandOffArmor(LocalPlayer player) {
    final Inventory inventory = player.getInventory();
    if (!isArmor(inventory.getSelectedItem())) {
      return false;
    }
    int slot = inventoryItems.emptyHotbarSlot(player);
    for (int i = 0; slot < 0 && i < MacroSlots.RESERVED_HOTBAR_SLOT; i++) {
      if (!isArmor(inventory.getItem(i))) {
        slot = i;
      }
    }
    if (slot < 0) {
      return false;
    }
    selectHotbarSlot(player, slot);
    return true;
  }

  private static boolean isArmor(ItemStack stack) {
    final Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
    return equippable != null && equippable.slot().isArmor();
  }

  boolean equipHandItem(Minecraft mc, LocalPlayer player, String itemNeedle) {
    int hotbarSlot = inventoryItems.matchingHotbarSlot(player, itemNeedle);
    if (hotbarSlot >= 0) {
      selectHotbarSlot(player, hotbarSlot);
      return true;
    }
    int inventorySlot = inventoryItems.matchingInventorySlot(player, itemNeedle);
    if (inventorySlot < 0) {
      return false;
    }
    swapInventoryToHotbar(mc, player, inventorySlot, inventoryItems.spareHotbarSlot(player));
    return false;
  }

  void resetClick() {
    clicked = false;
    used = false;
    pickup.clear();
  }

  private boolean swapInventoryToHotbar(
      Minecraft mc, LocalPlayer player, int inventoryIndex, int hotbarIndex) {
    if (!MacroSlots.swapInventoryToHotbar(mc, player, inventoryIndex, hotbarIndex)) {
      return false;
    }
    armClickDelay();
    return true;
  }

  private void armClickDelay() {
    actionTiming.armMenuClick(GuiActionTiming.randomDelay());
  }
}
