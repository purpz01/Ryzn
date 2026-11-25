package ryzn.macro.client.util.inventory;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import ryzn.macro.client.util.text.MacroText;

public final class MacroItems {

  private MacroItems() {}

  public static boolean isAxe(ItemStack stack) {
    return stack != null && !stack.isEmpty() && stack.is(ItemTags.AXES);
  }

  /**
   * The SkyBlock item id from {@code ExtraAttributes}, or "" for a plain vanilla stack. This is the
   * only identity that survives reforges, stars and rename cosmetics - the vanilla item is usually
   * a player head and the display name is whatever the server felt like printing.
   */
  public static String skyblockId(ItemStack stack) {
    if (stack == null || stack.isEmpty()) {
      return "";
    }
    final CustomData data = stack.get(DataComponents.CUSTOM_DATA);
    if (data == null || data.isEmpty()) {
      return "";
    }
    final CompoundTag root = data.copyTag();
    final CompoundTag extra =
        root.getCompound("ExtraAttributes")
            .or(() -> root.getCompound("extraAttributes"))
            .orElse(root);
    return extra.getString("id").orElse("");
  }

  public static String stackName(ItemStack stack) {
    if (stack == null || stack.isEmpty()) {
      return "";
    }
    return MacroText.stripFormatting(stack.getHoverName().getString());
  }

  public static String stackLoreText(ItemStack stack) {
    if (stack == null || stack.isEmpty()) {
      return "";
    }
    final StringBuilder text = new StringBuilder();
    final ItemLore lore = stack.get(DataComponents.LORE);
    if (lore != null) {
      for (Component line : lore.lines()) {
        final String clean = MacroText.stripFormatting(line.getString());
        if (!clean.isBlank()) {
          text.append(' ').append(clean);
        }
      }
    }
    return text.toString();
  }

  public static String stackSearchText(ItemStack stack) {
    if (stack == null || stack.isEmpty()) {
      return "";
    }
    final String name = stackName(stack);
    final ItemLore lore = stack.get(DataComponents.LORE);
    if (lore == null) {
      return name;
    }

    StringBuilder text = null;
    for (Component line : lore.lines()) {
      final String clean = MacroText.stripFormatting(line.getString());
      if (clean.isBlank()) {
        continue;
      }
      if (text == null) {
        text = new StringBuilder(name.length() + 64).append(name);
      }
      text.append(' ').append(clean);
    }
    return text == null ? name : text.toString();
  }

  public static boolean stackMatchesName(ItemStack stack, String itemName) {
    return MacroText.normalizeSearchText(stackSearchText(stack))
        .contains(MacroText.normalizeSearchText(itemName));
  }

  public static boolean isMenuDecorationStack(ItemStack stack) {
    if (stack == null || stack.isEmpty()) {
      return true;
    }
    return stack.is(Items.ARROW)
        || stack.is(Items.BARRIER)
        || stack.is(Items.GLASS_PANE)
        || Items.STAINED_GLASS_PANE.asList().contains(stack.getItem());
  }
}
