package ryzn.macro.client.feature.macros.impl.wheat;

import static ryzn.macro.client.MacroInput.selectHotbarSlot;
import static ryzn.macro.client.util.inventory.MacroItems.isMenuDecorationStack;
import static ryzn.macro.client.util.inventory.MacroItems.stackName;
import static ryzn.macro.client.util.inventory.MacroItems.stackSearchText;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ryzn.macro.client.util.inventory.MacroSlots;
import ryzn.macro.client.util.inventory.VanillaTools;

final class WheatInventory {

  private static final int PLAYER_INVENTORY_SIZE = 36;

  boolean isHoldingHoe(LocalPlayer player) {
    return player != null && isHoe(player.getMainHandItem());
  }

  boolean hasHoe(LocalPlayer player) {
    return MacroSlots.findAnyInventorySlot(player, this::isHoe) >= 0;
  }

  boolean tryEquipHoe(Minecraft mc, LocalPlayer player) {
    if (player == null) {
      return false;
    }
    Inventory inventory = player.getInventory();
    int selected = inventory.getSelectedSlot();
    if (isHoe(inventory.getItem(selected))) {
      return true;
    }
    int hotbarSlot = MacroSlots.findHotbarSlot(player, this::isHoe);
    if (hotbarSlot >= 0) {
      selectHotbarSlot(player, hotbarSlot);
      return true;
    }
    int inventorySlot = MacroSlots.findMainInventorySlot(player, this::isHoe);
    if (inventorySlot < 0) {
      return false;
    }
    int destination = MacroSlots.findFirstEmptyHotbarSlot(player);
    if (destination < 0) {
      destination = selected;
    }
    MacroSlots.swapInventoryToHotbar(mc, player, inventorySlot, destination);
    return false;
  }

  boolean isFull(LocalPlayer player) {
    if (player == null) {
      return false;
    }
    Inventory inventory = player.getInventory();
    for (int i = 0; i < PLAYER_INVENTORY_SIZE; i++) {
      if (inventory.getItem(i).isEmpty()) {
        return false;
      }
    }
    return true;
  }

  boolean hasSellableCrops(LocalPlayer player) {
    if (player == null) {
      return false;
    }
    Inventory inventory = player.getInventory();
    for (int i = 0; i < PLAYER_INVENTORY_SIZE; i++) {
      if (isSellable(inventory.getItem(i))) {
        return true;
      }
    }
    return false;
  }

  boolean isSellable(ItemStack stack) {
    if (stack == null || stack.isEmpty()) {
      return false;
    }
    if (stack.is(Items.WHEAT) || stack.is(Items.WHEAT_SEEDS)) {
      return true;
    }
    return isSellableName(stack.getHoverName().getString());
  }

  boolean isSellableName(String itemName) {
    String name = normalizeSearchText(itemName);
    return name.equals("wheat")
        || name.equals("seeds")
        || name.equals("wheat seeds")
        || name.contains("wheat seeds");
  }

  Slot nextSellableSlot(AbstractContainerMenu menu, LocalPlayer player) {
    if (menu == null) {
      return null;
    }
    for (Slot slot : menu.slots) {
      if (slot == null || !isPlayerSlot(menu, player, slot)) {
        continue;
      }
      if (isSellable(slot.getItem())) {
        return slot;
      }
    }
    return null;
  }

  /**
   * The shop's hoe, found by what it IS rather than where it sits or what it is called. Hypixel
   * reshuffles and renames merchant offers, and a literal "rookie hoe" text lookup breaks every
   * time it does; the named offer still wins when present, any hoe in the shop grid is the
   * fallback.
   */
  Slot containerHoeSlot(AbstractContainerMenu menu, LocalPlayer player) {
    if (menu == null) {
      return null;
    }
    Slot anyHoe = null;
    for (Slot slot : menu.slots) {
      if (slot == null || isPlayerSlot(menu, player, slot)) {
        continue;
      }
      ItemStack stack = slot.getItem();
      if (stack == null || stack.isEmpty()) {
        continue;
      }
      if (normalizeSearchText(stackSearchText(stack)).contains("rookie hoe")) {
        return slot;
      }
      if (anyHoe == null && isHoeOffer(stack)) {
        anyHoe = slot;
      }
    }
    return anyHoe;
  }

  /** Shop offers as plain names - only for the debug line when no hoe was found. */
  String containerOffers(AbstractContainerMenu menu, LocalPlayer player) {
    if (menu == null) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    for (Slot slot : menu.slots) {
      if (slot == null || isPlayerSlot(menu, player, slot)) {
        continue;
      }
      ItemStack stack = slot.getItem();
      if (stack == null || stack.isEmpty() || isMenuDecorationStack(stack)) {
        continue;
      }
      text.append(slot.index).append('=').append(stackName(stack)).append(' ');
    }
    return text.toString();
  }

  /**
   * Shop-grid variant of {@link #isHoe}: name only, never lore - a crop's lore may mention a hoe.
   */
  private boolean isHoeOffer(ItemStack stack) {
    return VanillaTools.isHoe(stack) || normalizeSearchText(stackName(stack)).contains("hoe");
  }

  private boolean isHoe(ItemStack stack) {
    if (stack == null || stack.isEmpty()) {
      return false;
    }
    if (VanillaTools.isHoe(stack)) {
      return true;
    }
    return normalizeSearchText(stackSearchText(stack)).contains("hoe");
  }

  private boolean isPlayerSlot(AbstractContainerMenu menu, LocalPlayer player, Slot slot) {
    if (slot == null || player == null) {
      return false;
    }
    if (slot.container == player.getInventory()) {
      return true;
    }
    int playerStart = Math.max(0, menu.slots.size() - PLAYER_INVENTORY_SIZE);
    return slot.index >= playerStart;
  }
}
