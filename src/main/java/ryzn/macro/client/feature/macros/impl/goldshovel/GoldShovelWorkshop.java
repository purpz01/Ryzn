package ryzn.macro.client.feature.macros.impl.goldshovel;

import static ryzn.macro.client.MacroInput.releaseAllKeys;
import static ryzn.macro.client.MacroInput.selectHotbarSlot;
import static ryzn.macro.client.feature.commands.impl.MacroCommands.sendCommand;
import static ryzn.macro.client.feature.macros.impl.goldshovel.GoldShovelExperience.CLOSE_MENU_TIMEOUT_TICKS;
import static ryzn.macro.client.util.gui.MacroScreens.currentContainerMenu;
import static ryzn.macro.client.util.inventory.MacroSlots.isCursorEmpty;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Items;
import ryzn.macro.client.feature.macros.impl.goldshovel.GoldShovelMacro.State;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.util.inventory.MacroSlots;

/**
 * @description Runs crafting and enchanting with the existing menu action helpers.
 */
final class GoldShovelWorkshop {

  private final GoldShovelMacro hunt;

  GoldShovelWorkshop(GoldShovelMacro hunt) {
    this.hunt = hunt;
  }

  static final int OPEN_CRAFT_TIMEOUT_TICKS = 20 * 8;

  static final int WAIT_CRAFT_GUI_TIMEOUT_TICKS = 20 * 10;

  static final int SHOVEL_CRAFT_TIMEOUT_TICKS = 20 * 35;

  static final int MOVE_SHOVEL_TIMEOUT_TICKS = 20 * 20;

  static final int ROTATE_ENCHANT_TABLE_TIMEOUT_TICKS = 20 * 10;

  static final int OPEN_ENCHANT_TABLE_TIMEOUT_TICKS = 20 * 12;

  static final int WAIT_ENCHANT_GUI_TIMEOUT_TICKS = 20 * 10;

  void tickOpenCraft(Minecraft mc) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    if (hunt.stateTicks > OPEN_CRAFT_TIMEOUT_TICKS) {
      hunt.fail("Open /craft timeout");
      return;
    }
    if (mc.gui.screen() != null) {
      hunt.closeCurrentScreen(mc);
      return;
    }
    if (mc.player == null || mc.player.connection == null) {
      hunt.fail("Network handler missing");
      return;
    }
    sendCommand(mc, "craft");
    hunt.craftSession.markGuiOpened();
    hunt.setState(State.WAIT_CRAFT_GUI);
  }

  void tickWaitCraftGui(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    if (hunt.stateTicks > WAIT_CRAFT_GUI_TIMEOUT_TICKS) {
      hunt.fail("Craft Item GUI timeout");
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)
        || !hunt.menuSlots.isCraftItemScreen(mc)) {
      hunt.status = "Waiting for Craft Item";
      return;
    }
    final AbstractContainerMenu menu = handled.getMenu();
    final int gold = hunt.menuSlots.countPlayerMaterial(menu, player, Items.GOLD_INGOT);
    final int sticks = hunt.menuSlots.countPlayerMaterial(menu, player, Items.STICK);
    // Both tools come out of this one menu visit: 3 gold + 2 sticks for the axe, 1 + 2 for the
    // shovel.
    if (gold < 4 || sticks < 4) {
      hunt.fail("Need 4 gold ingots and 4 sticks");
      return;
    }
    hunt.setState(State.CRAFT_AXE);
  }

  /**
   * The axe is crafted and then left alone - no hotbar move, no enchant; the foraging leg picks it
   * up.
   */
  void tickCraftAxe(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    if (hunt.stateTicks > SHOVEL_CRAFT_TIMEOUT_TICKS) {
      hunt.fail("Golden Axe craft timeout");
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)
        || !hunt.menuSlots.isCraftItemScreen(mc)) {
      hunt.fail("Craft Item GUI closed");
      return;
    }
    if (mc.gameMode == null) {
      hunt.fail("Interaction manager missing");
      return;
    }

    GoldShovelCraftGrid.Outcome outcome =
        hunt.craftGrid.tick(mc, player, handled.getMenu(), GoldShovelCraftGrid.Craft.AXE);
    if (outcome.result() == GoldShovelCraftGrid.Result.FAILED) {
      hunt.fail(outcome.message());
    } else if (outcome.result() == GoldShovelCraftGrid.Result.COMPLETE) {
      hunt.setState(State.CRAFT_SHOVEL);
    } else if (outcome.message() != null) {
      hunt.status = outcome.message();
    }
  }

  void tickCraftShovel(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    if (hunt.stateTicks > SHOVEL_CRAFT_TIMEOUT_TICKS) {
      hunt.fail("Golden Shovel craft timeout");
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)
        || !hunt.menuSlots.isCraftItemScreen(mc)) {
      hunt.fail("Craft Item GUI closed");
      return;
    }
    final AbstractContainerMenu menu = handled.getMenu();
    if (mc.gameMode == null) {
      hunt.fail("Interaction manager missing");
      return;
    }

    GoldShovelCraftGrid.Outcome outcome =
        hunt.craftGrid.tick(mc, player, menu, GoldShovelCraftGrid.Craft.SHOVEL);
    if (outcome.result() == GoldShovelCraftGrid.Result.FAILED) {
      hunt.fail(outcome.message());
    } else if (outcome.result() == GoldShovelCraftGrid.Result.COMPLETE) {
      hunt.setState(State.MOVE_SHOVEL_TO_HOTBAR);
    } else if (outcome.message() != null) {
      hunt.status = outcome.message();
    }
  }

  void tickMoveShovelToHotbar(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);

    if (hunt.stateTicks > MOVE_SHOVEL_TIMEOUT_TICKS) {
      hunt.fail("Golden Shovel hotbar timeout");
      return;
    }
    if (mc.gui.screen() != null) {
      final AbstractContainerMenu menu = currentContainerMenu(mc);
      if (menu != null && !isCursorEmpty(menu)) {
        if (!hunt.craftGrid.returnCursorStackToPlayerInventory(mc, menu, player)) {
          hunt.fail("Cursor return failed");
        }
        return;
      }
      hunt.closeCurrentScreen(mc);
      return;
    }

    if (hunt.actionTiming.waitForMenuClick()) {
      return;
    }

    final int hotbarSlot = MacroSlots.findHotbarSlot(player, hunt.menuSlots::isGoldenShovel);
    if (hotbarSlot >= 0) {
      selectHotbarSlot(player, hotbarSlot);
      hunt.setState(State.CLOSE_CRAFT_MENU);
      return;
    }

    final int inventorySlot =
        MacroSlots.findMainInventorySlot(player, hunt.menuSlots::isGoldenShovel);
    if (inventorySlot < 0) {
      hunt.fail("Golden Shovel missing");
      return;
    }
    if (hunt.craftSession.isHotbarMoveRequested()) {
      hunt.status = "Waiting for Golden Shovel swap";
      return;
    }
    int dest = MacroSlots.findFirstEmptyHotbarSlot(player);
    if (dest < 0) {
      dest = player.getInventory().getSelectedSlot();
    }
    if (!MacroSlots.swapInventoryToHotbar(mc, player, inventorySlot, dest)) {
      hunt.fail("Golden Shovel swap failed");
      return;
    }
    hunt.craftSession.markHotbarMoveRequested();
    hunt.actionTiming.armMenuClick(GuiActionTiming.randomDelay());
    hunt.status = "Moving Golden Shovel to hotbar";
  }

  void tickCloseCraftMenu(Minecraft mc) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    if (mc.gui.screen() != null) {
      final AbstractContainerMenu menu = currentContainerMenu(mc);
      if (menu != null && !isCursorEmpty(menu)) {
        if (!hunt.craftGrid.returnCursorStackToPlayerInventory(mc, menu, mc.player)) {
          hunt.fail("Cursor return failed");
        }
        return;
      }
      if (hunt.stateTicks > CLOSE_MENU_TIMEOUT_TICKS) {
        hunt.fail("Craft close timeout");
        return;
      }
      if (hunt.craftSession.shouldRequestClose(hunt.stateTicks)) {
        hunt.closeCurrentScreen(mc);
      }
      return;
    }
    hunt.craftSession.clearGuiOpened();
    hunt.craftSession.resetCloseRequest();
    hunt.setState(State.ROTATE_TO_ENCHANT_TABLE);
  }

  /** The target tool comes to hand only for its own table visit. */
  void tickMoveTargetToHotbar(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);

    if (hunt.stateTicks > MOVE_SHOVEL_TIMEOUT_TICKS) {
      hunt.fail(hunt.target.toolName() + " hotbar timeout");
      return;
    }
    if (mc.gui.screen() != null) {
      hunt.closeCurrentScreen(mc);
      return;
    }

    if (hunt.actionTiming.waitForMenuClick()) {
      return;
    }

    final int hotbarSlot = MacroSlots.findHotbarSlot(player, hunt.target::matches);
    if (hotbarSlot >= 0) {
      selectHotbarSlot(player, hotbarSlot);
      hunt.setState(State.ROTATE_TO_ENCHANT_TABLE);
      return;
    }

    final int inventorySlot = MacroSlots.findMainInventorySlot(player, hunt.target::matches);
    if (inventorySlot < 0) {
      hunt.fail(hunt.target.toolName() + " missing");
      return;
    }
    if (hunt.craftSession.isHotbarMoveRequested()) {
      hunt.status = "Waiting for " + hunt.target.toolName() + " swap";
      return;
    }
    int dest = MacroSlots.findFirstEmptyHotbarSlot(player);
    if (dest < 0) {
      dest = player.getInventory().getSelectedSlot();
    }
    if (!MacroSlots.swapInventoryToHotbar(mc, player, inventorySlot, dest)) {
      hunt.fail(hunt.target.toolName() + " swap failed");
      return;
    }
    hunt.craftSession.markHotbarMoveRequested();
    hunt.actionTiming.armMenuClick(GuiActionTiming.randomDelay());
    hunt.status = "Moving " + hunt.target.toolName() + " to hotbar";
  }

  void tickRotateToEnchantTable(Minecraft mc, LocalPlayer player, ClientLevel level) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    if (mc.gui.screen() != null) {
      return;
    }

    final int toolSlot = MacroSlots.findHotbarSlot(player, hunt.target::matches);
    if (toolSlot < 0) {
      hunt.fail(hunt.target.toolName() + " missing");
      return;
    }
    selectHotbarSlot(player, toolSlot);

    hunt.enchantSession.rememberTable(
        hunt.worldTargets.enchantingTable(level, player, hunt.enchantSession.tablePos()));
    if (hunt.enchantSession.tablePos() == null) {
      if (hunt.stateTicks > ROTATE_ENCHANT_TABLE_TIMEOUT_TICKS) {
        hunt.fail("Enchanting table missing");
      } else {
        hunt.status = "Searching for enchanting table";
      }
      return;
    }

    if (hunt.look().aimAtBlock(player, hunt.enchantSession.tablePos())) {
      hunt.setState(State.OPEN_ENCHANT_TABLE);
    }
  }

  void tickOpenEnchantTable(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    hunt.releaseMovementKeepUse(mc.options);

    if (hunt.menuSlots.isEnchantItemScreen(mc)) {
      hunt.setUse(mc.options, false);
      hunt.enchantSession.markGuiOpened();
      hunt.setState(State.WAIT_ENCHANT_GUI);
      return;
    }
    if (mc.gui.screen() != null) {
      hunt.setUse(mc.options, false);
      return;
    }
    if (hunt.stateTicks > OPEN_ENCHANT_TABLE_TIMEOUT_TICKS) {
      hunt.setUse(mc.options, false);
      hunt.fail("Enchanting table open timeout");
      return;
    }

    final int toolSlot = MacroSlots.findHotbarSlot(player, hunt.target::matches);
    if (toolSlot < 0) {
      hunt.setUse(mc.options, false);
      hunt.fail(hunt.target.toolName() + " missing");
      return;
    }
    selectHotbarSlot(player, toolSlot);

    if (hunt.enchantSession.tablePos() == null
        || !hunt.look().aimAtBlock(player, hunt.enchantSession.tablePos())) {
      hunt.setUse(mc.options, false);
      return;
    }
    hunt.setUse(mc.options, true);
    hunt.status = "Opening enchanting table";
  }

  void tickWaitEnchantGui(Minecraft mc) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    if (hunt.stateTicks > WAIT_ENCHANT_GUI_TIMEOUT_TICKS) {
      hunt.fail("Enchant Item GUI timeout");
      return;
    }
    if (hunt.menuSlots.isEnchantItemScreen(mc)) {
      hunt.enchantSession.markGuiOpened();
      hunt.setState(State.INSERT_TOOL);
    }
  }

  void tickInsertTool(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    applyEnchantOutcome(
        mc, hunt.enchantActions.insertTool(mc, player, hunt.stateTicks, hunt.target));
  }

  void tickClickEnchantment(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    applyEnchantOutcome(
        mc, hunt.enchantActions.clickEnchantment(mc, player, hunt.stateTicks, hunt.target));
  }

  void tickClickEnchantLevel(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    applyEnchantOutcome(
        mc, hunt.enchantActions.clickEnchantLevel(mc, player, hunt.stateTicks, hunt.target));
  }

  void tickWaitEnchantChat(Minecraft mc) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    applyEnchantOutcome(mc, hunt.enchantActions.awaitConfirmation(hunt.stateTicks, hunt.target));
  }

  void tickCloseEnchantMenu(Minecraft mc) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    applyEnchantOutcome(
        mc, hunt.enchantActions.closeMenu(hunt.stateTicks, mc.gui.screen() != null));
  }

  void applyEnchantOutcome(Minecraft mc, GoldShovelEnchantActions.Outcome outcome) {
    switch (outcome.action()) {
      case INSERTED -> hunt.setState(State.CLICK_ENCHANTMENT);
      case ENCHANTMENT_SELECTED -> hunt.setState(State.CLICK_ENCHANT_LEVEL);
      case AWAIT_CONFIRMATION -> hunt.setState(State.WAIT_ENCHANT_CHAT);
      case BEGIN_CLOSE -> hunt.setState(State.CLOSE_ENCHANT_MENU);
      case REQUEST_CLOSE -> hunt.closeCurrentScreen(mc);
      // The shovel's table visit is the halfway point: the axe still needs its bottles and
      // Efficiency V.
      case FINISHED -> {
        if (hunt.target == GoldShovelMenuSlots.EnchantTarget.SHOVEL) {
          hunt.beginAxeLeg();
        } else {
          hunt.setState(State.DONE);
        }
      }
      case FAILED -> hunt.fail(outcome.message());
      case NONE -> {
        if (outcome.message() != null) {
          hunt.status = outcome.message();
        }
      }

      default -> {}
    }
  }
}
