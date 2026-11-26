package ryzn.macro.client.feature.macros.impl.level7;

import static ryzn.macro.client.feature.macros.impl.level7.RevLevel2Gear.MENU_TIMEOUT_TICKS;
import static ryzn.macro.client.feature.macros.impl.level7.RevLevel2Merchants.MAX_PATH_ATTEMPTS;
import static ryzn.macro.client.feature.macros.impl.level7.RevLevel2Merchants.NPC_MENU_TIMEOUT_TICKS;
import static ryzn.macro.client.feature.macros.impl.level7.RevLevel2Merchants.PATH_RETRY_TICKS;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.feature.macros.impl.level7.RevLevel2Macro.State;
import ryzn.macro.client.feature.rotation.impl.MacroAim;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.util.gui.MacroScreens;
import ryzn.macro.client.util.inventory.MacroSlots;
import ryzn.macro.client.util.world.MacroObstruction;

/**
 * @description Runs the Maddox menus, Slayer loop, death return and level reward claims.
 */
final class RevLevel2SlayerRoute {

  private final RevLevel2Macro hunt;

  RevLevel2SlayerRoute(RevLevel2Macro hunt) {
    this.hunt = hunt;
  }

  static final BlockPos MADDOX_POSITION = new BlockPos(-83, 67, -132);

  /** "SLAYER QUEST FAILED!" follows the death line; without it the quest is still running. */
  static final int DEATH_RESULT_WAIT_TICKS = 40;

  boolean slayerQuestStarted;

  boolean slayerRunDied;

  boolean slayerQuestFailed;

  /** Where the zombies were last being fought; a death without the boss up walks back here. */
  BlockPos slayerFightSpot;

  boolean zombieSlayerLevelTwo;

  boolean levelRewardsPending;

  void tickPathToMaddox(Minecraft mc, LocalPlayer player, State next) {
    tickMaddoxApproach(mc, player, next, false);
  }

  void tickCombatPathToMaddox(Minecraft mc, LocalPlayer player, State next) {
    tickMaddoxApproach(mc, player, next, true);
  }

  private void tickMaddoxApproach(
      Minecraft mc, LocalPlayer player, State next, boolean combatReturn) {
    hunt.look().clear();
    boolean nearMaddox = hunt.merchants.nearPosition(player, MADDOX_POSITION);
    var maddox = hunt.merchants.findMerchant(mc.level, "Maddox", MADDOX_POSITION);
    boolean canInteract =
        maddox != null && RevLevel2Merchants.canInteract(mc.level, player, maddox);
    if (RevLevel2Macro.isWithinMaddoxArrival(
        player.getX(), player.getY(), player.getZ(), maddox != null, canInteract)) {
      if (combatReturn && hunt.combat.isActive()) hunt.combat.stop();
      PathfindCommand.stopNavQuiet();
      hunt.setState(next, "At Maddox");
      return;
    }
    if (combatReturn && !nearMaddox) {
      if (!hunt.combat.isActive() && !hunt.combat.startMaddoxReturn(player, MADDOX_POSITION)) {
        hunt.fail(hunt.combat.statusText());
        return;
      }
      if (hunt.combat.tick(mc) == RevLevel2SlayerCombat.Result.FAILED) {
        hunt.fail(hunt.combat.statusText());
      } else {
        hunt.status = hunt.combat.statusText();
      }
      return;
    }
    if (combatReturn && hunt.combat.isActive()) hunt.combat.stop();
    if (PathfindCommand.isNavigationActive()) {
      hunt.status = "Walking to Maddox";
      return;
    }
    if (hunt.pathRetryTicks > 0) {
      hunt.status = "Waiting for pathfinder (" + hunt.pathAttempts + "/" + MAX_PATH_ATTEMPTS + ")";
      return;
    }
    if (++hunt.pathAttempts > MAX_PATH_ATTEMPTS) {
      hunt.fail(
          nearMaddox ? "could not get within reach of Maddox" : "could not pathfind to Maddox");
      return;
    }
    hunt.pathRetryTicks = PATH_RETRY_TICKS;
    BlockPos goal = MADDOX_POSITION;
    if (nearMaddox && maddox != null) {
      var closer =
          MacroObstruction.findSidestep(
              mc.level, player, maddox.blockPosition(), MacroAim.entityLookPoint(maddox), maddox);
      if (closer != null) goal = closer;
    }
    if (PathfindCommand.startToQuiet(goal.getX(), goal.getY(), goal.getZ())) {
      hunt.status = "Pathfinding to Maddox";
    } else {
      hunt.status = "Waiting for pathfinder (" + hunt.pathAttempts + "/" + MAX_PATH_ATTEMPTS + ")";
    }
  }

  void tickMenuChoice(Minecraft mc, LocalPlayer player, String name, String action, State next) {
    final AbstractContainerMenu menu = MacroScreens.currentContainerMenu(mc);
    if (menu == null || !hunt.isServerMenuOpen(mc)) {
      if (hunt.stateTicks > MENU_TIMEOUT_TICKS) {
        hunt.fail(name + " menu was not available");
      } else {
        hunt.status = "Waiting for " + name;
      }
      return;
    }
    if (hunt.nextActionTicks > 0) {
      hunt.status = "Waiting to select " + name;
      return;
    }
    final Slot choice = RevLevel2MenuSlots.findMenuChoice(menu, player, name, action);
    if (choice == null) {
      if (hunt.stateTicks > MENU_TIMEOUT_TICKS) {
        hunt.fail(name + " was not found in Maddox's menu");
      } else {
        hunt.status = "Looking for " + name;
      }
      return;
    }
    MacroSlots.clickSlot(mc, menu, choice.index, 0, ContainerInput.PICKUP, player);
    hunt.setState(next, "Selected " + name);
    hunt.nextActionTicks = GuiActionTiming.firstMenuClickDelay();
  }

  void tickLevelReward(Minecraft mc, LocalPlayer player, String name, State next) {
    final AbstractContainerMenu menu = MacroScreens.currentContainerMenu(mc);
    if (menu == null || !hunt.isServerMenuOpen(mc)) {
      if (hunt.stateTicks > MENU_TIMEOUT_TICKS) {
        hunt.fail(name + " reward menu was not available");
      } else {
        hunt.status = "Waiting for " + name;
      }
      return;
    }
    if (hunt.nextActionTicks > 0) {
      hunt.status = "Waiting to claim " + name;
      return;
    }
    final Slot reward = RevLevel2MenuSlots.findMenuEntry(menu, player, name);
    if (reward == null) {
      if (hunt.stateTicks > MENU_TIMEOUT_TICKS) {
        hunt.fail(name + " was not found in Boss Leveling Rewards");
      } else {
        hunt.status = "Looking for " + name;
      }
      return;
    }
    final RevLevel2MenuSlots.RewardAction action =
        RevLevel2MenuSlots.rewardAction(reward.getItem(), name);
    if (action == RevLevel2MenuSlots.RewardAction.CLAIM) {
      MacroSlots.clickSlot(mc, menu, reward.index, 0, ContainerInput.PICKUP, player);
    }
    hunt.setState(
        next,
        action == RevLevel2MenuSlots.RewardAction.CLAIM
            ? "Claimed " + name
            : name + " was already claimed");
    hunt.nextActionTicks = GuiActionTiming.firstMenuClickDelay();
  }

  void tickWaitQuestStarted(Minecraft mc) {
    MacroInput.releaseMovementKeys(mc.options);
    if (slayerQuestStarted) {
      if (mc.gui.screen() != null && !MacroScreens.closeCurrentScreenIfCursorEmpty(mc)) {
        hunt.status = "Waiting for cursor to clear";
        return;
      }
      hunt.setState(State.RUN_SLAYER, "Slayer quest started");
      return;
    }
    if (hunt.stateTicks > NPC_MENU_TIMEOUT_TICKS) {
      hunt.fail("did not receive Slayer quest started chat");
      return;
    }
    hunt.status = "Waiting for Slayer quest started chat";
  }

  void tickSlayerCombat(Minecraft mc, LocalPlayer player) {
    if (!hunt.combat.isActive() && !hunt.combat.start(player)) {
      hunt.fail(hunt.combat.statusText());
      return;
    }
    final BlockPos fightSpot = hunt.combat.fightSpot(player);
    if (fightSpot != null) {
      slayerFightSpot = fightSpot;
    }
    final RevLevel2SlayerCombat.Result result = hunt.combat.tick(mc);
    if (result == RevLevel2SlayerCombat.Result.COMPLETE) {
      hunt.setState(State.PATH_TO_MADDOX_REWARD, "Revenant Horror I defeated");
    } else if (result == RevLevel2SlayerCombat.Result.FAILED) {
      hunt.fail(hunt.combat.statusText());
    } else {
      hunt.status = hunt.combat.statusText();
    }
  }

  void tickWaitDeathResult(Minecraft mc) {
    MacroInput.releaseMovementKeys(mc.options);
    if (hunt.stateTicks > DEATH_RESULT_WAIT_TICKS) {
      hunt.setState(
          RevLevel2Macro.recoveryStateAfterDeath(false),
          "Quest still active; returning to the zombies");
    } else {
      hunt.status = "Checking whether the quest failed";
    }
  }

  /** The quest survived the death: walk back and pick the kills up as soon as zombies are close. */
  void tickReturnToZombies(Minecraft mc, LocalPlayer player) {
    hunt.look().clear();
    final BlockPos spot = slayerFightSpot == null ? MADDOX_POSITION : slayerFightSpot;
    if (hunt.merchants.nearPosition(player, spot)
        || hunt.combat.zombieNearby(mc, player)
        || RevLevel2SlayerCombat.findBoss(mc) != null) {
      hunt.combat.stop();
      hunt.setState(State.RUN_SLAYER, "Back at the zombies");
      return;
    }
    if (!hunt.combat.isActive() && !hunt.combat.startZombieReturn(player, spot)) {
      hunt.fail(hunt.combat.statusText());
      return;
    }
    final RevLevel2SlayerCombat.Result result = hunt.combat.tick(mc);
    if (result == RevLevel2SlayerCombat.Result.FAILED) {
      hunt.fail(hunt.combat.statusText());
    } else {
      hunt.status = hunt.combat.statusText();
    }
  }
}
