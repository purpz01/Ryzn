package ryzn.macro.client.feature.macros.impl.level7;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.GoldShovelActionEvent;
import ryzn.macro.client.feature.macros.impl.goldshovel.GoldShovelMacro;
import ryzn.macro.client.feature.macros.impl.level7.RevLevel2Macro.State;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.util.gui.MacroScreens;
import ryzn.macro.client.util.gui.ScreenOwner;
import ryzn.macro.client.util.inventory.MacroSlots;

/**
 * @description Owns starter purchases, inventory moves, gear recovery and the Smite child leg.
 */
final class RevLevel2Gear {

  private final RevLevel2Macro hunt;

  RevLevel2Gear(RevLevel2Macro hunt) {
    this.hunt = hunt;
  }

  static final int MENU_TIMEOUT_TICKS = 20 * 12;

  static final int INVENTORY_TIMEOUT_TICKS = 20 * 10;

  static final int ARMOR_CLICK_RETRIES = 3;

  /** Times the gear leg may back out, close whatever is open and resume before the run fails. */
  static final int MAX_GEAR_RECOVERIES = 6;

  /** How long a purchase click gets to show up in the inventory before it is clicked again. */
  static final int PURCHASE_CONFIRM_TICKS = 60;

  static final int MAX_PURCHASE_CLICKS = 3;

  /** A recovery waits this long for a held item to be put down before closing the screen anyway. */
  static final int HELD_ITEM_CLOSE_TICKS = 40;

  /** A merchant menu may blink shut for this long while Hypixel swaps one menu for another. */
  static final int MENU_GONE_GRACE_TICKS = 20;

  static final int ARMOR_RETRY_BACKOFF_TICKS = 2;

  int armorPieceIndex;

  boolean purchaseRequested;

  int purchaseClicks;

  int purchaseWaitTicks;

  int gearRecoveries;

  int inventoryMovePhase;

  int inventoryMoveSource = -1;

  int inventoryMoveDestination = -1;

  ItemStack inventoryMoveStack = ItemStack.EMPTY;

  ItemStack inventoryMoveReplacedStack = ItemStack.EMPTY;

  int inventoryMoveRetries;

  void tickBuySword(Minecraft mc, LocalPlayer player) {
    if (hasUndeadSword(player)) {
      hunt.setState(State.CLOSE_WEAPONSMITH, "Undead Sword confirmed");
      return;
    }
    final AbstractContainerMenu menu = MacroScreens.currentContainerMenu(mc);
    if (menu == null || !hunt.isServerMenuOpen(mc)) {
      if (++hunt.menuGoneTicks > MENU_GONE_GRACE_TICKS) {
        recoverGear("Weaponsmith menu closed before the sword was bought");
      } else {
        hunt.status = "Waiting for Weaponsmith menu";
      }
      return;
    }
    hunt.menuGoneTicks = 0;
    if (!purchaseStillPending("Undead Sword")) {
      return;
    }
    if (hunt.nextActionTicks > 0) {
      hunt.status = "Waiting for Undead Sword purchase";
      return;
    }
    final Slot sword =
        RevLevel2MenuSlots.findOffer(menu, player, RevLevel2MenuSlots::isUndeadSword);
    if (sword == null) {
      if (hunt.stateTicks > MENU_TIMEOUT_TICKS) {
        recoverGear("Undead Sword was not found in Weaponsmith");
      } else {
        hunt.status = "Looking for Undead Sword";
      }
      return;
    }
    MacroSlots.clickSlot(mc, menu, sword.index, 0, ContainerInput.PICKUP, player);
    notePurchaseClick();
    hunt.status = "Buying Undead Sword";
  }

  /**
   * One purchase click, then wait for the inventory to show the item. Returns true once another
   * click is due: the first one, or a repeat because the last click was never confirmed (a menu
   * that reopened underneath it swallows the click). After the allowed clicks the gear leg backs
   * out and reopens the merchant.
   */
  boolean purchaseStillPending(String item) {
    if (!purchaseRequested) {
      return true;
    }
    if (++purchaseWaitTicks < PURCHASE_CONFIRM_TICKS) {
      hunt.status = "Waiting for " + item + " purchase";
      return false;
    }
    purchaseRequested = false;
    if (purchaseClicks >= MAX_PURCHASE_CLICKS) {
      recoverGear(item + " purchase was not confirmed");
      return false;
    }
    return true;
  }

  void notePurchaseClick() {
    purchaseRequested = true;
    purchaseClicks++;
    purchaseWaitTicks = 0;
    hunt.nextActionTicks = GuiActionTiming.randomDelayAtLeast(5);
  }

  void tickMoveSwordToHotbar(Minecraft mc, LocalPlayer player, State next) {
    if (RevLevel2MenuSlots.findInventoryItem(
            player, RevLevel2MenuSlots::isUndeadSword, 0, MacroSlots.RESERVED_HOTBAR_SLOT)
        >= 0) {
      closeMacroInventory(mc, next, "Undead Sword is in hotbar");
      return;
    }
    final int source =
        RevLevel2MenuSlots.findInventoryItem(player, RevLevel2MenuSlots::isUndeadSword, 0, 36);
    if (source < 0) {
      if (hunt.stateTicks > INVENTORY_TIMEOUT_TICKS) {
        gearFailure("Undead Sword disappeared from inventory");
      } else {
        hunt.status = "Waiting for Undead Sword inventory sync";
      }
      return;
    }
    if (!ensureMacroInventory(mc, player)) {
      return;
    }
    if (hunt.nextActionTicks > 0) {
      hunt.status = "Waiting to move Undead Sword";
      return;
    }
    if (hunt.stateTicks > 2 * INVENTORY_TIMEOUT_TICKS) {
      gearFailure("could not move Undead Sword to hotbar");
      return;
    }
    int destination = MacroSlots.findFirstEmptyHotbarSlot(player);
    if (destination < 0) {
      destination =
          MacroSlots.isUsableHotbarSlot(player.getInventory().getSelectedSlot())
              ? player.getInventory().getSelectedSlot()
              : 0;
    }
    if (!MacroSlots.swapInventoryToHotbar(mc, player, source, destination)) {
      gearFailure("could not move Undead Sword to hotbar");
      return;
    }
    hunt.nextActionTicks = GuiActionTiming.randomDelayAtLeast(5);
    hunt.status = "Moving Undead Sword to hotbar";
  }

  void tickOpenIronArmor(Minecraft mc, LocalPlayer player) {
    final AbstractContainerMenu menu = MacroScreens.currentContainerMenu(mc);
    if (menu != null && hasIronArmorOffer(menu, player)) {
      hunt.setState(State.BUY_IRON_ARMOR, "Opened Iron Armor");
      hunt.nextActionTicks = GuiActionTiming.firstMenuClickDelay();
      return;
    }
    if (menu == null || !hunt.isServerMenuOpen(mc)) {
      if (++hunt.menuGoneTicks > MENU_GONE_GRACE_TICKS) {
        recoverGear("Rosetta menu closed before Iron Armor opened");
      } else {
        hunt.status = "Waiting for Rosetta menu";
      }
      return;
    }
    hunt.menuGoneTicks = 0;
    if (hunt.nextActionTicks > 0) {
      hunt.status = "Waiting to open Iron Armor";
      return;
    }
    final Slot ironArmor =
        RevLevel2MenuSlots.findOffer(menu, player, RevLevel2MenuSlots::isIronArmor);
    if (ironArmor == null) {
      if (hunt.stateTicks > MENU_TIMEOUT_TICKS) {
        recoverGear("Iron Armor was not found in Rosetta");
      } else {
        hunt.status = "Looking for Iron Armor";
      }
      return;
    }
    MacroSlots.clickSlot(mc, menu, ironArmor.index, 0, ContainerInput.PICKUP, player);
    hunt.nextActionTicks = GuiActionTiming.firstMenuClickDelay();
    hunt.status = "Opening Iron Armor";
  }

  void tickBuyArmor(Minecraft mc, LocalPlayer player) {
    if (!hunt.isServerMenuOpen(mc)) {
      if (++hunt.menuGoneTicks > MENU_GONE_GRACE_TICKS) {
        recoverGear("Iron Armor menu closed before purchases finished");
      } else {
        hunt.status = "Waiting for Iron Armor menu";
      }
      return;
    }
    hunt.menuGoneTicks = 0;
    final RevLevel2MenuSlots.ArmorPiece[] pieces = RevLevel2MenuSlots.ArmorPiece.values();
    while (armorPieceIndex < pieces.length && ownsArmorPiece(player, pieces[armorPieceIndex])) {
      armorPieceIndex++;
      purchaseRequested = false;
      purchaseClicks = 0;
      hunt.stateTicks = 0;
    }
    if (armorPieceIndex >= pieces.length) {
      hunt.setState(State.CLOSE_ROSETTA, "Iron Armor confirmed");
      return;
    }
    final AbstractContainerMenu menu = MacroScreens.currentContainerMenu(mc);
    if (RevLevel2MenuSlots.findOffer(menu, player, RevLevel2MenuSlots::isIronArmor) != null) {
      // Rosetta's late second menu (or a bounced purchase click) lands back on the Starter Gear
      // root. Whatever was clicked into the old menu is gone, so open Iron Armor and buy again.
      hunt.setState(State.OPEN_IRON_ARMOR, "Back on Rosetta's menu; reopening Iron Armor");
      hunt.nextActionTicks = GuiActionTiming.firstMenuClickDelay();
      return;
    }
    final RevLevel2MenuSlots.ArmorPiece piece = pieces[armorPieceIndex];
    final String pieceName = piece.name().toLowerCase();
    if (!purchaseStillPending(pieceName)) {
      return;
    }
    if (hunt.nextActionTicks > 0) {
      hunt.status = "Waiting to buy " + pieceName;
      return;
    }
    final Slot offer = RevLevel2MenuSlots.findOffer(menu, player, piece::matches);
    if (offer == null) {
      if (hunt.stateTicks > MENU_TIMEOUT_TICKS) {
        recoverGear(pieceName + " was not found in Iron Armor");
      } else {
        hunt.status = "Looking for " + pieceName;
      }
      return;
    }
    MacroSlots.clickSlot(mc, menu, offer.index, 0, ContainerInput.PICKUP, player);
    notePurchaseClick();
    hunt.status = "Buying " + pieceName;
  }

  void tickEquipArmor(Minecraft mc, LocalPlayer player) {
    final RevLevel2MenuSlots.ArmorPiece[] pieces = RevLevel2MenuSlots.ArmorPiece.values();
    while (armorPieceIndex < pieces.length
        && pieces[armorPieceIndex].matches(
            player.getInventory().getItem(pieces[armorPieceIndex].inventoryIndex))) {
      armorPieceIndex++;
      clearInventoryMove();
      hunt.stateTicks = 0;
    }
    if (armorPieceIndex >= pieces.length) {
      closeMacroInventory(mc, State.START_UNDEAD_SWORD_SMITE, "Iron Armor equipped");
      return;
    }
    final RevLevel2MenuSlots.ArmorPiece piece = pieces[armorPieceIndex];
    if (hunt.isServerMenuOpen(mc)) {
      // Rosetta's menu came back on top of the inventory. The move in flight died with the
      // inventory screen, so drop it, shut the menu and start this piece again.
      clearInventoryMove();
      if (hunt.stateTicks > INVENTORY_TIMEOUT_TICKS) {
        recoverGear("Rosetta's menu kept reopening while equipping");
      } else if (MacroScreens.closeCurrentScreenIfCursorEmpty(mc)) {
        hunt.nextActionTicks = GuiActionTiming.firstMenuClickDelay();
        hunt.status = "Rosetta's menu reopened; closed it";
      } else {
        hunt.status = "Rosetta's menu reopened; waiting for the cursor to clear";
      }
      return;
    }
    if (!ensureMacroInventory(mc, player)) {
      return;
    }
    final AbstractContainerMenu menu = player.inventoryMenu;
    if (inventoryMovePhase == 0) {
      final int source = RevLevel2MenuSlots.findInventoryItem(player, piece::matches, 0, 36);
      if (source < 0) {
        // Often just mid-sync after a menu swap put it back; if it is really gone, buy it again.
        if (hunt.stateTicks > INVENTORY_TIMEOUT_TICKS) {
          recoverGear("missing " + piece.name().toLowerCase() + " to equip");
        } else {
          hunt.status = "Waiting for " + piece.name().toLowerCase() + " in inventory";
        }
        return;
      }
      if (hunt.nextActionTicks > 0) {
        hunt.status = "Preparing " + piece.name().toLowerCase();
        return;
      }
      final Slot sourceSlot = MacroSlots.findPlayerInventoryMenuSlot(menu, player, source);
      final Slot destinationSlot =
          MacroSlots.findPlayerInventoryMenuSlot(menu, player, piece.inventoryIndex);
      if (sourceSlot == null
          || destinationSlot == null
          || !sourceSlot.mayPickup(player)
          || !destinationSlot.mayPlace(sourceSlot.getItem())) {
        recoverGear(piece.name().toLowerCase() + " cannot be placed in its armor slot");
        return;
      }
      inventoryMoveSource = source;
      inventoryMoveDestination = piece.inventoryIndex;
      inventoryMoveStack = sourceSlot.getItem().copy();
      inventoryMoveReplacedStack = destinationSlot.getItem().copy();
      inventoryMoveRetries = 0;
      clickInventorySlot(mc, menu, sourceSlot, player);
      inventoryMovePhase = 1;
      hunt.status = "Equipping " + piece.name().toLowerCase();
      return;
    }
    tickInventoryMove(mc, player, piece);
  }

  void tickInventoryMove(Minecraft mc, LocalPlayer player, RevLevel2MenuSlots.ArmorPiece piece) {
    if (hunt.nextActionTicks > 0) {
      return;
    }
    if (hunt.stateTicks > INVENTORY_TIMEOUT_TICKS) {
      recoverGear("timed out equipping " + piece.name().toLowerCase());
      return;
    }
    final AbstractContainerMenu menu = player.inventoryMenu;
    final Slot source = MacroSlots.findPlayerInventoryMenuSlot(menu, player, inventoryMoveSource);
    final Slot destination =
        MacroSlots.findPlayerInventoryMenuSlot(menu, player, inventoryMoveDestination);
    if (source == null || destination == null) {
      recoverGear("inventory changed while equipping " + piece.name().toLowerCase());
      return;
    }
    if (inventoryMovePhase == 1) {
      if (!sameItem(menu.getCarried(), inventoryMoveStack) || !source.getItem().isEmpty()) {
        if (sourceClickRolledBack(menu, source) && retryInventoryClick(mc, menu, source, player)) {
          return;
        }
        restartInventoryMove(piece, "pick up");
        return;
      }
      inventoryMoveRetries = 0;
      clickInventorySlot(mc, menu, destination, player);
      inventoryMovePhase = 2;
      return;
    }
    if (inventoryMovePhase == 2) {
      if (!piece.matches(destination.getItem())) {
        if (destinationClickRolledBack(menu, source, destination)
            && retryInventoryClick(mc, menu, destination, player)) {
          return;
        }
        restartInventoryMove(piece, "equip");
        return;
      }
      if (menu.getCarried().isEmpty()) {
        clearInventoryMove();
        return;
      }
      if (!sameOrBothEmpty(menu.getCarried(), inventoryMoveReplacedStack)
          || !source.getItem().isEmpty()) {
        recoverGear("inventory changed while equipping " + piece.name().toLowerCase());
        return;
      }
      inventoryMoveRetries = 0;
      clickInventorySlot(mc, menu, source, player);
      inventoryMovePhase = 3;
      return;
    }
    if (!menu.getCarried().isEmpty()
        || !sameOrBothEmpty(source.getItem(), inventoryMoveReplacedStack)) {
      if (finalClickRolledBack(menu, source, destination)
          && retryInventoryClick(mc, menu, source, player)) {
        return;
      }
      restartInventoryMove(piece, "put back the swapped");
      return;
    }
    clearInventoryMove();
  }

  /**
   * A cursor move only makes sense against the inventory it started from, and a server menu opening
   * on top of ours ends it mid-flight: the screen is swapped out and the carried item goes back to
   * whatever slot the server picks. Failing the whole run there threw away a recovery the state
   * already has - phase 0 re-finds the piece wherever it now sits and moves it again. The state
   * timeout still bounds the retry.
   */
  void restartInventoryMove(RevLevel2MenuSlots.ArmorPiece piece, String step) {
    clearInventoryMove();
    hunt.status = "Retrying the " + piece.name().toLowerCase() + " " + step;
  }

  boolean sourceClickRolledBack(AbstractContainerMenu menu, Slot source) {
    return menu.getCarried().isEmpty() && sameItem(source.getItem(), inventoryMoveStack);
  }

  boolean destinationClickRolledBack(AbstractContainerMenu menu, Slot source, Slot destination) {
    return source.getItem().isEmpty()
        && sameItem(menu.getCarried(), inventoryMoveStack)
        && sameOrBothEmpty(destination.getItem(), inventoryMoveReplacedStack);
  }

  boolean finalClickRolledBack(AbstractContainerMenu menu, Slot source, Slot destination) {
    return source.getItem().isEmpty()
        && sameOrBothEmpty(menu.getCarried(), inventoryMoveReplacedStack)
        && sameItem(destination.getItem(), inventoryMoveStack);
  }

  boolean retryInventoryClick(
      Minecraft mc, AbstractContainerMenu menu, Slot slot, LocalPlayer player) {
    if (inventoryMoveRetries >= ARMOR_CLICK_RETRIES) {
      return false;
    }
    inventoryMoveRetries++;
    clickInventorySlot(mc, menu, slot, player);
    return true;
  }

  void clickInventorySlot(Minecraft mc, AbstractContainerMenu menu, Slot slot, LocalPlayer player) {
    MacroSlots.clickSlot(mc, menu, slot.index, 0, ContainerInput.PICKUP, player);
    hunt.nextActionTicks =
        GuiActionTiming.randomDelayAtLeast(4 + inventoryMoveRetries * ARMOR_RETRY_BACKOFF_TICKS);
  }

  boolean ensureMacroInventory(Minecraft mc, LocalPlayer player) {
    if (mc.gui.screen() instanceof InventoryScreen) {
      return true;
    }
    if (mc.gui.screen() != null) {
      if (!MacroScreens.closeCurrentScreenIfCursorEmpty(mc)) {
        hunt.status = "Waiting for cursor to clear";
        if (hunt.stateTicks > INVENTORY_TIMEOUT_TICKS) {
          gearFailure("put down the item on your cursor first");
        }
      }
      return false;
    }
    ScreenOwner.macroOpenedInventory();
    mc.gui.setScreen(new InventoryScreen(player));
    hunt.nextActionTicks = GuiActionTiming.firstMenuClickDelay();
    hunt.status = "Opening inventory";
    return false;
  }

  /** Buying and equipping can be redone from the inventory; anything after that cannot. */
  boolean inGearLeg() {
    return hunt.state.ordinal() >= State.PATH_TO_WEAPONSMITH.ordinal()
        && hunt.state.ordinal() <= State.EQUIP_IRON_ARMOR.ordinal()
        && hunt.state != State.RETURN_ENCHANTED_SWORD_TO_HOTBAR;
  }

  void gearFailure(String reason) {
    if (inGearLeg()) {
      recoverGear(reason);
    } else {
      hunt.fail(reason);
    }
  }

  /**
   * Something in the buy / equip leg went wrong - a menu reopened or closed underneath a click, a
   * purchase never arrived, an item moved. Nothing is lost that the inventory cannot show, so close
   * whatever is open and pick the leg back up from what the player now holds. Bounded, so a real
   * blocker (no coins, full inventory) still ends the run with its reason.
   */
  void recoverGear(String reason) {
    if (++gearRecoveries > MAX_GEAR_RECOVERIES) {
      hunt.fail(reason);
      return;
    }
    clearInventoryMove();
    hunt.setState(
        State.RECOVER_GEAR,
        "Recovering (" + gearRecoveries + "/" + MAX_GEAR_RECOVERIES + "): " + reason);
  }

  void tickRecoverGear(Minecraft mc, LocalPlayer player) {
    MacroInput.releaseMovementKeys(mc.options);
    if (mc.gui.screen() != null) {
      if (MacroScreens.closeCurrentScreenIfCursorEmpty(mc)) {
        hunt.nextActionTicks = GuiActionTiming.firstMenuClickDelay();
      } else if (hunt.stateTicks > INVENTORY_TIMEOUT_TICKS) {
        hunt.fail("could not clear the open screen to recover");
      } else if (hunt.stateTicks > HELD_ITEM_CLOSE_TICKS) {
        // Closing hands the held item back to the inventory server-side.
        MacroScreens.closeCurrentScreen(mc);
      }
      return;
    }
    if (hunt.nextActionTicks > 0) {
      return;
    }
    final State resume = gearResumeState(player);
    hunt.setState(resume, "Resuming: " + resume.label);
    hunt.pathAttempts = 0;
  }

  /** The earliest gear step the inventory says is still unfinished. */
  State gearResumeState(LocalPlayer player) {
    if (!hasUndeadSword(player)) {
      return State.PATH_TO_WEAPONSMITH;
    }
    if (RevLevel2MenuSlots.findInventoryItem(
            player, RevLevel2MenuSlots::isUndeadSword, 0, MacroSlots.RESERVED_HOTBAR_SLOT)
        < 0) {
      return State.MOVE_SWORD_TO_HOTBAR;
    }
    for (RevLevel2MenuSlots.ArmorPiece piece : RevLevel2MenuSlots.ArmorPiece.values()) {
      if (!ownsArmorPiece(player, piece)) {
        return State.PATH_TO_ROSETTA;
      }
    }
    return State.EQUIP_IRON_ARMOR;
  }

  void closeMacroInventory(Minecraft mc, State next, String nextStatus) {
    if (mc.gui.screen() == null || MacroScreens.closeCurrentScreenIfCursorEmpty(mc)) {
      hunt.setState(next, nextStatus);
      return;
    }
    hunt.status = "Waiting for cursor to clear";
  }

  void tickStartUndeadSwordSmite(Minecraft mc) {
    MacroInput.releaseAllKeys(mc.options);
    if (RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).isEnabled()) {
      hunt.fail("another Gold Shovel task is already running");
      return;
    }
    final GoldShovelActionEvent smite = GoldShovelActionEvent.startUndeadSwordSmite();
    RyznBootstrap.eventBus().post(smite);
    if (!smite.started()) {
      hunt.fail("could not start Undead Sword Smite V task");
      return;
    }
    hunt.setState(State.WAIT_UNDEAD_SWORD_SMITE, "Buying bottles and enchanting Undead Sword");
  }

  /**
   * Watch the Gold Shovel child, and keep hands off the keys while it runs. Releasing them every
   * tick here fought its pathfinder to a standstill: the executor held W, this cleared it, and the
   * body sat at speed 0.0000 with no collision in front of it for 43 seconds while the wall-grind
   * failsafe hammered hops at nothing (log 2026-09-02 11:28:20). Whoever owns the body owns its
   * keys.
   */
  void tickAwaitUndeadSwordSmite(Minecraft mc) {
    if (RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).isEnabled()) {
      hunt.status =
          "Enchanting Undead Sword: "
              + RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).statusText();
      return;
    }
    MacroInput.releaseAllKeys(mc.options);
    if (!RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).wasLastRunCompleted()) {
      hunt.fail(
          "Undead Sword Smite V task stopped: "
              + RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).lastStopReason());
      return;
    }
    hunt.setState(
        RevLevel2Macro.stateAfterSmiteCompletion(), "Returning enchanted Undead Sword to hotbar");
  }

  boolean hasIronArmorOffer(AbstractContainerMenu menu, LocalPlayer player) {
    for (RevLevel2MenuSlots.ArmorPiece piece : RevLevel2MenuSlots.ArmorPiece.values()) {
      if (RevLevel2MenuSlots.findOffer(menu, player, piece::matches) != null) {
        return true;
      }
    }
    return false;
  }

  boolean hasUndeadSword(LocalPlayer player) {
    return RevLevel2MenuSlots.findInventoryItem(player, RevLevel2MenuSlots::isUndeadSword, 0, 40)
        >= 0;
  }

  boolean ownsArmorPiece(LocalPlayer player, RevLevel2MenuSlots.ArmorPiece piece) {
    return RevLevel2MenuSlots.findInventoryItem(player, piece::matches, 0, 40) >= 0;
  }

  boolean sameItem(ItemStack first, ItemStack second) {
    return !first.isEmpty()
        && !second.isEmpty()
        && ItemStack.isSameItemSameComponents(first, second);
  }

  boolean sameOrBothEmpty(ItemStack first, ItemStack second) {
    return first.isEmpty() ? second.isEmpty() : sameItem(first, second);
  }

  void clearInventoryMove() {
    inventoryMovePhase = 0;
    inventoryMoveSource = -1;
    inventoryMoveDestination = -1;
    inventoryMoveStack = ItemStack.EMPTY;
    inventoryMoveReplacedStack = ItemStack.EMPTY;
    inventoryMoveRetries = 0;
  }
}
