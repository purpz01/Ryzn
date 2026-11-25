package ryzn.macro.client.feature.macros.impl.foragingquest;

import static ryzn.macro.client.util.inventory.MacroItems.isAxe;
import static ryzn.macro.client.util.inventory.MacroItems.stackSearchText;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import ryzn.macro.client.feature.macros.impl.tree.TreeLogType;
import ryzn.macro.client.util.inventory.MacroSlots;

final class ForagingInventoryItems {
  /**
   * Boundary between hotbar and backpack, NOT a slot limit - see MacroSlots.RESERVED_HOTBAR_SLOT.
   */
  private static final int HOTBAR_SIZE = 9;

  private static final int INVENTORY_SIZE = 36;

  /**
   * Slot 9 is never reported, so nothing can select into it. See {@link
   * MacroSlots#RESERVED_HOTBAR_SLOT}.
   */
  int matchingHotbarSlot(LocalPlayer player, String itemNeedle) {
    final Inventory inventory = player.getInventory();
    final int selected = inventory.getSelectedSlot();
    if (MacroSlots.isUsableHotbarSlot(selected)
        && matchesItem(inventory.getItem(selected), itemNeedle)) {
      return selected;
    }
    for (int slot = 0; slot < MacroSlots.RESERVED_HOTBAR_SLOT; slot++) {
      if (matchesItem(inventory.getItem(slot), itemNeedle)) {
        return slot;
      }
    }
    return -1;
  }

  int emptyHotbarSlot(LocalPlayer player) {
    final Inventory inventory = player.getInventory();
    final int selected = inventory.getSelectedSlot();
    if (MacroSlots.isUsableHotbarSlot(selected) && inventory.getItem(selected).isEmpty()) {
      return selected;
    }
    for (int slot = 0; slot < MacroSlots.RESERVED_HOTBAR_SLOT; slot++) {
      if (inventory.getItem(slot).isEmpty()) {
        return slot;
      }
    }
    return -1;
  }

  /**
   * Where to park an item pulled out of the backpack. Empty slot first; otherwise the last ALLOWED
   * hotbar slot that is not an axe - the hotbar is usually full (bought logs), and displacing the
   * chopping axe left the macro swinging at trees with a stack of wood.
   *
   * <p>The scan starts one slot in from the end on purpose. It used to start at {@code HOTBAR_SIZE
   * - 1}, so with a full hotbar it always picked the 9th slot - the one thing no macro may touch.
   * Returns -1 when there is nowhere legal, and the caller takes its own not-found path.
   */
  int spareHotbarSlot(LocalPlayer player) {
    final int empty = emptyHotbarSlot(player);
    if (empty >= 0) {
      return empty;
    }
    final Inventory inventory = player.getInventory();
    for (int slot = MacroSlots.RESERVED_HOTBAR_SLOT - 1; slot >= 0; slot--) {
      if (!isAxe(inventory.getItem(slot))) {
        return slot;
      }
    }
    final int selected = inventory.getSelectedSlot();
    return MacroSlots.isUsableHotbarSlot(selected) ? selected : -1;
  }

  int matchingInventorySlot(LocalPlayer player, String itemNeedle) {
    final Inventory inventory = player.getInventory();
    for (int slot = HOTBAR_SIZE; slot < INVENTORY_SIZE; slot++) {
      if (matchesItem(inventory.getItem(slot), itemNeedle)) {
        return slot;
      }
    }
    return -1;
  }

  boolean hasItem(LocalPlayer player, String itemNeedle) {
    return matchingHotbarSlot(player, itemNeedle) >= 0
        || matchingInventorySlot(player, itemNeedle) >= 0;
  }

  int countLogs(LocalPlayer player, TreeLogType type) {
    int count = 0;
    final Inventory inventory = player.getInventory();
    for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
      final ItemStack stack = inventory.getItem(slot);
      if (type.matchesItem(stack) || matchesLogSearch(type, stackSearchText(stack))) {
        count += stack.getCount();
      }
    }
    return count;
  }

  boolean matchesLogSearch(TreeLogType type, String searchText) {
    return normalizeSearchText(searchText)
        .contains(normalizeSearchText(type.displayName()) + " log");
  }

  private boolean matchesItem(ItemStack stack, String itemNeedle) {
    return normalizeSearchText(stackSearchText(stack)).contains(normalizeSearchText(itemNeedle));
  }
}
