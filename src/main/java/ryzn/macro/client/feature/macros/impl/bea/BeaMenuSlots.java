package ryzn.macro.client.feature.macros.impl.bea;

import static ryzn.macro.client.util.inventory.MacroItems.isMenuDecorationStack;
import static ryzn.macro.client.util.inventory.MacroItems.stackLoreText;
import static ryzn.macro.client.util.inventory.MacroItems.stackName;
import static ryzn.macro.client.util.inventory.MacroItems.stackSearchText;
import static ryzn.macro.client.util.inventory.MacroSlots.isPlayerInventorySlot;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import ryzn.macro.client.util.gui.MacroMenu;

final class BeaMenuSlots {
  private static final int HOTBAR_SIZE = 9;
  private static final int INVENTORY_SIZE = 36;

  int countPlayerMaterial(AbstractContainerMenu menu, LocalPlayer player, Item item) {
    int count = 0;
    for (Slot slot : menu.slots) {
      if (slot == null || !isPlayerInventorySlot(menu, player, slot)) {
        continue;
      }
      if (slot.getItem().is(item)) {
        count += slot.getItem().getCount();
      }
    }
    return count;
  }

  /**
   * Bea's Bee pet offer. The exact lore match wins when it is there, but it demands five separate
   * fragments and Hypixel only has to reword one of them to make the whole trade invisible - which
   * reads as the macro "searching" until it times out. So a ranked fallback takes the best
   * bee-named offer that is still plainly purchasable, and {@link #tradeOffers} dumps what was on
   * the page when neither hits.
   */
  Slot commonBeeTradeSlot(AbstractContainerMenu menu, LocalPlayer player) {
    if (menu == null) {
      return null;
    }
    Slot exact = null;
    Slot best = null;
    int bestScore = 0;
    for (Slot slot : menu.slots) {
      if (slot == null || isPlayerInventorySlot(menu, player, slot) || slot.getItem().isEmpty()) {
        continue;
      }
      final ItemStack stack = slot.getItem();
      if (isMenuDecorationStack(stack)) {
        continue;
      }
      final String name = stackName(stack);
      final String lore = stackLoreText(stack);
      if (isCommonBeeTrade(name, lore)) {
        if (exact == null) {
          exact = slot;
        }
        continue;
      }
      final int score = beeTradeScore(name, lore);
      if (score > bestScore) {
        best = slot;
        bestScore = score;
      }
    }
    return exact != null ? exact : best;
  }

  /**
   * How much a slot looks like the Bee trade when the exact lore no longer matches. Zero
   * disqualifies: the name must mention a bee AND the lore must still show it as something you can
   * buy, so a decorative bee-themed filler or an already-owned entry can never be clicked.
   */
  int beeTradeScore(String name, String lore) {
    final String normalizedName = normalizeSearchText(name);
    final String normalizedLore = normalizeSearchText(lore);
    if (!normalizedName.contains("bee")) {
      return 0;
    }
    if (!normalizedLore.contains("click to trade") && !normalizedLore.contains("cost")) {
      return 0;
    }
    int score = 1;
    if (normalizedLore.contains("farming pet")) {
      score += 8;
    }
    if (normalizedLore.contains("coins")) {
      score += 4;
    }
    if (normalizedName.contains("common")) {
      score += 2;
    }
    return score;
  }

  /**
   * {@code slot=name} for every offer on the page, so a failed lookup says what it actually saw.
   */
  String tradeOffers(AbstractContainerMenu menu, LocalPlayer player) {
    if (menu == null) {
      return "no menu";
    }
    final StringBuilder out = new StringBuilder();
    for (Slot slot : menu.slots) {
      if (slot == null || isPlayerInventorySlot(menu, player, slot) || slot.getItem().isEmpty()) {
        continue;
      }
      if (out.length() > 0) {
        out.append(", ");
      }
      out.append(slot.index).append('=').append(stackName(slot.getItem()));
    }
    return out.length() == 0 ? "empty" : out.toString();
  }

  Slot beePetSlot(AbstractContainerMenu menu, LocalPlayer player) {
    if (menu == null) {
      return null;
    }
    return MacroMenu.bestNonPlayerSlot(
        menu,
        player,
        slot -> {
          final ItemStack stack = slot.getItem();
          if (stack.isEmpty() || isMenuDecorationStack(stack)) {
            return MacroMenu.SKIP;
          }
          final int score = beePetScore(stackName(stack), stackSearchText(stack));
          return score;
        });
  }

  int beePetInventoryIndex(LocalPlayer player) {
    final Inventory inventory = player.getInventory();
    final int selected = inventory.getSelectedSlot();
    if (isBeePetStack(inventory.getItem(selected))) {
      return selected;
    }
    for (int index = 0; index < HOTBAR_SIZE; index++) {
      if (isBeePetStack(inventory.getItem(index))) {
        return index;
      }
    }
    for (int index = HOTBAR_SIZE; index < INVENTORY_SIZE; index++) {
      if (isBeePetStack(inventory.getItem(index))) {
        return index;
      }
    }
    return -1;
  }

  boolean isBeePetStack(ItemStack stack) {
    return stack != null
        && !stack.isEmpty()
        && isBeePetText(stackName(stack), stackSearchText(stack));
  }

  boolean isCommonBeeTrade(String name, String lore) {
    final String normalizedName = normalizeSearchText(name);
    final String normalizedLore = normalizeSearchText(lore);
    return normalizedName.contains("bee")
        && normalizedLore.contains("farming pet")
        && normalizedLore.contains("cost")
        && normalizedLore.contains("coins")
        && normalizedLore.contains("click to trade");
  }

  int beePetScore(String name, String search) {
    final String normalizedName = normalizeSearchText(name);
    final String normalizedSearch = normalizeSearchText(search);
    if (!normalizedName.contains("bee")) {
      return Integer.MIN_VALUE;
    }
    int score = 20;
    if (normalizedSearch.contains("farming pet")) {
      score += 40;
    }
    if (normalizedSearch.contains("common")) {
      score += 6;
    }
    if (normalizedSearch.contains("summon") || normalizedSearch.contains("click")) {
      score += 4;
    }
    if (normalizedSearch.contains("pet")) {
      score += 3;
    }
    return score;
  }

  boolean isBeePetText(String name, String search) {
    return normalizeSearchText(name).contains("bee")
        && normalizeSearchText(search).contains("farming pet");
  }
}
