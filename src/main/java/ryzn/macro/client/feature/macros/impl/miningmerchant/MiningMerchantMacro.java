package ryzn.macro.client.feature.macros.impl.miningmerchant;

import static ryzn.macro.client.MacroInput.releaseAllKeys;
import static ryzn.macro.client.MacroInput.setUseHeldOn;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.entityLookPoint;
import static ryzn.macro.client.util.entity.MacroEntities.entityNameLower;
import static ryzn.macro.client.util.entity.MacroEntities.findNearestNamedEntity;
import static ryzn.macro.client.util.entity.MacroEntities.isNearBlockCenter;
import static ryzn.macro.client.util.gui.MacroScreens.cleanTitle;
import static ryzn.macro.client.util.gui.MacroScreens.closeCurrentScreen;
import static ryzn.macro.client.util.inventory.MacroSlots.clickSlot;

import lombok.Getter;
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
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.MiningMerchantActionEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.debug.MacroStateTrace;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.feature.macros.api.Macro;
import ryzn.macro.client.feature.rotation.api.LookController;
import ryzn.macro.client.feature.rotation.impl.RouteGaze;
import ryzn.macro.client.input.ClickConfirm;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.input.MacroActionTiming;
import ryzn.macro.client.input.MacroKeys;
import ryzn.macro.client.util.text.RyznChat;
import ryzn.macro.client.util.world.MacroObstruction;

/**
 * Walks to the Hub Mining Merchant and buys Gold Ingot x32, so the Gold Shovel macro has the ingot
 * its craft needs. This is the buy half of the old Mining Merchant + Reforge macro (removed in
 * 97212bc when the standalone mining macro replaced the coal quest); the Blacksmith, coal-mining,
 * and reforge stages stay gone.
 *
 * <p>Walking uses the native pathfinder, rotation and GUI clicks mirror the Gold Shovel macro. The
 * purchase is confirmed by the player's gold count rising, never by "a click was sent".
 */
public final class MiningMerchantMacro extends Macro {

  @RequiredArgsConstructor
  private enum State {
    IDLE("Idle"),
    PATHING_TO_MERCHANT("Walking to Mining Merchant"),
    INTERACT_MERCHANT("Opening Mining Merchant"),
    BUY_GOLD("Buying Gold Ingot x32"),
    DONE("Finishing"),
    FAILED("Failed");

    private final String label;
  }

  private static final BlockPos MERCHANT_POSITION = new BlockPos(12, 63, -114);
  private static final double NPC_SEARCH_RADIUS = 8.0;
  private static final double ARRIVAL_DISTANCE_SQ = 0.85 * 0.85;

  private static final int PATH_TIMEOUT_TICKS = 20 * 45;
  private static final int NPC_SEARCH_TIMEOUT_TICKS = 20 * 10;
  private static final int INTERACT_TIMEOUT_TICKS = 20 * 25;
  private static final int GUI_STEP_TIMEOUT_TICKS = 20 * 20;
  private static final int NPC_APPROACH_GRACE_TICKS = 20;
  private static final int DONE_SETTLE_TICKS = 5;

  /** How many gold ingots to buy from this merchant. */
  private static final int BUY_AMOUNT = 32;

  /** How long a sent purchase may take to land before the buy click is re-sent. */
  private static final int BUY_DELIVERY_TICKS = 40;

  @Getter private boolean enabled;
  private State state = State.IDLE;
  private String status = "Disabled";
  private boolean lastRunCompleted;
  private String lastStopReason = "";

  private int stateTicks;
  private int guiStep;

  /**
   * Count of the material being bought, sampled on the first tick of its state; -1 = not sampled
   * yet.
   */
  private int materialBefore = -1;

  /** Last observed count of that material, so a delivery counts as progress against the timeout. */
  private int lastOwned = -1;

  /** Consecutive ticks a real player has blocked the click; past the norm, the bot relocates. */
  private int blockedTicks;

  private boolean interactionRequested;
  private boolean merchantGuiOpened;
  private Entity merchant;

  private final MiningMerchantMenuSlots menuSlots = new MiningMerchantMenuSlots();
  private final ClickConfirm buyConfirm = new ClickConfirm();

  /** Watches the merchant while the route walks; see {@code RotationManager.externalOwnsLook}. */
  private final RouteGaze gaze = new RouteGaze();

  private final MacroActionTiming actionTiming = new MacroActionTiming();
  private final MacroStateTrace trace = new MacroStateTrace("miningmerchant");

  /**
   * @description Creates the process-lifetime Mining Merchant macro.
   */
  public MiningMerchantMacro() {
    super(
        "mining_merchant",
        "Mining Merchant",
        "Walks to the Hub Mining Merchant and buys 32 gold ingots for the Gold" + " Shovel craft.");
  }

  public void setEnabled(boolean on) {
    if (enabled == on) {
      return;
    }
    enabled = on;
    RyznChat.announce("Mining Merchant", on);
    if (on) {
      beginSession();
    } else {
      stopAll("disabled", false);
    }
  }

  public String statusText() {
    return enabled ? status : "Disabled";
  }

  public boolean wasLastRunCompleted() {
    return lastRunCompleted;
  }

  public String lastStopReason() {
    return lastStopReason;
  }

  public boolean shouldLockMouse() {
    if (!enabled) {
      return false;
    }
    if (gaze.isOwned()) {
      return true;
    } // watching the merchant mid-route; the executor keeps the body
    if (PathfindCommand.isNavigationActive()) {
      return false;
    }
    return state == State.INTERACT_MERCHANT;
  }

  /**
   * True while the walk to the merchant is watching it. Read by {@code
   * RotationManager.externalOwnsLook}.
   *
   * @return true while the route is watching its target with the head
   */
  public boolean ownsRouteLook() {
    return enabled && gaze.isOwned();
  }

  public void onClientTick(Minecraft mc) {
    trace.log(state.name(), status);
    if (!enabled) {
      return;
    }

    final LocalPlayer player = mc.player;
    final ClientLevel level = mc.level;
    if (player == null || level == null) {
      releaseAllKeys(mc.options);
      return;
    }

    switch (state) {
      case PATHING_TO_MERCHANT -> tickPathing(mc, player);
      case INTERACT_MERCHANT -> tickInteractMerchant(mc, player, level);
      case BUY_GOLD -> tickBuyGold(mc, player);
      case DONE -> tickDone(mc);
      case FAILED -> tickFailed(mc);
      default -> beginSession();
    }
  }

  /** Functional aim on the game tick (fps-independent; works minimized at ~1 fps). */
  public void tickLook() {
    if (!shouldLockMouse() || (PathfindCommand.isNavigationActive() && !gaze.isOwned())) {
      return;
    }
    final LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) {
      return;
    }
    look().tick(player);
  }

  /** Same wall-clock ease re-sampled at frame rate; see {@link LookController#tick}. */
  public void onRenderFrame() {
    tickLook();
  }

  // ---- pathing ----

  private void tickPathing(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);
    // Watch the merchant on the way in rather than staring down the path and snapping round on
    // arrival.
    if (!gaze.update(player, mc.level == null ? null : merchant(mc.level), look())) {
      look().clear();
    }

    if (mc.gui.screen() != null) {
      PathfindCommand.stopNavQuiet();
      status = "Paused while a screen is open";
      return;
    }
    if (isAtMerchantPosition(player)) {
      PathfindCommand.stopNavQuiet();
      setState(State.INTERACT_MERCHANT);
      return;
    }
    if (!actionTiming.isPathRequested()) {
      if (!PathfindCommand.startToQuiet(
          MERCHANT_POSITION.getX(), MERCHANT_POSITION.getY(), MERCHANT_POSITION.getZ())) {
        fail("Could not start path to Mining Merchant");
        return;
      }
      actionTiming.markPathRequested();
      status = "Walking to Mining Merchant";
      return;
    }
    if (!PathfindCommand.isNavigationActive() && stateTicks > NPC_APPROACH_GRACE_TICKS) {
      setState(State.INTERACT_MERCHANT);
      return;
    }
    if (stateTicks > PATH_TIMEOUT_TICKS) {
      fail("Path to Mining Merchant timed out");
    }
  }

  private boolean isAtMerchantPosition(LocalPlayer player) {
    final double dx = MERCHANT_POSITION.getX() + 0.5 - player.getX();
    final double dz = MERCHANT_POSITION.getZ() + 0.5 - player.getZ();
    return player.getBlockX() == MERCHANT_POSITION.getX()
        && player.getBlockZ() == MERCHANT_POSITION.getZ()
        && Math.abs(player.getY() - MERCHANT_POSITION.getY()) <= 2.0
        && dx * dx + dz * dz <= ARRIVAL_DISTANCE_SQ;
  }

  // ---- merchant ----

  private void tickInteractMerchant(Minecraft mc, LocalPlayer player, ClientLevel level) {
    stateTicks++;
    releaseAllKeys(mc.options);

    if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
      if (interactionRequested || menuSlots.isPurchaseScreen(mc, merchantGuiOpened)) {
        merchantGuiOpened = true;
        status = "Mining Merchant screen opened: " + cleanTitle(mc);
        setState(State.BUY_GOLD);
        return;
      }
      status = "Unexpected merchant screen: " + cleanTitle(mc);
      if (stateTicks > INTERACT_TIMEOUT_TICKS) {
        fail("Unexpected screen while opening Mining Merchant: " + cleanTitle(mc));
      } else if (stateTicks > GuiActionTiming.maxDelayTicks() * 2) {
        closeCurrentScreen(mc);
      }
      return;
    }
    if (mc.gui.screen() != null) {
      status = "Waiting for screen to close: " + cleanTitle(mc);
      if (stateTicks > INTERACT_TIMEOUT_TICKS) {
        fail("Mining Merchant blocked by screen: " + cleanTitle(mc));
      }
      return;
    }
    if (stateTicks > INTERACT_TIMEOUT_TICKS) {
      fail("Mining Merchant GUI timeout");
      return;
    }

    final Entity npc = merchant(level);
    if (npc == null) {
      if (stateTicks > NPC_SEARCH_TIMEOUT_TICKS) {
        fail("Mining Merchant not found");
      }
      return;
    }
    // Interaction norm: walk in to within 3 blocks before clicking. The wider aim-range gate reads
    // as
    // "in range" a full block past the server's actual reach, which spends the whole timeout
    // clicking
    // at a merchant that never answers.
    if (!MacroObstruction.isWithinInteract(player, npc)) {
      if (stateTicks > INTERACT_TIMEOUT_TICKS) {
        fail("Mining Merchant out of range");
        return;
      }
      status = "Moving closer to Mining Merchant";
      if (look().aimAtEntity(player, npc) && mc.options != null) {
        MacroKeys.set(mc.options.keyUp, true);
      }
      return;
    }
    if (!look().aimAtEntity(player, npc)) {
      return;
    }
    // A stranger on the crosshair or stacked on the bot earns a sidestep, not a stare-off.
    if (!MacroInput.isCrosshairOn(mc, npc) || MacroObstruction.isPlayerStackedOn(level, player)) {
      setUseHeldOn(mc.options, false, npc);
      tickBlocked(mc, level, player, npc);
      return;
    }
    blockedTicks = 0;
    if (actionTiming.waitForInteraction()) {
      return;
    }
    setUseHeldOn(mc.options, true, npc);
    interactionRequested = true;
    actionTiming.armInteraction(GuiActionTiming.randomDelayAtLeast(2));
    status = "Opening Mining Merchant";
  }

  /** Wait out a brief block; a lasting one walks to a tile that can actually see the merchant. */
  private void tickBlocked(Minecraft mc, ClientLevel level, LocalPlayer player, Entity npc) {
    status = "Blocked from the Mining Merchant, waiting";
    if (++blockedTicks < MacroObstruction.BLOCKED_RELOCATE_TICKS) {
      return;
    }
    blockedTicks = 0;
    final BlockPos side =
        MacroObstruction.findSidestep(
            level, player, npc.blockPosition(), entityLookPoint(npc), npc);
    if (side == null) {
      return;
    }
    releaseAllKeys(mc.options);
    status = "Sidestepping for a clear view of the Mining Merchant";
    PathfindCommand.startToQuiet(side.getX(), side.getY(), side.getZ());
  }

  /**
   * Buys gold up to {@link #BUY_AMOUNT}: right-click its shop entry to open the trading options,
   * then buy the biggest offer that fits until the gold is really in the inventory.
   */
  private void tickBuyGold(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);
    final String label = "Gold Ingot";

    if (materialBefore < 0) {
      materialBefore = menuSlots.playerGoldCount(player);
    }
    if (stateTicks > GUI_STEP_TIMEOUT_TICKS) {
      fail("Timed out buying " + label + " x" + BUY_AMOUNT);
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)) {
      fail("Mining Merchant GUI closed while buying " + label);
      return;
    }
    if (!menuSlots.isPurchaseScreen(mc, merchantGuiOpened)) {
      status = "Wrong screen while buying " + label + ": " + cleanTitle(mc);
      return;
    }

    // Top up to 32 rather than add 32: a run restarted after a kick already holds what it bought.
    final int goal = Math.max(materialBefore, BUY_AMOUNT);
    final int owned = menuSlots.playerGoldCount(player);
    final int remaining = goal - owned;
    // Each delivery is progress, so the timeout watches for a stall rather than for the whole
    // purchase.
    if (owned != lastOwned) {
      lastOwned = owned;
      stateTicks = 0;
    }
    // The purchase is only real once the gold is actually in the inventory.
    if (remaining <= 0) {
      setState(State.DONE);
      return;
    }
    if (actionTiming.waitForMenuClick()) {
      status = label + " buy step " + guiStep + " on " + cleanTitle(mc);
      return;
    }
    if (mc.gameMode == null) {
      fail("Interaction manager missing");
      return;
    }
    final AbstractContainerMenu menu = handled.getMenu();

    switch (guiStep) {
      case 0 -> {
        final Slot entry = menuSlots.buyOfferSlot(menu, player, remaining);
        if (entry == null) {
          status = label + " entry not found on " + cleanTitle(mc);
          return;
        }
        status = "Opening " + label + " options from slot " + entry.index;
        clickSlot(mc, menu, entry.index, 1, ContainerInput.PICKUP, player);
        guiStep = 1;
        actionTiming.armMenuClick(GuiActionTiming.randomDelay());
      }
      case 1 -> {
        if (buyConfirm.awaiting(owned)) {
          status = "Waiting for " + label + " (" + owned + "/" + goal + ")";
          return;
        }
        final Slot offer = menuSlots.buyOfferSlot(menu, player, remaining);
        if (offer == null) {
          status = label + " offer not found on " + cleanTitle(mc);
          return;
        }
        status = "Buying " + label + " from slot " + offer.index + " (" + owned + "/" + goal + ")";
        clickSlot(mc, menu, offer.index, 0, ContainerInput.PICKUP, player);
        buyConfirm.sent(owned, BUY_DELIVERY_TICKS);
        actionTiming.armMenuClick(GuiActionTiming.randomDelay());
      }
      default -> {}
    }
  }

  private Entity merchant(ClientLevel level) {
    if (merchant != null
        && merchant.isAlive()
        && isNearBlockCenter(merchant, MERCHANT_POSITION, NPC_SEARCH_RADIUS)
        && entityNameLower(merchant).contains("mining merchant")) {
      return merchant;
    }
    merchant =
        findNearestNamedEntity(
            level, "mining merchant", "click", MERCHANT_POSITION, NPC_SEARCH_RADIUS);
    return merchant;
  }

  private void tickDone(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (stateTicks >= DONE_SETTLE_TICKS || mc.gui.screen() == null) {
      status = "Complete";
      stopAll("complete", true);
    }
  }

  private void tickFailed(Minecraft mc) {
    releaseAllKeys(mc.options);
    stopAll(status, false);
  }

  // ---- lifecycle ----

  private void beginSession() {
    lastRunCompleted = false;
    lastStopReason = "";
    resetRuntime();
    enabled = true;
    setState(State.PATHING_TO_MERCHANT);
  }

  private void setState(State next) {
    if (next == state) {
      return;
    }
    state = next;
    status = next.label;
    stateTicks = 0;
    actionTiming.enterState();
    guiStep = 0;
    materialBefore = -1;
    lastOwned = -1;
    buyConfirm.clear();
    look().resetSettle();
    if (next == State.INTERACT_MERCHANT) {
      look().clear();
      merchant = null;
      interactionRequested = false;
      merchantGuiOpened = false;
    }
  }

  private void resetRuntime() {
    state = State.IDLE;
    status = "Starting Mining Merchant macro";
    stateTicks = 0;
    actionTiming.enterState();
    guiStep = 0;
    materialBefore = -1;
    lastOwned = -1;
    buyConfirm.clear();
    blockedTicks = 0;
    interactionRequested = false;
    merchantGuiOpened = false;
    merchant = null;
    look().clear();
  }

  private void fail(String message) {
    setState(State.FAILED);
    status = message;
  }

  private void stopAll(String reason, boolean completed) {
    final Minecraft mc = Minecraft.getInstance();
    releaseAllKeys(mc.options);
    PathfindCommand.stopNavQuiet();
    if (mc.gui.screen() != null && menuSlots.isPurchaseScreen(mc, merchantGuiOpened)) {
      closeCurrentScreen(mc);
    }
    resetRuntime();
    enabled = false;
    lastRunCompleted = completed;
    lastStopReason = reason;
    status = "Stopped: " + reason;
  }

  /**
   * @description Applies a run-state request from the menu, command, or failsafe.
   * @param miningmerchantactionevent the requested run state
   */
  @Subscribe
  public void onMiningMerchantActionEvent(MiningMerchantActionEvent miningmerchantactionevent) {
    setEnabled(miningmerchantactionevent.enabled());
  }

  /**
   * @description Returns this macro's aim controller from the shared rotation subsystem.
   * @return the controller the rotation manager keeps for this owner
   */
  private LookController look() {
    return RyznBootstrap.rotationManager().lookController(this, 90f);
  }
}
