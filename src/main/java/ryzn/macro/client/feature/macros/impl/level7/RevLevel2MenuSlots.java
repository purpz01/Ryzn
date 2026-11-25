package ryzn.macro.client.feature.macros.impl.level7;

import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import ryzn.macro.client.util.inventory.MacroItems;
import ryzn.macro.client.util.inventory.MacroSlots;
import ryzn.macro.client.util.text.MacroText;

/** Item-name matching and offer-slot lookup for the Rev level 2 starter-gear route. */
final class RevLevel2MenuSlots {

  enum RewardAction {
    MISSING,
    CLAIM,
    ADVANCE
  }

  enum ArmorPiece {
    HELMET(39, "Iron Helmet", "Iron Helm"),
    CHESTPLATE(38, "Iron Chestplate"),
    LEGGINGS(37, "Iron Leggings"),
    BOOTS(36, "Iron Boots");

    final int inventoryIndex;
    private final String[] names;

    ArmorPiece(int inventoryIndex, String... names) {
      this.inventoryIndex = inventoryIndex;
      this.names = names;
    }

    boolean matches(ItemStack stack) {
      return RevLevel2MenuSlots.matchesName(
          stack == null || stack.isEmpty() ? "" : stack.getHoverName().getString(), names);
    }

    boolean matchesName(String name) {
      return RevLevel2MenuSlots.matchesName(name, names);
    }
  }

  private RevLevel2MenuSlots() {}

  static boolean isUndeadSword(ItemStack stack) {
    return matchesName(
        stack == null || stack.isEmpty() ? "" : stack.getHoverName().getString(), "Undead Sword");
  }

  static boolean isIronArmor(ItemStack stack) {
    return isIronArmorName(
        stack == null || stack.isEmpty() ? "" : stack.getHoverName().getString());
  }

  static boolean isIronArmorName(String name) {
    return matchesName(name, "Iron Armor");
  }

  static Slot findOffer(
      AbstractContainerMenu menu, LocalPlayer player, Predicate<ItemStack> matches) {
    if (menu == null || player == null || matches == null) {
      return null;
    }
    for (Slot slot : menu.slots) {
      if (slot == null
          || slot.getItem().isEmpty()
          || MacroSlots.isPlayerInventorySlot(menu, player, slot)
          || MacroSlots.isCommonMenuControlSlot(slot)) {
        continue;
      }
      if (matches.test(slot.getItem())) {
        return slot;
      }
    }
    return null;
  }

  static Slot findMenuChoice(
      AbstractContainerMenu menu, LocalPlayer player, String name, String action) {
    return findOffer(
        menu,
        player,
        stack ->
            matchesMenuChoiceText(
                stack.getHoverName().getString(), MacroItems.stackSearchText(stack), name, action));
  }

  static Slot findMenuEntry(AbstractContainerMenu menu, LocalPlayer player, String name) {
    return findOffer(
        menu, player, stack -> matchesDisplayName(stack.getHoverName().getString(), name));
  }

  static boolean matchesMenuChoiceText(String rawName, String rawText, String name, String action) {
    final String text = MacroText.normalizeSearchText(rawText);
    return matchesDisplayName(rawName, name)
        && text.contains(MacroText.normalizeSearchText(action));
  }

  static RewardAction rewardAction(ItemStack stack, String expectedName) {
    return rewardAction(
        stack.getHoverName().getString(), MacroItems.stackSearchText(stack), expectedName);
  }

  static RewardAction rewardAction(String rawName, String rawText, String expectedName) {
    if (!matchesDisplayName(rawName, expectedName)) {
      return RewardAction.MISSING;
    }
    return MacroText.normalizeSearchText(rawText).contains("click to claim rewards")
        ? RewardAction.CLAIM
        : RewardAction.ADVANCE;
  }

  private static boolean matchesDisplayName(String rawName, String expectedName) {
    final String name =
        MacroText.normalizeSearchText(rawName).replaceFirst("^[^\\p{L}\\p{N}]+", "");
    return name.equals(MacroText.normalizeSearchText(expectedName));
  }

  static int findInventoryItem(
      LocalPlayer player, Predicate<ItemStack> matches, int start, int endExclusive) {
    if (player == null || matches == null) {
      return -1;
    }
    final int from = Math.max(0, start);
    final int to = Math.min(40, Math.max(from, endExclusive));
    for (int index = from; index < to; index++) {
      if (matches.test(player.getInventory().getItem(index))) {
        return index;
      }
    }
    return -1;
  }

  static boolean matchesName(String rawName, String... needles) {
    final String name = MacroText.normalizeSearchText(rawName == null ? "" : rawName);
    for (String needle : needles) {
      if (name.contains(MacroText.normalizeSearchText(needle))) {
        return true;
      }
    }
    return false;
  }
}
