package ryzn.macro.client.feature.macros.impl.goldshovel;

import static ryzn.macro.client.util.inventory.MacroSlots.clickSlot;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import ryzn.macro.client.feature.macros.impl.goldshovel.GoldShovelMenuSlots.EnchantTarget;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.input.MacroActionTiming;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class GoldShovelEnchantActions {

  enum Action {
    NONE,
    INSERTED,
    ENCHANTMENT_SELECTED,
    AWAIT_CONFIRMATION,
    BEGIN_CLOSE,
    REQUEST_CLOSE,
    FINISHED,
    FAILED
  }

  record Outcome(Action action, String message) {

    static Outcome working() {
      return new Outcome(Action.NONE, null);
    }

    static Outcome status(String message) {
      return new Outcome(Action.NONE, message);
    }

    static Outcome action(Action action) {
      return new Outcome(action, null);
    }

    static Outcome failed(String message) {
      return new Outcome(Action.FAILED, message);
    }
  }

  private static final int INSERT_TOOL_TIMEOUT_TICKS = 20 * 12;
  private static final int ENCHANTMENT_CLICK_TIMEOUT_TICKS = 20 * 12;
  private static final int ENCHANT_LEVEL_CLICK_TIMEOUT_TICKS = 20 * 12;
  private static final int ENCHANT_CHAT_TIMEOUT_TICKS = 20 * 15;
  private static final int CLOSE_ENCHANT_TIMEOUT_TICKS = 20 * 5;

  private final GoldShovelEnchantSession session;
  private final GoldShovelMenuSlots menuSlots;
  private final MacroActionTiming actionTiming;

  Outcome insertTool(Minecraft mc, LocalPlayer player, int stateTicks, EnchantTarget target) {
    if (stateTicks > INSERT_TOOL_TIMEOUT_TICKS) {
      return Outcome.failed(target.toolName() + " insert timeout");
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)
        || !menuSlots.isEnchantItemScreen(mc)) {
      return Outcome.failed("Enchant Item GUI closed");
    }
    AbstractContainerMenu menu = handled.getMenu();
    if (menuSlots.insertedToolSlot(menu, player, target) != null) {
      session.markToolInsertionRequested();
      return Outcome.action(Action.INSERTED);
    }
    if (actionTiming.waitForMenuClick() || session.isToolInsertionRequested()) {
      return Outcome.working();
    }
    if (mc.gameMode == null) {
      return Outcome.failed("Interaction manager missing");
    }
    Slot tool = menuSlots.toolPlayerSlot(menu, player, target);
    if (tool == null) {
      return Outcome.failed(target.toolName() + " missing");
    }
    clickSlot(mc, menu, tool.index, 0, ContainerInput.QUICK_MOVE, player);
    session.markToolInsertionRequested();
    armClickDelay();
    return Outcome.status("Inserted " + target.toolName());
  }

  Outcome clickEnchantment(Minecraft mc, LocalPlayer player, int stateTicks, EnchantTarget target) {
    if (stateTicks > ENCHANTMENT_CLICK_TIMEOUT_TICKS) {
      return Outcome.failed(target.enchantmentName() + " click timeout");
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)
        || !menuSlots.isEnchantItemScreen(mc)) {
      return Outcome.failed("Enchant Item GUI closed");
    }
    AbstractContainerMenu menu = handled.getMenu();
    if (menuSlots.isEnchantmentPage(mc, target)
        || menuSlots.enchantmentLevelEnchant(menu, player, target) != null) {
      return Outcome.action(Action.ENCHANTMENT_SELECTED);
    }
    if (actionTiming.waitForMenuClick() || session.isEfficiencyClickRequested()) {
      return Outcome.working();
    }
    if (mc.gameMode == null) {
      return Outcome.failed("Interaction manager missing");
    }
    Slot enchantment = menuSlots.enchantmentOption(menu, player, target);
    if (enchantment == null) {
      return Outcome.status("Searching for " + target.enchantmentName());
    }
    clickSlot(mc, menu, enchantment.index, 0, ContainerInput.PICKUP, player);
    session.markEfficiencyClickRequested();
    armClickDelay();
    return Outcome.status("Clicked " + target.enchantmentName());
  }

  Outcome clickEnchantLevel(
      Minecraft mc, LocalPlayer player, int stateTicks, EnchantTarget target) {
    if (session.isEnchantConfirmed()) {
      return Outcome.action(Action.AWAIT_CONFIRMATION);
    }
    if (stateTicks > ENCHANT_LEVEL_CLICK_TIMEOUT_TICKS) {
      return Outcome.failed(target.levelName() + " click timeout");
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)
        || !menuSlots.isEnchantItemScreen(mc)) {
      return Outcome.failed("Enchant Item GUI closed");
    }
    if (actionTiming.waitForMenuClick()) {
      return Outcome.working();
    }
    if (session.isEnchantLevelClickRequested()) {
      return Outcome.action(Action.AWAIT_CONFIRMATION);
    }
    if (mc.gameMode == null) {
      return Outcome.failed("Interaction manager missing");
    }
    Slot level = menuSlots.enchantmentLevelEnchant(handled.getMenu(), player, target);
    if (level == null) {
      return Outcome.status("Searching for " + target.levelName());
    }
    clickSlot(mc, handled.getMenu(), level.index, 0, ContainerInput.PICKUP, player);
    session.markEnchantLevelClickRequested();
    armClickDelay();
    return Outcome.status("Clicked " + target.levelName());
  }

  Outcome awaitConfirmation(int stateTicks, EnchantTarget target) {
    if (session.isEnchantConfirmed()) {
      return Outcome.action(Action.BEGIN_CLOSE);
    }
    if (stateTicks > ENCHANT_CHAT_TIMEOUT_TICKS) {
      return Outcome.failed(target.levelName() + " chat timeout");
    }
    return Outcome.working();
  }

  Outcome closeMenu(int stateTicks, boolean screenOpen) {
    if (stateTicks > CLOSE_ENCHANT_TIMEOUT_TICKS && screenOpen) {
      return Outcome.failed("Enchant close timeout");
    }
    if (screenOpen) {
      return session.shouldRequestClose(stateTicks)
          ? Outcome.action(Action.REQUEST_CLOSE)
          : Outcome.working();
    }
    session.clearGuiOpened();
    session.resetCloseRequest();
    return Outcome.action(Action.FINISHED);
  }

  private void armClickDelay() {
    actionTiming.armMenuClick(GuiActionTiming.randomDelay());
  }
}
