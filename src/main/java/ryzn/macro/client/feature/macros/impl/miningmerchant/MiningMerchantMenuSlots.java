package ryzn.macro.client.feature.macros.impl.miningmerchant;

import static ryzn.macro.client.util.gui.MacroMenu.bestNonPlayerSlot;
import static ryzn.macro.client.util.gui.MacroScreens.cleanTitle;
import static ryzn.macro.client.util.inventory.MacroItems.stackSearchText;
import static ryzn.macro.client.util.text.MacroText.containsStackAmount;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ryzn.macro.client.util.gui.MacroMenu;

/** Slot + screen recognition for the Mining Merchant shop: buying gold ingots. */
final class MiningMerchantMenuSlots {
  private static final int INVENTORY_SIZE = 36;

  /** Score floor, so an offer that fits always outranks one that overshoots. */
  private static final int OFFER_BASE = 1000;

  boolean isPurchaseScreen(Minecraft minecraft, boolean merchantGuiOpened) {
    if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?>)) {
      return false;
    }
    final String lower = cleanTitle(minecraft).toLowerCase(Locale.ROOT);
    return lower.contains("mining merchant")
        || lower.contains("mine merchant")
        || lower.contains("shop")
        || lower.contains("trading options")
        || merchantGuiOpened;
  }

  /**
   * The slot to click for a material: the largest offer that still fits in what is left to buy,
   * else the smallest overshoot. Only the real item is ever a candidate - the shop sells Torch x32,
   * and matching "a stack of 32" bought those every time the material's own x32 option was off
   * screen.
   */
  Slot buyOfferSlot(AbstractContainerMenu menu, LocalPlayer player, int remaining) {
    return bestNonPlayerSlot(
        menu,
        player,
        slot -> {
          final ItemStack stack = slot.getItem();
          if (stack.isEmpty() || !stack.is(Items.GOLD_INGOT)) {
            return MacroMenu.SKIP;
          }
          return offerScore(offerAmount(stack), remaining);
        });
  }

  int offerScore(int amount, int remaining) {
    return amount <= remaining ? OFFER_BASE + amount : OFFER_BASE - amount;
  }

  int playerGoldCount(LocalPlayer player) {
    final Inventory inventory = player.getInventory();
    int total = 0;
    for (int index = 0; index < INVENTORY_SIZE; index++) {
      final ItemStack stack = inventory.getItem(index);
      if (stack.is(Items.GOLD_INGOT)) {
        total += stack.getCount();
      }
    }
    return total;
  }

  /** What one click hands over: the offer stack itself, or the amount its lore advertises. */
  private int offerAmount(ItemStack stack) {
    final String text = normalizeSearchText(stackSearchText(stack));
    if (containsStackAmount(text, 64)) {
      return 64;
    }
    if (containsStackAmount(text, 32)) {
      return 32;
    }
    return Math.max(1, stack.getCount());
  }
}
