package ryzn.macro.client.feature.macros.impl.wheat;

import static ryzn.macro.client.MacroInput.setUseHeld;
import static ryzn.macro.client.MacroInput.setUseHeldOn;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.entityLookPoint;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.isLookWithin;
import static ryzn.macro.client.util.entity.MacroEntities.entityNameLower;
import static ryzn.macro.client.util.entity.MacroEntities.findNearestNamedEntity;
import static ryzn.macro.client.util.entity.MacroEntities.isNearBlockCenter;
import static ryzn.macro.client.util.inventory.MacroSlots.clickSlot;
import static ryzn.macro.client.util.text.MacroText.stripFormatting;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.input.ClickConfirm;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.util.world.MacroObstruction;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class WheatMerchantActions {

  enum InteractOutcome {
    WAITING,
    OPENED,
    NEED_PATH,
    SIDESTEP,
    OPEN_TIMEOUT
  }

  enum BuyOutcome {
    WAITING,
    RETRY_INTERACT,
    COMPLETE,
    HOE_NOT_FOUND,
    PURCHASE_NOT_CONFIRMED
  }

  enum SellOutcome {
    WAITING,
    REOPEN,
    COMPLETE,
    GUI_CLOSED,
    TIMED_OUT
  }

  static final BlockPos POSITION = new BlockPos(62, 72, -113);

  private static final double SEARCH_RADIUS = 8.0;
  private static final double ARRIVE_RADIUS_SQ = 1.25 * 1.25;
  private static final int INTERACT_TIMEOUT_TICKS = 20 * 25;
  private static final int PURCHASE_TIMEOUT_TICKS = 20 * 20;
  private static final int SELL_TIMEOUT_TICKS = 20 * 45;
  private static final int MAX_REOPEN_ATTEMPTS = 3;
  private static final int CLICK_COOLDOWN_TICKS = 10;

  /** How long a sent hoe purchase may take to arrive before the click is re-sent. */
  private static final int POST_BUY_CONFIRM_TICKS = 40;

  private final float[] desiredAim = new float[2];
  private final WheatInventory inventory;
  private final WheatMerchantActionTiming timing = new WheatMerchantActionTiming();
  private final ClickConfirm purchaseConfirm = new ClickConfirm();
  private Entity merchantEntity;
  private boolean purchaseClicked;
  private int blockedTicks;
  private BlockPos sidestepTarget;

  void reset() {
    merchantEntity = null;
    purchaseClicked = false;
    purchaseConfirm.clear();
    clearBlocked();
    timing.reset();
  }

  void enterInteraction() {
    merchantEntity = null;
    clearBlocked();
    timing.enterInteraction();
  }

  /** Where the last {@link InteractOutcome#SIDESTEP} wants the bot to stand. */
  BlockPos sidestepTarget() {
    return sidestepTarget;
  }

  static boolean isInteractTimedOut(int phaseTicks) {
    return phaseTicks > INTERACT_TIMEOUT_TICKS;
  }

  private void clearBlocked() {
    blockedTicks = 0;
    sidestepTarget = null;
  }

  void enterMenu() {
    timing.enterMenu();
  }

  void preparePurchase() {
    purchaseClicked = false;
    purchaseConfirm.clear();
  }

  void resetReopens() {
    timing.resetReopens();
  }

  static boolean isNearMerchant(LocalPlayer player) {
    return isNearMerchant(player.getX(), player.getY(), player.getZ());
  }

  static boolean isNearMerchant(double x, double y, double z) {
    double dx = POSITION.getX() + 0.5 - x;
    double dz = POSITION.getZ() + 0.5 - z;
    return dx * dx + dz * dz <= ARRIVE_RADIUS_SQ && Math.abs(y - POSITION.getY()) <= 3.0;
  }

  InteractOutcome tickInteract(
      Minecraft mc,
      LocalPlayer player,
      ClientLevel level,
      WheatLookMotion lookMotion,
      int phaseTicks) {
    // Keep asking until the phase times out - there is no attempt cap. Giving up early was what let
    // the
    // failsafe kill a run: the merchant answered the clicks with dialogue, opened its shop seconds
    // after
    // the macro had already quit, and a menu nobody was waiting for is exactly what
    // UNEXPECTED_SCREEN
    // stops for. Clicking on also re-stamps the ScreenOwner claim, so the menu is owned when it
    // lands.
    //
    // One tick of key-down per click, never a latch: a held use key auto-repeats the interact about
    // five
    // times a second, which Hypixel answered with dialogue and no shop at all - it only opened once
    // that
    // spam stopped.
    setUseHeld(mc.options, false);
    if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
      return InteractOutcome.OPENED;
    }
    if (isInteractTimedOut(phaseTicks)) {
      return InteractOutcome.OPEN_TIMEOUT;
    }
    Entity npc = updateMerchant(level);
    if (npc == null) {
      if (!isNearMerchant(player)) {
        return InteractOutcome.NEED_PATH;
      }
      lookMotion.setTarget(
          new Vec3(POSITION.getX() + 0.5, POSITION.getY() + 1.5, POSITION.getZ() + 0.5));
      return InteractOutcome.WAITING;
    }
    Vec3 lookTarget = entityLookPoint(npc);
    lookMotion.setTarget(lookTarget);
    if (!isLookWithin(player, lookTarget, 3.0f, desiredAim)) {
      return InteractOutcome.WAITING;
    }
    // Interaction norm: click from at most 3 blocks, with the crosshair on the merchant itself and
    // nobody stacked on the bot. Out of reach counts as blocked too, so one path handles both and
    // a stranger standing in the way earns a sidestep instead of a stare-off.
    if (!MacroObstruction.isWithinInteract(player, npc)
        || !MacroInput.isCrosshairOn(mc, npc)
        || MacroObstruction.isPlayerStackedOn(level, player)) {
      setUseHeld(mc.options, false);
      return tickBlocked(level, player, npc, lookTarget);
    }
    blockedTicks = 0;
    if (timing.waitForNpcClick()) {
      return InteractOutcome.WAITING;
    }
    if (mc.options != null) {
      // Exact-intent press: only fires while the crosshair pick is the merchant, so the click can
      // never open a passing player's profile menu instead of the shop.
      setUseHeldOn(mc.options, true, npc);
      timing.recordNpcClick(CLICK_COOLDOWN_TICKS);
      DebugLog.trace(
          DebugLog.Category.MACRO,
          "Wheat: clicking the Farm Merchant (try %d)",
          timing.npcClickAttempts());
    }
    return InteractOutcome.WAITING;
  }

  /** Wait out a brief block; a lasting one moves the bot to a tile that can see the merchant. */
  private InteractOutcome tickBlocked(ClientLevel level, LocalPlayer player, Entity npc, Vec3 aim) {
    if (++blockedTicks < MacroObstruction.BLOCKED_RELOCATE_TICKS) {
      return InteractOutcome.WAITING;
    }
    blockedTicks = 0;
    sidestepTarget = MacroObstruction.findSidestep(level, player, npc.blockPosition(), aim, npc);
    if (sidestepTarget != null) {
      return InteractOutcome.SIDESTEP;
    }
    return isNearMerchant(player) ? InteractOutcome.WAITING : InteractOutcome.NEED_PATH;
  }

  /**
   * Buying is done when the hoe is in the inventory, never when the click was sent. A buy Hypixel
   * dropped used to leave this waiting out the whole phase for a hoe that was never bought; now the
   * click is re-sent once its delivery window lapses, and the confirm window stops it paying twice
   * in the meantime.
   */
  BuyOutcome tickPurchase(Minecraft mc, LocalPlayer player, int phaseTicks) {
    if (inventory.hasHoe(player)) {
      return BuyOutcome.COMPLETE;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)) {
      return phaseTicks > PURCHASE_TIMEOUT_TICKS ? BuyOutcome.RETRY_INTERACT : BuyOutcome.WAITING;
    }
    AbstractContainerMenu menu = handled.getMenu();
    if (timing.waitForMenuClick()) {
      return BuyOutcome.WAITING;
    }
    if (purchaseConfirm.awaiting(0)) {
      return BuyOutcome.WAITING;
    }
    if (phaseTicks > PURCHASE_TIMEOUT_TICKS) {
      if (!purchaseClicked) {
        DebugLog.trace(
            DebugLog.Category.MACRO,
            "Wheat: the Farm Merchant is not selling a hoe. It offers: %s",
            inventory.containerOffers(menu, player));
      }
      return purchaseClicked ? BuyOutcome.PURCHASE_NOT_CONFIRMED : BuyOutcome.HOE_NOT_FOUND;
    }
    Slot hoe = inventory.containerHoeSlot(menu, player);
    if (hoe == null) {
      return BuyOutcome.WAITING;
    }
    DebugLog.trace(
        DebugLog.Category.MACRO,
        "Wheat: buying %s (slot %d)",
        stripFormatting(hoe.getItem().getHoverName().getString()),
        hoe.index);
    clickSlot(mc, menu, hoe.index, 0, ContainerInput.PICKUP, player);
    purchaseClicked = true;
    purchaseConfirm.sent(0, POST_BUY_CONFIRM_TICKS);
    timing.armMenuClick(GuiActionTiming.randomDelay());
    return BuyOutcome.WAITING;
  }

  SellOutcome tickSelling(Minecraft mc, LocalPlayer player, int phaseTicks) {
    if (!inventory.hasSellableCrops(player)) {
      return SellOutcome.COMPLETE;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)) {
      return timing.tryReopen(MAX_REOPEN_ATTEMPTS) ? SellOutcome.REOPEN : SellOutcome.GUI_CLOSED;
    }
    if (phaseTicks > SELL_TIMEOUT_TICKS) {
      return SellOutcome.TIMED_OUT;
    }
    if (timing.waitForMenuClick()) {
      return SellOutcome.WAITING;
    }
    Slot sell = inventory.nextSellableSlot(handled.getMenu(), player);
    if (sell == null) {
      return SellOutcome.COMPLETE;
    }
    DebugLog.trace(
        DebugLog.Category.MACRO,
        "Wheat: selling %s (slot %d)",
        stripFormatting(sell.getItem().getHoverName().getString()),
        sell.index);
    clickSlot(mc, handled.getMenu(), sell.index, 2, ContainerInput.CLONE, player);
    timing.armMenuClick(GuiActionTiming.randomDelay());
    return SellOutcome.WAITING;
  }

  /**
   * The merchant entity, for the walk-in gaze. Same cache the interact uses; null until it is in
   * range.
   */
  Entity merchant(ClientLevel level) {
    return level == null ? null : updateMerchant(level);
  }

  private Entity updateMerchant(ClientLevel level) {
    if (merchantEntity != null
        && merchantEntity.isAlive()
        && isNearBlockCenter(merchantEntity, POSITION, SEARCH_RADIUS)) {
      String name = entityNameLower(merchantEntity);
      if (name.contains("farm merchant") || name.contains("click")) {
        return merchantEntity;
      }
    }
    merchantEntity =
        findNearestNamedEntity(level, "farm merchant", "click", POSITION, SEARCH_RADIUS);
    return merchantEntity;
  }
}
