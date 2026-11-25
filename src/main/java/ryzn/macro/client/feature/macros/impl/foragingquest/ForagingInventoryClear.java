package ryzn.macro.client.feature.macros.impl.foragingquest;

import java.util.function.Predicate;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.input.MacroActionTiming;
import ryzn.macro.client.util.inventory.MacroSlots;

/**
 * Makes room before the log purchase: offers the bag to the Lumber Merchant a stack at a time, then
 * puts the Golden Axe in the hotbar.
 *
 * <p>The sell is one middle-click per stack - the same gesture the wheat breaker and the
 * auto-seller use - in a single pass. Tools are kept; the remaining inventory is offered to the
 * merchant.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class ForagingInventoryClear {

  /** Main inventory plus hotbar. Armor and the offhand are deliberately out of scope. */
  private static final int PLAYER_INVENTORY_SIZE = 36;

  private final MacroActionTiming actionTiming;
  private int sellCursor;

  void begin() {
    sellCursor = 0;
  }

  /**
   * @return true once every slot has been offered to the shop.
   */
  boolean tickSell(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu) {
    return tickSell(mc, player, menu, ForagingInventoryClear::isSellable);
  }

  private boolean tickSell(
      Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, Predicate<ItemStack> sellable) {
    if (actionTiming.waitForMenuClick()) {
      return false;
    }
    while (sellCursor < PLAYER_INVENTORY_SIZE
        && !sellable.test(player.getInventory().getItem(sellCursor))) {
      sellCursor++;
    }
    if (sellCursor >= PLAYER_INVENTORY_SIZE) {
      return true;
    }

    final Slot slot = MacroSlots.findPlayerInventoryMenuSlot(menu, player, sellCursor);
    sellCursor++;
    if (slot == null) {
      return false;
    }
    MacroSlots.clickSlot(mc, menu, slot.index, 2, ContainerInput.CLONE, player);
    actionTiming.armMenuClick(GuiActionTiming.randomDelay());
    return false;
  }

  /**
   * @return true once every collected wood-log stack has been offered to the shop.
   */
  boolean tickSellLogs(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu) {
    return tickSell(mc, player, menu, ForagingInventoryClear::isLog);
  }

  /**
   * Move the axe into the hotbar, swapping it against whatever is selected when the hotbar is full
   * (by then that is a stack of the wood just bought). Confirmed by the hotbar itself on a later
   * tick.
   *
   * @return true when the axe is already in the hotbar, or there is no axe to move
   */
  boolean tickAxeToHotbar(Minecraft mc, LocalPlayer player) {
    if (MacroSlots.findHotbarSlot(player, ForagingInventoryClear::isGoldenAxe) >= 0) {
      return true;
    }
    final int inventorySlot =
        MacroSlots.findMainInventorySlot(player, ForagingInventoryClear::isGoldenAxe);
    if (inventorySlot < 0) {
      return true;
    } // no axe in the bag at all - nothing to wait for
    if (actionTiming.waitForMenuClick()) {
      return false;
    }
    int destination = MacroSlots.findFirstEmptyHotbarSlot(player);
    if (destination < 0) {
      destination = player.getInventory().getSelectedSlot();
    }
    MacroSlots.swapInventoryToHotbar(mc, player, inventorySlot, destination);
    actionTiming.armMenuClick(GuiActionTiming.randomDelay());
    return false;
  }

  private static boolean isSellable(ItemStack stack) {
    return stack != null && !stack.isEmpty() && !isTool(stack.getItem());
  }

  static boolean isTool(Item item) {
    String id = BuiltInRegistries.ITEM.getKey(item).getPath();
    return id.endsWith("_pickaxe")
        || id.endsWith("_axe")
        || id.endsWith("_shovel")
        || id.endsWith("_hoe")
        || id.endsWith("_sword")
        || item == Items.FISHING_ROD
        || item == Items.SHEARS
        || item == Items.FLINT_AND_STEEL
        || item == Items.BRUSH;
  }

  static boolean isLog(ItemStack stack) {
    return stack != null && !stack.isEmpty() && isLog(stack.getItem());
  }

  static boolean isLog(Item item) {
    return item == Items.OAK_LOG
        || item == Items.BIRCH_LOG
        || item == Items.SPRUCE_LOG
        || item == Items.DARK_OAK_LOG
        || item == Items.ACACIA_LOG
        || item == Items.JUNGLE_LOG;
  }

  private static boolean isGoldenAxe(ItemStack stack) {
    return stack != null && !stack.isEmpty() && stack.is(Items.GOLDEN_AXE);
  }
}
