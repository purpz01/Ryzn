package ryzn.macro.client.feature.macros.impl.goldshovel;

import static ryzn.macro.client.util.gui.MacroScreens.cleanTitle;
import static ryzn.macro.client.util.inventory.MacroItems.isMenuDecorationStack;
import static ryzn.macro.client.util.inventory.MacroItems.stackName;
import static ryzn.macro.client.util.inventory.MacroItems.stackSearchText;
import static ryzn.macro.client.util.inventory.MacroSlots.isPlayerInventorySlot;
import static ryzn.macro.client.util.text.MacroText.containsStackAmount;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import java.util.Locale;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ryzn.macro.client.util.gui.MacroMenu;

final class GoldShovelMenuSlots {
  private static final int GRID_SIZE = 9;
  private static final int SLOT_PIXELS = 18;
  private static final int INVENTORY_SIZE = 36;

  /** A tool this macro takes to the table and the enchantment it buys there. */
  @Accessors(fluent = true)
  enum EnchantTarget {
    SHOVEL(
        "Golden Shovel",
        Items.GOLDEN_SHOVEL,
        "Efficiency",
        "Efficiency II",
        "efficiency (ii|2)(?![a-z0-9])",
        "12 exp levels",
        1,
        0),
    AXE(
        "Golden Axe",
        Items.GOLDEN_AXE,
        "Efficiency",
        "Efficiency V",
        "efficiency (v|5)(?![a-z0-9])",
        "30 exp levels",
        3,
        30),
    UNDEAD_SWORD(
        "Undead Sword",
        Items.IRON_SWORD,
        "Smite",
        "Smite V",
        "smite (v|5)(?![a-z0-9])",
        "30 exp levels",
        3,
        30);

    @Getter(AccessLevel.PACKAGE)
    private final String toolName;

    private final Item item;

    @Getter(AccessLevel.PACKAGE)
    private final String enchantmentName;

    @Getter(AccessLevel.PACKAGE)
    private final String levelName;

    private final Pattern levelPattern;
    private final String costHint;

    @Getter(AccessLevel.PACKAGE)
    private final int experienceBottleStacks;

    @Getter(AccessLevel.PACKAGE)
    private final int requiredExperienceLevel;

    EnchantTarget(
        String toolName,
        Item item,
        String enchantmentName,
        String levelName,
        String levelRegex,
        String costHint,
        int experienceBottleStacks,
        int requiredExperienceLevel) {
      this.toolName = toolName;
      this.item = item;
      this.enchantmentName = enchantmentName;
      this.levelName = levelName;
      this.levelPattern = Pattern.compile(levelRegex);
      this.costHint = costHint;
      this.experienceBottleStacks = experienceBottleStacks;
      this.requiredExperienceLevel = requiredExperienceLevel;
    }

    boolean matches(ItemStack stack) {
      return stack != null
          && !stack.isEmpty()
          && (stack.is(item) || normalizeSearchText(stackSearchText(stack)).contains(lowerName()));
    }

    /**
     * The level has to end where the name ends: "Efficiency V" must not answer for "Efficiency VI".
     */
    boolean matchesLevelText(String normalizedText) {
      return normalizedText != null && levelPattern.matcher(normalizedText).find();
    }

    boolean mentionsCost(String normalizedText) {
      return normalizedText != null && normalizedText.contains(costHint);
    }

    /**
     * The confirmation Hypixel sends, already normalized: "you enchanted your golden axe with ...".
     */
    String confirmationText() {
      return "you enchanted your " + lowerName() + " with " + levelName.toLowerCase(Locale.ROOT);
    }

    private String lowerName() {
      return toolName.toLowerCase(Locale.ROOT);
    }
  }

  boolean isLumberMerchantScreen(Minecraft minecraft) {
    return isContainer(minecraft) && isLumberMerchantTitle(cleanTitle(minecraft));
  }

  boolean isLibrarianScreen(Minecraft minecraft) {
    return isContainer(minecraft) && isLibrarianTitle(cleanTitle(minecraft));
  }

  boolean isTradingOptionsScreen(Minecraft minecraft) {
    return isContainer(minecraft) && isTradingOptionsTitle(cleanTitle(minecraft));
  }

  boolean isCraftItemScreen(Minecraft minecraft) {
    return isContainer(minecraft) && isCraftItemTitle(cleanTitle(minecraft));
  }

  boolean isEnchantItemScreen(Minecraft minecraft) {
    return isContainer(minecraft) && isEnchantItemTitle(cleanTitle(minecraft));
  }

  boolean isEnchantmentPage(Minecraft minecraft, EnchantTarget target) {
    return isEnchantmentPageTitle(cleanTitle(minecraft), target);
  }

  boolean isLumberMerchantTitle(String title) {
    return lower(title).contains("lumber merchant");
  }

  boolean isLibrarianTitle(String title) {
    return lower(title).contains("librarian");
  }

  boolean isTradingOptionsTitle(String title) {
    return lower(title).contains("shop trading options");
  }

  boolean isCraftItemTitle(String title) {
    return title.equalsIgnoreCase("Craft Item");
  }

  boolean isEnchantItemTitle(String title) {
    return lower(title).contains("enchant item");
  }

  boolean isEnchantmentPageTitle(String title, EnchantTarget target) {
    final String lower = lower(title);
    return target != null
        && lower.contains("enchant item")
        && lower.contains(lower(target.enchantmentName()));
  }

  Slot stickTrade(AbstractContainerMenu menu, LocalPlayer player) {
    if (menu == null) {
      return null;
    }
    return MacroMenu.bestNonPlayerSlot(
        menu,
        player,
        slot -> {
          final ItemStack stack = slot.getItem();
          if (stack.isEmpty()) {
            return MacroMenu.SKIP;
          }
          final String name = normalizeSearchText(stackName(stack));
          final String search = normalizeSearchText(stackSearchText(stack));
          if ((!stack.is(Items.STICK) && !name.contains("stick") && !search.contains("stick"))
              || !search.contains("click to trade")) {
            return MacroMenu.SKIP;
          }
          if (stack.getCount() != 32 && !search.contains("stick x32") && !search.contains("x32")) {
            return MacroMenu.SKIP;
          }
          int score = 20;
          if (stack.is(Items.STICK)) {
            score += 40;
          }
          if (stack.getCount() == 32) {
            score += 20;
          }
          if (search.contains("stick x32")) {
            score += 15;
          }
          if (name.contains("stick")) {
            score += 10;
          }
          return score;
        });
  }

  Slot experienceBottleTrade(AbstractContainerMenu menu, LocalPlayer player) {
    return bestScored(menu, player, false);
  }

  Slot experienceBottleStackOption(AbstractContainerMenu menu, LocalPlayer player) {
    return bestScored(menu, player, true);
  }

  Slot enchantmentOption(AbstractContainerMenu menu, LocalPlayer player, EnchantTarget target) {
    if (menu == null) {
      return null;
    }
    final String enchantment = lower(target.enchantmentName());
    return MacroMenu.bestNonPlayerSlot(
        menu,
        player,
        slot -> {
          final ItemStack stack = slot.getItem();
          if (stack.isEmpty() || isMenuDecorationStack(stack)) {
            return MacroMenu.SKIP;
          }
          final String name = normalizeSearchText(stackName(stack));
          final String search = normalizeSearchText(stackSearchText(stack));
          if ((!name.contains(enchantment) && !search.contains(enchantment))
              || search.contains("click to enchant")) {
            return MacroMenu.SKIP;
          }
          int score = 20;
          if (name.contains(enchantment)) {
            score += 20;
          }
          if (search.contains("click to view")) {
            score += 40;
          }
          return score;
        });
  }

  /** The "click to enchant" entry for {@code target}'s level on the open enchantment page. */
  Slot enchantmentLevelEnchant(
      AbstractContainerMenu menu, LocalPlayer player, EnchantTarget target) {
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
          final String name = normalizeSearchText(stackName(stack));
          final String search = normalizeSearchText(stackSearchText(stack));
          if ((!target.matchesLevelText(name) && !target.matchesLevelText(search))
              || !search.contains("click to enchant")) {
            return MacroMenu.SKIP;
          }
          int score = 20;
          if (target.matchesLevelText(name)) {
            score += 30;
          }
          if (target.mentionsCost(search)) {
            score += 12;
          }
          return score;
        });
  }

  boolean playerHasExperienceBottles(LocalPlayer player) {
    final Inventory inventory = player.getInventory();
    for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
      if (isExperienceBottle(inventory.getItem(slot))) {
        return true;
      }
    }
    return false;
  }

  /**
   * How many bottles the player holds; a purchase is counted as landed when this rises by a stack.
   */
  int countExperienceBottles(LocalPlayer player) {
    final Inventory inventory = player.getInventory();
    int count = 0;
    for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
      final ItemStack stack = inventory.getItem(slot);
      if (isExperienceBottle(stack)) {
        count += stack.getCount();
      }
    }
    return count;
  }

  boolean isExperienceBottle(ItemStack stack) {
    return stack != null
        && !stack.isEmpty()
        && (stack.is(Items.EXPERIENCE_BOTTLE)
            || normalizeSearchText(stackSearchText(stack)).contains("experience bottle"));
  }

  boolean isGoldenShovel(ItemStack stack) {
    return EnchantTarget.SHOVEL.matches(stack);
  }

  boolean isGoldenAxe(ItemStack stack) {
    return EnchantTarget.AXE.matches(stack);
  }

  Slot toolPlayerSlot(AbstractContainerMenu menu, LocalPlayer player, EnchantTarget target) {
    for (Slot slot : menu.slots) {
      if (slot != null
          && isPlayerInventorySlot(menu, player, slot)
          && target.matches(slot.getItem())) {
        return slot;
      }
    }
    return null;
  }

  Slot insertedToolSlot(AbstractContainerMenu menu, LocalPlayer player, EnchantTarget target) {
    for (Slot slot : menu.slots) {
      if (slot != null
          && !isPlayerInventorySlot(menu, player, slot)
          && !isMenuDecorationStack(slot.getItem())
          && target.matches(slot.getItem())) {
        return slot;
      }
    }
    return null;
  }

  int countPlayerMaterial(AbstractContainerMenu menu, LocalPlayer player, Item item) {
    int count = 0;
    for (Slot slot : menu.slots) {
      if (slot != null && isPlayerInventorySlot(menu, player, slot) && slot.getItem().is(item)) {
        count += slot.getItem().getCount();
      }
    }
    return count;
  }

  private Slot bestScored(AbstractContainerMenu menu, LocalPlayer player, boolean stackOption) {
    if (menu == null) {
      return null;
    }
    return MacroMenu.bestNonPlayerSlot(
        menu,
        player,
        slot -> {
          final ItemStack stack = slot.getItem();
          if (stack.isEmpty()) {
            return MacroMenu.SKIP;
          }
          final String name = normalizeSearchText(stackName(stack));
          final String search = normalizeSearchText(stackSearchText(stack));
          if (!isExperienceBottleText(stack, name, search)) {
            return MacroMenu.SKIP;
          }
          if (stackOption
              ? !search.contains("click to purchase")
              : !search.contains("right-click for more trading options")) {
            return MacroMenu.SKIP;
          }
          // The shop page's single-bottle trade also says "click to purchase"; only x64 is the
          // stack.
          if (stackOption && stack.getCount() != 64 && !containsStackAmount(search, 64)) {
            return MacroMenu.SKIP;
          }
          int score = 20;
          if (stack.is(Items.EXPERIENCE_BOTTLE)) {
            score += 40;
          }
          if (stackOption && stack.getCount() == 64) {
            score += 30;
          }
          if (name.contains("experience bottle")) {
            score += 10;
          }
          return score;
        });
  }

  private boolean isExperienceBottleText(ItemStack stack, String name, String search) {
    return stack.is(Items.EXPERIENCE_BOTTLE)
        || name.contains("experience bottle")
        || search.contains("experience bottle");
  }

  private boolean isContainer(Minecraft minecraft) {
    return minecraft.gui.screen() instanceof AbstractContainerScreen<?>;
  }

  private String lower(String value) {
    return value.toLowerCase(Locale.ROOT);
  }
}
