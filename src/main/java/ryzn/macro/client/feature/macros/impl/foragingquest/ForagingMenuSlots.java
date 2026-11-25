package ryzn.macro.client.feature.macros.impl.foragingquest;

import static ryzn.macro.client.util.gui.MacroMenu.bestNonPlayerSlot;
import static ryzn.macro.client.util.inventory.MacroItems.isMenuDecorationStack;
import static ryzn.macro.client.util.inventory.MacroItems.stackName;
import static ryzn.macro.client.util.inventory.MacroItems.stackSearchText;
import static ryzn.macro.client.util.inventory.MacroSlots.isPlayerInventorySlot;
import static ryzn.macro.client.util.text.MacroText.containsStackAmount;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import ryzn.macro.client.feature.macros.impl.tree.TreeLogType;
import ryzn.macro.client.util.gui.MacroMenu;

final class ForagingMenuSlots {

  boolean isTradingOptionsScreen(Minecraft minecraft) {
    return isTradingOptionsTitle(ryzn.macro.client.util.gui.MacroScreens.cleanTitle(minecraft));
  }

  boolean isTradingOptionsTitle(String title) {
    final String lower = title.toLowerCase(Locale.ROOT);
    return lower.contains("trading options") || lower.contains("shop trading options");
  }

  Slot logEntry(AbstractContainerMenu menu, LocalPlayer player, TreeLogType type, Item item) {
    return bestNonPlayerSlot(
        menu,
        player,
        slot -> {
          final ItemStack stack = slot.getItem();
          if (stack.isEmpty()) {
            return MacroMenu.SKIP;
          }
          final String text = normalizeSearchText(stackSearchText(stack));
          if (!matchesLog(stack, text, type, item) || isMenuDecorationStack(stack)) {
            return MacroMenu.SKIP;
          }
          int score = 10;
          if (stack.is(item)) {
            score += 40;
          }
          if (text.contains("click to trade") || text.contains("right-click")) {
            score += 20;
          }
          if (text.contains("logs")) {
            score += 5;
          }
          return score;
        });
  }

  Slot logX64Option(AbstractContainerMenu menu, LocalPlayer player, TreeLogType type, Item item) {
    return bestNonPlayerSlot(
        menu,
        player,
        slot -> {
          final ItemStack stack = slot.getItem();
          if (stack.isEmpty()) {
            return MacroMenu.SKIP;
          }
          final String text = normalizeSearchText(stackSearchText(stack));
          if (!matchesLog(stack, text, type, item)) {
            return MacroMenu.SKIP;
          }
          if (!text.contains("click to purchase") && !text.contains("purchase")) {
            return MacroMenu.SKIP;
          }
          if (stack.getCount() != 64 && !containsStackAmount(text, 64)) {
            return MacroMenu.SKIP;
          }
          int score = 10;
          if (stack.is(item)) {
            score += 40;
          }
          if (stack.getCount() == 64) {
            score += 20;
          }
          return score;
        });
  }

  Slot backSlot(AbstractContainerMenu menu, LocalPlayer player) {
    for (Slot slot : menu.slots) {
      if (slot == null || isPlayerInventorySlot(menu, player, slot)) {
        continue;
      }
      final String text = normalizeSearchText(stackSearchText(slot.getItem()));
      if (text.contains("go back") || text.equals("back") || text.contains("previous")) {
        return slot;
      }
    }
    return null;
  }

  Slot containerSlotByName(AbstractContainerMenu menu, LocalPlayer player, String itemName) {
    final String needle = normalizeSearchText(itemName);
    return bestNonPlayerSlot(
        menu,
        player,
        slot -> {
          final ItemStack stack = slot.getItem();
          if (stack.isEmpty()) {
            return MacroMenu.SKIP;
          }
          final String name = normalizeSearchText(stackName(stack));
          final String text = normalizeSearchText(stackSearchText(stack));
          if ((!name.contains(needle) && !text.contains(needle)) || isMenuDecorationStack(stack)) {
            return MacroMenu.SKIP;
          }
          int score = 10;
          if (name.contains(needle)) {
            score += 40;
          }
          if (text.contains("click")) {
            score += 5;
          }
          return score;
        });
  }

  /**
   * The centre-most claimable item in a reward popup (e.g. the Lumber Jack axe), ignoring the
   * player inventory and glass-pane decorations. Matches by position, not name, so it works
   * whatever the axe is called. Used for the "click the item in the middle to claim" reward menus
   * that then close themselves.
   */
  Slot claimableRewardSlot(AbstractContainerMenu menu, LocalPlayer player) {
    int containerCount = 0;
    for (Slot slot : menu.slots) {
      if (slot != null && !isPlayerInventorySlot(menu, player, slot)) {
        containerCount++;
      }
    }
    final double center = (containerCount - 1) / 2.0;
    Slot best = null;
    double bestDistance = Double.MAX_VALUE;
    int index = 0;
    for (Slot slot : menu.slots) {
      if (slot == null || isPlayerInventorySlot(menu, player, slot)) {
        continue;
      }
      final int here = index++;
      final ItemStack stack = slot.getItem();
      if (stack.isEmpty() || isMenuDecorationStack(stack)) {
        continue;
      }
      final double distance = Math.abs(here - center);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = slot;
      }
    }
    return best;
  }

  private boolean matchesLog(ItemStack stack, String search, TreeLogType type, Item item) {
    return stack.is(item) || search.contains(normalizeSearchText(type.displayName()) + " log");
  }
}
