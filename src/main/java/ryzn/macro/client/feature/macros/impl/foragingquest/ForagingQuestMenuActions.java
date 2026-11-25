package ryzn.macro.client.feature.macros.impl.foragingquest;

import static ryzn.macro.client.MacroInput.releaseAllKeys;
import static ryzn.macro.client.feature.macros.impl.foragingquest.ForagingQuestUnlockerMacro.GUI_TIMEOUT_TICKS;
import static ryzn.macro.client.util.gui.MacroScreens.closeCurrentScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import ryzn.macro.client.feature.macros.impl.tree.TreeLogType;

/**
 * @description Advances quest inventory, merchant, reward-menu, and log-drop actions.
 */
final class ForagingQuestMenuActions {

  private static final int USE_ITEM_TIMEOUT_TICKS = 20 * 15;

  private final ForagingQuestUnlockerMacro quest;

  ForagingQuestMenuActions(ForagingQuestUnlockerMacro quest) {
    this.quest = quest;
  }

  /**
   * Click the reward item in the middle of the popup (the axe); the menu closes itself, then
   * advance.
   */
  void tickClaimReward(Minecraft mc, LocalPlayer player, ForagingQuestState next) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    quest.look().clear();
    applyInventoryOutcome(
        quest.inventoryActions.claimReward(mc, player, quest.timedOut(GUI_TIMEOUT_TICKS)), next);
  }

  /**
   * A stall here must never cost the quest: the timeout goes on to the purchase like a finished
   * pass.
   */
  void tickSellInventory(Minecraft mc, LocalPlayer player) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    if (!quest.clearInventoryAtMerchant) {
      quest.setState(ForagingQuestState.BUY_LUMBER_LOGS);
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)) {
      quest.fail("Lumber Merchant GUI closed");
      return;
    }
    if (quest.inventoryClear.tickSell(mc, player, handled.getMenu())
        || quest.timedOut(GUI_TIMEOUT_TICKS * 2)) {
      quest.setState(ForagingQuestState.BUY_LUMBER_LOGS);
      return;
    }
    quest.status = "Selling inventory";
  }

  void tickSellFinalLogs(Minecraft mc, LocalPlayer player) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)) {
      quest.fail("Lumber Merchant GUI closed before all logs were sold");
      return;
    }
    if (quest.inventoryClear.tickSellLogs(mc, player, handled.getMenu())) {
      quest.setState(ForagingQuestState.DONE);
      return;
    }
    if (quest.timedOut(GUI_TIMEOUT_TICKS * 2)) {
      quest.fail("Lumber Merchant log sale timed out");
      return;
    }
    quest.status = "Selling collected logs";
  }

  /**
   * The quest chops with the axe, so it has to be held - but a missing axe is not worth failing
   * over.
   */
  void tickHoldGoldenAxe(Minecraft mc, LocalPlayer player) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    if (quest.inventoryClear.tickAxeToHotbar(mc, player) || quest.timedOut(GUI_TIMEOUT_TICKS)) {
      quest.setState(ForagingQuestState.PATH_LUMBER_JACK_START);
    }
  }

  void tickBuyLumberLogs(Minecraft mc, LocalPlayer player) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    ForagingLogPurchaseActions.Outcome outcome =
        quest.logPurchaseActions().tick(mc, player, quest.timedOut(GUI_TIMEOUT_TICKS * 4));
    if (outcome.screenOpened()) {
      quest.macroScreenOpened = true;
    }
    if (outcome.result() == ForagingLogPurchaseActions.Result.FAILED) {
      quest.fail(outcome.message());
    } else if (outcome.result() == ForagingLogPurchaseActions.Result.COMPLETE) {
      quest.setState(ForagingQuestState.CLOSE_LUMBER_GUI);
    } else if (outcome.message() != null) {
      quest.status = outcome.message();
    }
  }

  void tickStartLogDrop(TreeLogType type, ForagingQuestState next) {
    quest.logDropActions.reset();
    quest.status = "Preparing " + type.displayName() + " log drop";
    quest.setState(next);
  }

  void tickWaitLogDrop(
      Minecraft mc, LocalPlayer player, TreeLogType type, ForagingQuestState next) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    ForagingLogDropActions.Outcome outcome = quest.logDropActions.tick(mc, player, type);
    if (outcome.screenOpened()) {
      quest.macroScreenOpened = true;
    }
    if (outcome.result() == ForagingLogDropActions.Result.COMPLETE) {
      quest.look().clear();
      quest.setState(next);
    } else if (outcome.result() == ForagingLogDropActions.Result.FAILED) {
      quest.fail(outcome.message());
    } else if (outcome.message() != null) {
      quest.status = outcome.message();
    }
  }

  void tickClickGuiItem(
      Minecraft mc,
      LocalPlayer player,
      String itemName,
      ForagingQuestState next,
      boolean closeAfter) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    quest.look().clear();
    applyInventoryOutcome(
        quest.inventoryActions.clickGuiItem(
            mc, player, itemName, closeAfter, quest.timedOut(GUI_TIMEOUT_TICKS)),
        next);
  }

  void tickUseInventoryItem(
      Minecraft mc, LocalPlayer player, String itemNeedle, ForagingQuestState next) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    quest.look().clear();
    applyInventoryOutcome(
        quest.inventoryActions.useInventoryItem(
            mc, player, itemNeedle, quest.timedOut(USE_ITEM_TIMEOUT_TICKS)),
        next);
  }

  void applyInventoryOutcome(ForagingInventoryActions.Outcome outcome, ForagingQuestState next) {
    if (outcome.screenOpened()) {
      quest.macroScreenOpened = true;
    }
    if (outcome.result() == ForagingInventoryActions.Result.FAILED) {
      quest.fail(outcome.message());
    } else if (outcome.result() == ForagingInventoryActions.Result.COMPLETE) {
      quest.setState(next);
    } else if (outcome.message() != null) {
      quest.status = outcome.message();
    }
  }

  void tickCloseScreen(Minecraft mc, ForagingQuestState next) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    quest.look().clear();
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      if (quest.timedOut(GUI_TIMEOUT_TICKS)) {
        quest.fail("Screen close timed out");
      }
      return;
    }
    quest.setState(next);
  }
}
