package ryzn.macro.client.feature.macros.impl.goldshovel;

import static ryzn.macro.client.MacroInput.releaseAllKeys;
import static ryzn.macro.client.MacroInput.selectHotbarSlot;
import static ryzn.macro.client.feature.commands.impl.MacroCommands.sendCommand;
import static ryzn.macro.client.util.inventory.MacroSlots.clickSlot;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.macros.impl.goldshovel.GoldShovelMacro.State;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.util.inventory.MacroSlots;
import ryzn.macro.client.util.text.RyznChat;
import ryzn.macro.client.world.WorldChangeDetector;

/**
 * @description Owns experience purchases, throwing and crowd/lobby recovery.
 */
final class GoldShovelExperience {

  private final GoldShovelMacro hunt;

  GoldShovelExperience(GoldShovelMacro hunt) {
    this.hunt = hunt;
  }

  static final int STICK_TIMEOUT_TICKS = 20 * 15;

  static final int EXP_BOTTLE_SLOT_TIMEOUT_TICKS = 20 * 15;

  static final int TRADING_OPTIONS_TIMEOUT_TICKS = 20 * 15;

  static final int PURCHASE_TIMEOUT_TICKS = 20 * 15;

  static final int CLOSE_MENU_TIMEOUT_TICKS = 20 * 5;

  static final int EQUIP_EXP_TIMEOUT_TICKS = 20 * 20;

  static final int ROTATE_DOWN_TIMEOUT_TICKS = 20 * 8;

  static final int USE_EXP_TIMEOUT_TICKS = 20 * 30;

  /** The level both Efficiency V and Smite V cost. */
  static final int LEVEL_FIVE_TARGET_EXP_LEVEL = 30;

  static final int MAX_TRADING_OPTIONS_REOPENS = 6;

  /**
   * 192 bottles take well under a minute to throw; past this the throw itself is stuck, not slow.
   */
  static final int LEVEL_FIVE_USE_EXP_TIMEOUT_TICKS = 20 * 120;

  /**
   * Three stacks should clear level 30 from scratch; a second trip covers a bad roll, not a coin
   * sink.
   */
  static final int MAX_LEVEL_FIVE_SUPPLY_TRIPS = 2;

  /** Moves away from a persistent player allowed per lobby before the lobby is swapped out. */
  static final int MAX_EVASIONS = 5;

  static final int EVADE_TIMEOUT_TICKS = 20 * 20;

  static final double EVADE_ARRIVAL_DIST_SQ = 1.5 * 1.5;

  /** Farther than this from the Librarian after throwing, the body walks back before going on. */
  static final double RETURN_DIST_SQ = 4.0 * 4.0;

  static final String HUB_AREA_NEEDLE = "hub";

  static final int HOP_ISLAND_WAIT_TICKS = 100;

  static final int HOP_HUB_SETTLE_TICKS = 60;

  static final int HOP_HUB_RESEND_TICKS = 40;

  static final int HOP_GIVE_UP_TICKS = 20 * 180;

  int levelFiveSupplyTrips;

  final GoldShovelCrowdGuard crowdGuard = new GoldShovelCrowdGuard();

  /** Times this lobby a player came close enough to take thrown experience. */
  int evasions;

  BlockPos evadeTarget;

  /** Where to carry on once the body is back at the Librarian. */
  State returnNext = State.EQUIP_EXP_BOTTLES;

  int hubSettleTicks;

  final GoldShovelSupplySession supplySession = new GoldShovelSupplySession();

  void tickBuySticks(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);

    if (hunt.stateTicks > STICK_TIMEOUT_TICKS) {
      hunt.fail("Stick x32 timeout");
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)
        || !hunt.menuSlots.isLumberMerchantScreen(mc)) {
      hunt.fail("Lumber Merchant GUI closed");
      return;
    }
    if (hunt.actionTiming.waitForMenuClick()) {
      return;
    }
    if (supplySession.isStickClickRequested()) {
      hunt.setState(State.CLOSE_LUMBER_MENU);
      return;
    }
    if (mc.gameMode == null) {
      hunt.fail("Interaction manager missing");
      return;
    }

    final Slot stick = hunt.menuSlots.stickTrade(handled.getMenu(), player);
    if (stick == null) {
      hunt.status = "Searching for Stick x32";
      return;
    }
    clickSlot(mc, handled.getMenu(), stick.index, 0, ContainerInput.PICKUP, player);
    supplySession.markStickClickRequested();
    hunt.actionTiming.armMenuClick(GuiActionTiming.randomDelay());
    hunt.status = "Clicked Stick x32";
  }

  void tickCloseLumberMenu(Minecraft mc) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    if (hunt.stateTicks > CLOSE_MENU_TIMEOUT_TICKS && mc.gui.screen() != null) {
      hunt.fail("Lumber Merchant close timeout");
      return;
    }
    if (mc.gui.screen() != null) {
      hunt.closeCurrentScreen(mc);
      return;
    }
    supplySession.clearLumberGuiOpened();
    hunt.setState(State.PATHING_TO_LIBRARIAN);
  }

  void tickRightClickExpBottle(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);

    if (hunt.stateTicks > EXP_BOTTLE_SLOT_TIMEOUT_TICKS) {
      hunt.fail("Experience Bottle options timeout");
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)) {
      if (supplySession.isExpBottleOptionsRequested()) {
        return;
      }
      hunt.fail("Librarian GUI closed");
      return;
    }
    // Already on the options page (the axe leg comes back here between purchases): nothing to
    // request.
    // The x64 entry is the proof, not the title - Hypixel reuses a page and its name outlives its
    // slots.
    if (hunt.menuSlots.experienceBottleStackOption(handled.getMenu(), player) != null) {
      supplySession.markTradingOptionsOpened();
      hunt.setState(State.BUY_EXP_BOTTLE_STACK);
      return;
    }
    final Slot expBottle = hunt.menuSlots.experienceBottleTrade(handled.getMenu(), player);
    if (expBottle == null) {
      if (!hunt.menuSlots.isLibrarianScreen(mc)
          && !hunt.menuSlots.isTradingOptionsScreen(mc)
          && !supplySession.isExpBottleOptionsRequested()) {
        hunt.fail("Librarian GUI closed");
        return;
      }
      hunt.status = "Searching for Experience Bottle trade";
      return;
    }
    if (hunt.actionTiming.waitForMenuClick()) {
      return;
    }
    if (supplySession.isExpBottleOptionsRequested()) {
      return;
    }
    if (mc.gameMode == null) {
      hunt.fail("Interaction manager missing");
      return;
    }
    clickSlot(mc, handled.getMenu(), expBottle.index, 1, ContainerInput.PICKUP, player);
    supplySession.markExpBottleOptionsRequested();
    hunt.actionTiming.armMenuClick(GuiActionTiming.randomDelay());
    hunt.status = "Right-clicked Experience Bottle";
  }

  /**
   * Buys this visit's stacks. A purchase counts as landed when the bottles held rise by a stack, so
   * the axe leg's three buys need no assumption about what the menu does between them. Dropped
   * requests retry until the observed count reaches the goal or the state times out.
   */
  void tickBuyExpBottleStack(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);

    final int wanted = stacksThisVisit();
    final int bottles = hunt.menuSlots.countExperienceBottles(player);
    supplySession.captureExpBottleBaseline(bottles);
    final int bought = supplySession.expBottleStacksBought(bottles);
    if (bought >= wanted) {
      hunt.setState(State.CLOSE_LIBRARIAN_MENU);
      return;
    }
    if (hunt.stateTicks > purchaseTimeoutTicks(wanted)) {
      hunt.fail("Experience Bottle stack timeout");
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)) {
      waitForTradingOptions(mc, bought);
      return;
    }
    // The page stays open across purchases but Hypixel refills it in place, and mid-refill only
    // some
    // of its buy amounts are there. Wait the refill out: a click sent then hits the x1 entry
    // instead.
    final Slot option = hunt.menuSlots.experienceBottleStackOption(handled.getMenu(), player);
    if (option == null) {
      waitForTradingOptions(mc, bought);
      return;
    }
    supplySession.markTradingOptionsOpened();
    if (hunt.actionTiming.waitForMenuClick()
        || supplySession.isExpBottleStackClickRequested(bottles)) {
      return;
    }
    if (mc.gameMode == null) {
      hunt.fail("Interaction manager missing");
      return;
    }
    clickSlot(mc, handled.getMenu(), option.index, 0, ContainerInput.PICKUP, player);
    supplySession.markExpBottleStackClickRequested(bottles);
    hunt.actionTiming.armMenuClick(GuiActionTiming.randomDelay());
    hunt.status = "Bought Experience Bottle stack " + (bought + 1) + " of " + wanted;
  }

  /**
   * A purchase that took the page away is re-opened from whatever screen is left, not waited out.
   */
  void waitForTradingOptions(Minecraft mc, int bought) {
    final boolean librarian = hunt.menuSlots.isLibrarianScreen(mc);
    // Before the first purchase the page is simply still on its way; only a buy proves it can
    // vanish.
    if (bought <= 0 || (!librarian && mc.gui.screen() != null)) {
      hunt.status = "Waiting for Shop Trading Options";
      return;
    }
    if (supplySession.noteTradingOptionsReopen() > MAX_TRADING_OPTIONS_REOPENS) {
      hunt.fail("Shop Trading Options kept closing");
      return;
    }
    hunt.setState(librarian ? State.RIGHT_CLICK_EXP_BOTTLE : State.ROTATE_TO_LIBRARIAN);
  }

  int stacksThisVisit() {
    return hunt.target.experienceBottleStacks();
  }

  int purchaseTimeoutTicks(int stacks) {
    return TRADING_OPTIONS_TIMEOUT_TICKS + stacks * PURCHASE_TIMEOUT_TICKS;
  }

  void tickCloseLibrarianMenu(Minecraft mc) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    if (hunt.stateTicks > CLOSE_MENU_TIMEOUT_TICKS && mc.gui.screen() != null) {
      hunt.fail("Librarian close timeout");
      return;
    }
    if (mc.gui.screen() != null) {
      hunt.closeCurrentScreen(mc);
      return;
    }
    supplySession.clearLibrarianScreens();
    hunt.setState(State.EQUIP_EXP_BOTTLES);
  }

  void tickEquipExpBottles(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    if (evadeIfCrowded(mc, player)) {
      return;
    }

    if (hunt.stateTicks > EQUIP_EXP_TIMEOUT_TICKS) {
      hunt.fail("Experience Bottle equip timeout");
      return;
    }
    if (mc.gui.screen() != null) {
      hunt.closeCurrentScreen(mc);
      return;
    }

    // A hotbar swap is a click like any other: it waits out the same GUI action delay.
    if (hunt.actionTiming.waitForMenuClick()) {
      return;
    }

    final int hotbarSlot = MacroSlots.findHotbarSlot(player, hunt.menuSlots::isExperienceBottle);
    if (hotbarSlot >= 0) {
      selectHotbarSlot(player, hotbarSlot);
      hunt.setState(State.ROTATE_DOWN);
      return;
    }

    // the purchase lands server-side first, so let the state timeout be the only failure path
    final int inventorySlot =
        MacroSlots.findMainInventorySlot(player, hunt.menuSlots::isExperienceBottle);
    if (inventorySlot < 0) {
      hunt.status = "Waiting for Experience Bottles";
      return;
    }
    if (supplySession.isExpBottleSwapRequested()) {
      hunt.status = "Waiting for Experience Bottle swap";
      return;
    }
    int dest = MacroSlots.findFirstEmptyHotbarSlot(player);
    if (dest < 0) {
      dest = player.getInventory().getSelectedSlot();
    }
    if (!MacroSlots.swapInventoryToHotbar(mc, player, inventorySlot, dest)) {
      hunt.fail("Experience Bottle swap failed");
      return;
    }
    supplySession.markExpBottleSwapRequested();
    hunt.actionTiming.armMenuClick(GuiActionTiming.randomDelay());
    hunt.status = "Moving Experience Bottles to hotbar";
  }

  void tickRotateDown(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    if (mc.gui.screen() != null) {
      return;
    }
    if (evadeIfCrowded(mc, player)) {
      return;
    }

    if (hunt.stateTicks > ROTATE_DOWN_TIMEOUT_TICKS) {
      hunt.fail("Aim down timeout");
      return;
    }

    final int hotbarSlot = MacroSlots.findHotbarSlot(player, hunt.menuSlots::isExperienceBottle);
    if (hotbarSlot < 0) {
      hunt.status =
          "Waiting for Experience Bottles"; // hotbar swap is server-confirmed a tick later
      return;
    }
    selectHotbarSlot(player, hotbarSlot);

    if (hunt.look().aimDown(player)) {
      hunt.setState(State.THROW_EXP_BOTTLES);
    }
  }

  void tickThrowExpBottles(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    hunt.releaseMovementKeepUse(mc.options);
    hunt.look().down(); // keep the camera pinned straight down while throwing

    if (mc.gui.screen() != null) {
      hunt.setUse(mc.options, false);
      hunt.closeCurrentScreen(mc);
      return;
    }

    final boolean levelFiveLeg = hunt.target.requiredExperienceLevel() > 0;
    if (levelFiveLeg && player.experienceLevel >= hunt.target.requiredExperienceLevel()) {
      hunt.setUse(mc.options, false);
      leaveThrowSpot(player, State.MOVE_TARGET_TO_HOTBAR);
      return;
    }

    if (!hunt.menuSlots.isExperienceBottle(player.getMainHandItem())
        || !hunt.menuSlots.playerHasExperienceBottles(player)) {
      hunt.setUse(mc.options, false);
      advanceAfterThrowing(player);
      return;
    }

    // Checked before every throw: a bottle that lands beside someone else is their experience.
    if (evadeIfCrowded(mc, player)) {
      return;
    }

    hunt.setUse(mc.options, true);
    hunt.status =
        levelFiveLeg
            ? "Throwing Experience Bottles (level "
                + player.experienceLevel
                + " / "
                + hunt.target.requiredExperienceLevel()
                + ")"
            : "Throwing Experience Bottles";

    if (hunt.stateTicks
        > (levelFiveLeg ? LEVEL_FIVE_USE_EXP_TIMEOUT_TICKS : USE_EXP_TIMEOUT_TICKS)) {
      hunt.setUse(mc.options, false);
      if (levelFiveLeg) {
        hunt.fail("Experience Bottle throw stalled at level " + player.experienceLevel);
      } else {
        leaveThrowSpot(player, State.OPEN_CRAFT);
      }
    }
  }

  /**
   * A real player inside the guard radius: stop throwing and walk somewhere clear of everyone.
   * Returns true when the state changed, so the caller must stop for this tick.
   */
  boolean evadeIfCrowded(Minecraft mc, LocalPlayer player) {
    if (mc.level == null || crowdGuard.intruder(mc, mc.level, player) == null) {
      return false;
    }
    hunt.setUse(mc.options, false);
    beginEvade(mc, player);
    return true;
  }

  void beginEvade(Minecraft mc, LocalPlayer player) {
    WalkingNavigation.stopNavQuiet();
    if (++evasions > MAX_EVASIONS) {
      RyznChat.send("§bGold Shovel§7: a player keeps following; swapping lobbies.");
      hunt.setState(State.HOP_SEND_IS);
      return;
    }
    evadeTarget =
        crowdGuard.findRetreat(mc, mc.level, player, hunt.worldTargets.librarianPosition());
    if (evadeTarget == null) {
      // Boxed in with nowhere clear to stand: a fresh lobby is the only way out.
      hunt.setState(State.HOP_SEND_IS);
      return;
    }
    if (hunt.state == State.EVADE_PLAYER) {
      hunt.stateTicks = 0;
      hunt.actionTiming.enterState();
    } else {
      hunt.setState(State.EVADE_PLAYER);
    }
    hunt.status = "Player too close; moving away (" + evasions + "/" + MAX_EVASIONS + ")";
  }

  void tickEvadePlayer(Minecraft mc, LocalPlayer player, ClientLevel level) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    hunt.look().clear();
    if (mc.gui.screen() != null) {
      WalkingNavigation.stopNavQuiet();
      hunt.closeCurrentScreen(mc);
      return;
    }
    if (!hunt.actionTiming.isPathRequested()) {
      if (!WalkingNavigation.startToQuiet(
          evadeTarget.getX(), evadeTarget.getY(), evadeTarget.getZ())) {
        // No route to that spot: rule it out and pick another without counting it as a move.
        crowdGuard.reject(evadeTarget);
        evasions--;
        beginEvade(mc, player);
        return;
      }
      hunt.actionTiming.markPathRequested();
      return;
    }
    final boolean arrived =
        player.distanceToSqr(evadeTarget.getX() + 0.5, evadeTarget.getY(), evadeTarget.getZ() + 0.5)
            <= EVADE_ARRIVAL_DIST_SQ;
    final boolean stopped = !WalkingNavigation.isNavigationActive() && hunt.stateTicks > 20;
    if (!arrived && !stopped && hunt.stateTicks <= EVADE_TIMEOUT_TICKS) {
      return;
    }
    WalkingNavigation.stopNavQuiet();
    if (crowdGuard.intruder(mc, level, player) != null) {
      beginEvade(mc, player); // they followed: that is another move
      return;
    }
    hunt.setState(State.EQUIP_EXP_BOTTLES);
  }

  /**
   * Done throwing, or resuming a step away from the Librarian. If the body is not near it, walk
   * back first: the enchanting table is only searched for around where the body stands.
   */
  void leaveThrowSpot(LocalPlayer player, State next) {
    final BlockPos librarian = hunt.worldTargets.librarianPosition();
    final double dx = player.getX() - (librarian.getX() + 0.5);
    final double dz = player.getZ() - (librarian.getZ() + 0.5);
    if (dx * dx + dz * dz <= RETURN_DIST_SQ) {
      hunt.setState(next);
      return;
    }
    returnNext = next;
    hunt.setState(State.RETURN_TO_LIBRARIAN);
  }

  void tickHopSendIs(Minecraft mc) {
    releaseAllKeys(mc.options);
    WalkingNavigation.stopNavQuiet();
    hunt.look().clear();
    if (mc.gui.screen() != null) {
      hunt.closeCurrentScreen(mc);
      return;
    }
    RyznBootstrap.failsafeManager().expectWorldChange();
    sendCommand(mc, "is");
    hunt.setState(State.HOP_WAIT_IS);
  }

  void tickHopWaitIs(Minecraft mc) {
    releaseAllKeys(mc.options);
    if (++hunt.stateTicks >= HOP_ISLAND_WAIT_TICKS) {
      hunt.setState(State.HOP_SEND_HUB);
    }
  }

  void tickHopSendHub(Minecraft mc) {
    releaseAllKeys(mc.options);
    if (mc.gui.screen() != null) {
      hunt.closeCurrentScreen(mc);
      return;
    }
    RyznBootstrap.failsafeManager().expectWorldChange();
    sendCommand(mc, "hub");
    hubSettleTicks = 0;
    hunt.setState(State.HOP_SETTLE);
  }

  void tickHopSettle(Minecraft mc, LocalPlayer player) {
    hunt.stateTicks++;
    releaseAllKeys(mc.options);
    final String area = WorldChangeDetector.currentArea();
    if (area == null || !normalizeSearchText(area).contains(HUB_AREA_NEEDLE)) {
      hubSettleTicks = 0;
      if (hunt.stateTicks >= HOP_GIVE_UP_TICKS) {
        hunt.fail("Could not return to the Hub after swapping lobbies");
      } else if (hunt.stateTicks % HOP_HUB_RESEND_TICKS == 0) {
        RyznBootstrap.failsafeManager().expectWorldChange();
        sendCommand(mc, "hub");
      }
      return;
    }
    if (++hubSettleTicks < HOP_HUB_SETTLE_TICKS) {
      return;
    }
    // A new lobby: nobody has followed yet, and every cached NPC and table belongs to the old one.
    evasions = 0;
    crowdGuard.reset();
    hunt.worldTargets.reset();
    hunt.enchantSession.clearTable();
    if (hunt.menuSlots.playerHasExperienceBottles(player)) {
      returnNext = State.EQUIP_EXP_BOTTLES;
      hunt.setState(State.RETURN_TO_LIBRARIAN);
    } else {
      hunt.setState(State.PATHING_TO_LIBRARIAN);
    }
  }

  /**
   * Out of bottles in hand: take the next stack, buy another round, or move on with the shovel leg.
   */
  void advanceAfterThrowing(LocalPlayer player) {
    final boolean levelFiveLeg = hunt.target.requiredExperienceLevel() > 0;
    if (!levelFiveLeg) {
      leaveThrowSpot(player, State.OPEN_CRAFT);
      return;
    }
    if (hunt.menuSlots.playerHasExperienceBottles(player)) {
      hunt.setState(State.EQUIP_EXP_BOTTLES);
      return;
    }
    if (levelFiveSupplyTrips >= MAX_LEVEL_FIVE_SUPPLY_TRIPS) {
      hunt.fail(
          "Out of Experience Bottles at level "
              + player.experienceLevel
              + ", need "
              + LEVEL_FIVE_TARGET_EXP_LEVEL);
      return;
    }
    hunt.setState(State.PATHING_TO_LIBRARIAN);
  }
}
