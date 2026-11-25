package ryzn.macro.client.feature.macros.impl.tree;

import java.util.Locale;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.util.inventory.MacroSlots;
import ryzn.macro.client.util.text.RyznChat;

final class TreeToolSelector {

  private boolean warnedNoTool;

  boolean equip(LocalPlayer player) {
    Inventory inventory = player.getInventory();
    String keyword =
        RyznBootstrap.macroManager()
            .getByClass(TreeChopMacro.class)
            .chopToolKeyword()
            .toLowerCase(Locale.ROOT);
    // The settled case, checked first: this now runs every tick a tree is being worked, and walking
    // the
    // hotbar to re-decide something that has not changed is pure per-tick cost. Only the KEYWORD
    // match
    // short-circuits - a plain axe is the fallback, so holding one must not stop the scan finding
    // the
    // configured tool sitting in another slot.
    final int selected = inventory.getSelectedSlot();
    if (MacroSlots.isUsableHotbarSlot(selected)
        && matchesKeyword(inventory.getItem(selected), keyword)) {
      warnedNoTool = false;
      return true;
    }
    int axeSlot = -1;
    for (int slot = 0; slot < MacroSlots.RESERVED_HOTBAR_SLOT; slot++) {
      ItemStack stack = inventory.getItem(slot);
      if (stack.isEmpty()) {
        continue;
      }
      if (matchesKeyword(stack, keyword)) {
        select(player, slot);
        warnedNoTool = false;
        return true;
      }
      if (axeSlot < 0 && isAxe(stack)) {
        axeSlot = slot;
      }
    }
    if (axeSlot >= 0) {
      select(player, axeSlot);
      warnedNoTool = false;
      return true;
    }
    if (!warnedNoTool) {
      RyznChat.send(
          "§bTree Chop§7: §eno axe in your hotbar. §7Put one in and it carries on by itself.");
      warnedNoTool = true;
    }
    return false;
  }

  void resetWarning() {
    warnedNoTool = false;
  }

  private static void select(LocalPlayer player, int slot) {
    MacroInput.selectHotbarSlot(player, slot);
  }

  private static boolean matchesKeyword(ItemStack stack, String keyword) {
    return !stack.isEmpty()
        && stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(keyword);
  }

  private static boolean isAxe(ItemStack stack) {
    return stack.is(Items.WOODEN_AXE)
        || stack.is(Items.STONE_AXE)
        || stack.is(Items.IRON_AXE)
        || stack.is(Items.GOLDEN_AXE)
        || stack.is(Items.DIAMOND_AXE)
        || stack.is(Items.NETHERITE_AXE);
  }
}
