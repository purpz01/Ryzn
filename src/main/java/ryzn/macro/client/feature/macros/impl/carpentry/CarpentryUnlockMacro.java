package ryzn.macro.client.feature.macros.impl.carpentry;

import static ryzn.macro.client.MacroInput.releaseAllKeys;
import static ryzn.macro.client.MacroInput.selectHotbarSlot;
import static ryzn.macro.client.MacroInput.setUseHeld;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.desiredYawPitch;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.entityLookPoint;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.isLookWithin;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.isWithinInteractRange;
import static ryzn.macro.client.util.entity.MacroEntities.entityNameLower;
import static ryzn.macro.client.util.entity.MacroEntities.findNearestNamedEntity;
import static ryzn.macro.client.util.entity.MacroEntities.isNearBlockCenter;
import static ryzn.macro.client.util.gui.MacroScreens.closeCurrentScreen;
import static ryzn.macro.client.util.gui.MacroScreens.titleContains;
import static ryzn.macro.client.util.inventory.MacroItems.stackName;
import static ryzn.macro.client.util.inventory.MacroItems.stackSearchText;
import static ryzn.macro.client.util.inventory.MacroSlots.clickSlot;
import static ryzn.macro.client.util.text.MacroText.containsStackAmount;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;
import static ryzn.macro.client.util.text.MacroText.stripFormatting;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.CarpentryUnlockActionEvent;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.debug.MacroStateTrace;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.feature.macros.api.Macro;
import ryzn.macro.client.feature.rotation.impl.RouteGaze;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.input.MacroKeys;
import ryzn.macro.client.util.gui.MacroMenu;
import ryzn.macro.client.util.inventory.MacroSlots;
import ryzn.macro.client.util.text.RyznChat;

/**
 * Ryzn-native Carpentry Unlock macro. Paths to the Wool Weaver, buys White Wool x64 (page 2 ->
 * right-click trade -> Shop Trading Options -> x64 cost 2048), paths to the Carpenter, equips the
 * wool, interacts, and waits for the carpentry unlock chat. Walking uses Ryzn's native pathfinder
 * ({@link PathfindCommand}); rotation, GUI handling, and slot logic mirror the Ryzn Bea / Gold
 * Shovel macros (self-contained).
 */
public final class CarpentryUnlockMacro extends Macro {

  private final float[] desiredAim = new float[2];

  @RequiredArgsConstructor
  private enum State {
    IDLE("Idle"),
    PATHING_TO_WOOL_WEAVER("Walking to Wool Weaver"),
    ROTATE_TO_WOOL_WEAVER("Aiming at Wool Weaver"),
    INTERACT_WOOL_WEAVER("Opening Wool Weaver"),
    CLICK_NEXT_PAGE("Opening page 2"),
    RIGHT_CLICK_WHITE_WOOL("Opening White Wool options"),
    BUY_WHITE_WOOL_64("Buying White Wool x64"),
    CLOSE_WOOL_MENU("Closing Wool Weaver"),
    PATHING_TO_CARPENTER("Walking to Carpenter"),
    ROTATE_TO_CARPENTER("Aiming at Carpenter"),
    EQUIP_WHITE_WOOL("Equipping White Wool"),
    INTERACT_CARPENTER("Interacting with Carpenter"),
    WAIT_CARPENTRY_UNLOCK_CHAT("Waiting for unlock chat"),
    DONE("Finishing"),
    FAILED("Failed");

    private final String label;
  }

  private static final int WOOL_WEAVER_X = -16;
  private static final int WOOL_WEAVER_Y = 71;
  private static final int WOOL_WEAVER_Z = -59;
  private static final int CARPENTER_X = -138;
  private static final int CARPENTER_Y = 74;
  private static final int CARPENTER_Z = -42;

  private static final int INVENTORY_SIZE =
      36; // hotbar + main inventory (the player-owned slot count)
  private static final int WHITE_WOOL_NEEDED = 64;
  private static final double NPC_SEARCH_RADIUS = 8.0;
  private static final double EXACT_ARRIVAL_DIST_SQ = 0.85 * 0.85;

  private static final int PATH_TIMEOUT_TICKS = 20 * 45;
  private static final int NPC_SEARCH_TIMEOUT_TICKS = 20 * 10;
  private static final int INTERACT_TIMEOUT_TICKS = 20 * 25;
  private static final int NEXT_PAGE_TIMEOUT_TICKS = 20 * 15;
  private static final int WHITE_WOOL_TIMEOUT_TICKS = 20 * 15;
  private static final int WHITE_WOOL_RETRY_TICKS = 40;
  private static final int MAX_NEXT_PAGE_RETRIES = 2;
  private static final int TRADING_OPTIONS_TIMEOUT_TICKS = 20 * 15;
  private static final int PURCHASE_TIMEOUT_TICKS = 20 * 15;
  private static final int CLOSE_MENU_TIMEOUT_TICKS = 20 * 5;
  private static final int CARPENTER_SEARCH_TIMEOUT_TICKS = 20 * 10;
  private static final int EQUIP_WOOL_TIMEOUT_TICKS = 20 * 20;
  private static final int CARPENTER_INTERACT_TIMEOUT_TICKS = 20 * 25;
  private static final int UNLOCK_CHAT_TIMEOUT_TICKS = 20 * 20;
  private static final int DONE_SETTLE_TICKS = 5;
  private static final int NPC_APPROACH_GRACE_TICKS = 20;

  private static final float AIM_SETTLED_THRESHOLD = 2.0f;

  @Getter private boolean enabled;
  private State state = State.IDLE;
  private String status = "Disabled";
  private boolean lastRunCompleted;
  private String lastStopReason = "";

  private int stateTicks;
  private boolean pathRequested;
  private int interactCooldown;
  private int guiClickCooldown;

  private Entity woolWeaverEntity;
  private Entity carpenterEntity;
  private boolean aimSettled;

  private boolean nextPageClicked;
  private int nextPageRetries;
  private boolean whiteWoolRightClicked;
  private boolean whiteWool64Clicked;
  private boolean woolGuiOpenedByMacro;
  private boolean tradingOptionsOpenedByMacro;
  private boolean carpenterGuiOpenedByMacro;
  private boolean whiteWoolSwapRequested;
  private boolean carpenterInteractSent;
  private boolean carpentryUnlockDetected;

  // Rotation (driven per-frame in onRenderFrame, mirroring the Bea / Gold Shovel macros).
  private Vec3 lookTarget;

  /** Watches the NPC being walked to; see {@code RotationManager.externalOwnsLook}. */
  private final RouteGaze gaze = new RouteGaze();

  /**
   * @description Creates the process-lifetime Carpentry Unlock macro.
   */
  public CarpentryUnlockMacro() {
    super(
        "carpentry_unlock",
        "Carpentry Unlock",
        "Buys White Wool from the Wool Weaver and hands it to the Carpenter to unlock"
            + " Carpentry.");
  }

  /**
   * @description Reads chat at the original callback slot.
   * @param gamemessageevent the received message and its overlay flag
   */
  public void onGameMessageEvent(GameMessageEvent gamemessageevent) {
    final Component message = gamemessageevent.message();
    final boolean overlay = gamemessageevent.overlay();
    if (overlay || !enabled || message == null) {
      return;
    }
    if (state != State.INTERACT_CARPENTER && state != State.WAIT_CARPENTRY_UNLOCK_CHAT) {
      return;
    }
    final String text = stripFormatting(message.getString());
    if (isCarpentryUnlockMessage(text)) {
      carpentryUnlockDetected = true;
    }
  }

  public void setEnabled(boolean on) {
    if (enabled == on) {
      return;
    }
    enabled = on;
    RyznChat.announce("Carpentry Unlock", on);
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
    } // watching the NPC mid-route; the executor keeps the body
    if (PathfindCommand.isNavigationActive()) {
      return false;
    }
    return switch (state) {
      case ROTATE_TO_WOOL_WEAVER,
          INTERACT_WOOL_WEAVER,
          ROTATE_TO_CARPENTER,
          EQUIP_WHITE_WOOL,
          INTERACT_CARPENTER ->
          true;
      default -> false;
    };
  }

  private final MacroStateTrace trace = new MacroStateTrace("carpentry");

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
      case PATHING_TO_WOOL_WEAVER ->
          tickPathing(
              mc,
              player,
              WOOL_WEAVER_X,
              WOOL_WEAVER_Y,
              WOOL_WEAVER_Z,
              State.ROTATE_TO_WOOL_WEAVER,
              "Wool Weaver");
      case ROTATE_TO_WOOL_WEAVER -> tickRotateToNpc(mc, player, level, true);
      case INTERACT_WOOL_WEAVER -> tickInteractWoolWeaver(mc, player, level);
      case CLICK_NEXT_PAGE -> tickClickNextPage(mc, player);
      case RIGHT_CLICK_WHITE_WOOL -> tickRightClickWhiteWool(mc, player);
      case BUY_WHITE_WOOL_64 -> tickBuyWhiteWool64(mc, player);
      case CLOSE_WOOL_MENU -> tickCloseWoolMenu(mc);
      case PATHING_TO_CARPENTER ->
          tickPathing(
              mc,
              player,
              CARPENTER_X,
              CARPENTER_Y,
              CARPENTER_Z,
              State.ROTATE_TO_CARPENTER,
              "Carpenter");
      case ROTATE_TO_CARPENTER -> tickRotateToNpc(mc, player, level, false);
      case EQUIP_WHITE_WOOL -> tickEquipWhiteWool(mc, player);
      case INTERACT_CARPENTER -> tickInteractCarpenter(mc, player, level);
      case WAIT_CARPENTRY_UNLOCK_CHAT -> tickWaitCarpentryUnlock(mc, player);
      case DONE -> tickDone(mc);
      case FAILED -> tickFailed(mc);
      default -> beginSession();
    }
  }

  /** Functional aim on the game tick (fps-independent; works minimized at ~1 fps). */
  /**
   * True while the walk to an NPC is watching it. Read by {@code RotationManager.externalOwnsLook}.
   *
   * @return true while the route is watching its target with the head
   */
  public boolean ownsRouteLook() {
    return enabled && gaze.isOwned();
  }

  public void tickLook() {
    if (!shouldLockMouse()
        || lookTarget == null
        || (PathfindCommand.isNavigationActive() && !gaze.isOwned())) {
      return;
    }
    final LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) {
      return;
    }
    desiredYawPitch(player.getEyePosition(), lookTarget, desiredAim);
    RyznBootstrap.rotationManager().aimStandard(this, player, desiredAim[0], desiredAim[1]);
  }

  public void onRenderFrame() {
    tickLook();
  }

  private void beginSession() {
    resetRuntime();
    enabled = true;
    lastRunCompleted = false;
    lastStopReason = "";
    // A run restarted after a kick may already hold the 64 wool: don't pay 2048 for it twice.
    final LocalPlayer player = Minecraft.getInstance().player;
    setState(
        player != null && whiteWoolCount(player) >= WHITE_WOOL_NEEDED
            ? State.PATHING_TO_CARPENTER
            : State.PATHING_TO_WOOL_WEAVER);
  }

  private int whiteWoolCount(LocalPlayer player) {
    int count = 0;
    for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
      final ItemStack stack = player.getInventory().getItem(slot);
      if (isWhiteWoolStack(stack)) {
        count += stack.getCount();
      }
    }
    return count;
  }

  // ---- pathing ----

  private void tickPathing(
      Minecraft mc, LocalPlayer player, int x, int y, int z, State next, String label) {
    stateTicks++;
    releaseAllKeys(mc.options);
    // Watch the NPC on the way in rather than staring down the path and snapping round on arrival.
    final Entity watch =
        mc.level == null ? null : updateNpc(mc.level, state == State.PATHING_TO_WOOL_WEAVER);
    if (gaze.shouldWatch(player, watch, () -> RyznBootstrap.rotationManager().resetAim(this))) {
      lookTarget = entityLookPoint(watch);
    } else {
      clearLook();
    }

    if (mc.gui.screen() != null) {
      PathfindCommand.stopNavQuiet();
      status = "Paused while a screen is open";
      return;
    }

    if (isAtExactTarget(player, x, y, z)) {
      PathfindCommand.stopNavQuiet();
      setState(next);
      return;
    }

    if (!pathRequested) {
      if (!PathfindCommand.startToQuiet(x, y, z)) {
        fail("Could not start path to " + label);
        return;
      }
      pathRequested = true;
      status = "Walking to " + label;
      return;
    }

    if (!PathfindCommand.isNavigationActive() && stateTicks > NPC_APPROACH_GRACE_TICKS) {
      setState(next);
      return;
    }

    if (stateTicks > PATH_TIMEOUT_TICKS) {
      fail("Path to " + label + " timed out");
    }
  }

  // ---- NPC rotation + interaction ----

  private void tickRotateToNpc(
      Minecraft mc, LocalPlayer player, ClientLevel level, boolean weaver) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (mc.gui.screen() != null) {
      return;
    }

    final Entity npc = updateNpc(level, weaver);
    final String name = weaver ? "Wool Weaver" : "Carpenter";
    if (npc == null) {
      if (stateTicks > NPC_SEARCH_TIMEOUT_TICKS) {
        fail(name + " not found");
      } else {
        status = "Searching for " + name;
      }
      return;
    }

    if (!isWithinInteractRange(player, npc)) {
      approachNpc(
          mc,
          player,
          npc,
          name,
          weaver ? INTERACT_TIMEOUT_TICKS : CARPENTER_INTERACT_TIMEOUT_TICKS);
      return;
    }

    if (aimAtEntity(player, npc)) {
      setState(weaver ? State.INTERACT_WOOL_WEAVER : State.EQUIP_WHITE_WOOL);
    }
  }

  private void tickInteractWoolWeaver(Minecraft mc, LocalPlayer player, ClientLevel level) {
    stateTicks++;
    releaseAllKeys(mc.options);

    if (isWoolWeaverScreenOpen(mc)) {
      woolGuiOpenedByMacro = true;
      setState(State.CLICK_NEXT_PAGE);
      return;
    }
    if (mc.gui.screen() != null) {
      return;
    }
    if (stateTicks > INTERACT_TIMEOUT_TICKS) {
      fail("Wool Weaver GUI timeout");
      return;
    }

    final Entity npc = updateNpc(level, true);
    if (npc == null) {
      if (stateTicks > NPC_SEARCH_TIMEOUT_TICKS) {
        fail("Wool Weaver not found");
      }
      return;
    }
    if (!isWithinInteractRange(player, npc)) {
      approachNpc(mc, player, npc, "Wool Weaver", INTERACT_TIMEOUT_TICKS);
      return;
    }
    if (!aimAtEntity(player, npc)) {
      return;
    }
    if (interactCooldown > 0) {
      interactCooldown--;
      return;
    }
    if (mc.options != null) {
      setUseHeld(mc.options, true);
    }
    interactCooldown = GuiActionTiming.randomDelayAtLeast(2);
    status = "Opening Wool Weaver";
  }

  private void tickClickNextPage(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);

    if (stateTicks > NEXT_PAGE_TIMEOUT_TICKS) {
      fail("Next page timeout");
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)
        || !isWoolWeaverScreenOpen(mc)) {
      fail("Wool Weaver GUI closed");
      return;
    }
    if (guiClickCooldown > 0) {
      guiClickCooldown--;
      return;
    }
    if (nextPageClicked) {
      setState(State.RIGHT_CLICK_WHITE_WOOL);
      return;
    }
    if (mc.gameMode == null) {
      fail("Interaction manager missing");
      return;
    }

    final Slot next = findNextPageSlot(handled.getMenu(), player);
    if (next == null) {
      status = "Searching for Next Page";
      return;
    }
    clickSlot(mc, handled.getMenu(), next.index, 0, ContainerInput.PICKUP, player);
    nextPageClicked = true;
    guiClickCooldown = GuiActionTiming.randomDelay();
    status = "Clicked Next Page";
  }

  private void tickRightClickWhiteWool(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);

    if (stateTicks > WHITE_WOOL_TIMEOUT_TICKS) {
      fail("White Wool slot timeout");
      return;
    }
    if (whiteWoolRightClicked && isTradingOptionsScreenOpen(mc)) {
      tradingOptionsOpenedByMacro = true;
      setState(State.BUY_WHITE_WOOL_64);
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)
        || !isWoolWeaverScreenOpen(mc)) {
      if (whiteWoolRightClicked) {
        return;
      }
      fail("Wool Weaver GUI closed");
      return;
    }
    if (guiClickCooldown > 0) {
      guiClickCooldown--;
      return;
    }
    if (whiteWoolRightClicked) {
      return;
    }
    if (mc.gameMode == null) {
      fail("Interaction manager missing");
      return;
    }

    final Slot whiteWool = findWhiteWoolTradeSlot(handled.getMenu(), player);
    if (whiteWool == null) {
      // the page-turn click can be eaten while the shop is still filling; click it again rather
      // than
      // scanning page 1 until the timeout
      if (stateTicks > WHITE_WOOL_RETRY_TICKS && nextPageRetries < MAX_NEXT_PAGE_RETRIES) {
        nextPageRetries++;
        setState(State.CLICK_NEXT_PAGE);
        return;
      }
      status = "Searching for White Wool trade";
      return;
    }
    clickSlot(mc, handled.getMenu(), whiteWool.index, 1, ContainerInput.PICKUP, player);
    whiteWoolRightClicked = true;
    guiClickCooldown = GuiActionTiming.randomDelay();
    status = "Right-clicked White Wool";
  }

  private void tickBuyWhiteWool64(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);

    final int timeout = whiteWool64Clicked ? PURCHASE_TIMEOUT_TICKS : TRADING_OPTIONS_TIMEOUT_TICKS;
    if (stateTicks > timeout) {
      fail("White Wool x64 timeout");
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)
        || !isTradingOptionsScreenOpen(mc)) {
      status = "Waiting for Shop Trading Options";
      return;
    }
    tradingOptionsOpenedByMacro = true;
    if (guiClickCooldown > 0) {
      guiClickCooldown--;
      return;
    }
    if (whiteWool64Clicked) {
      setState(State.CLOSE_WOOL_MENU);
      return;
    }
    if (mc.gameMode == null) {
      fail("Interaction manager missing");
      return;
    }

    final Slot option = findWhiteWool64OptionSlot(handled.getMenu(), player);
    if (option == null) {
      status = "Searching for White Wool x64 (cost 2048)";
      return;
    }
    clickSlot(mc, handled.getMenu(), option.index, 0, ContainerInput.PICKUP, player);
    whiteWool64Clicked = true;
    guiClickCooldown = GuiActionTiming.randomDelay();
    status = "Bought White Wool x64";
  }

  private void tickCloseWoolMenu(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (stateTicks > CLOSE_MENU_TIMEOUT_TICKS && mc.gui.screen() != null) {
      fail("Wool Weaver close timeout");
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    woolGuiOpenedByMacro = false;
    tradingOptionsOpenedByMacro = false;
    setState(State.PATHING_TO_CARPENTER);
  }

  private void tickEquipWhiteWool(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);
    keepLookingAt(carpenterEntity);

    if (stateTicks > EQUIP_WOOL_TIMEOUT_TICKS) {
      fail("White Wool equip timeout");
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }

    final int hotbarSlot = MacroSlots.findHotbarSlot(player, this::isWhiteWoolStack);
    if (hotbarSlot >= 0) {
      selectHotbarSlot(player, hotbarSlot);
      setState(State.INTERACT_CARPENTER);
      return;
    }

    final int inventorySlot = MacroSlots.findMainInventorySlot(player, this::isWhiteWoolStack);
    if (inventorySlot < 0) {
      fail("White Wool missing");
      return;
    }
    if (whiteWoolSwapRequested) {
      status = "Waiting for White Wool swap";
      return;
    }
    int dest = MacroSlots.findFirstEmptyHotbarSlot(player);
    if (dest < 0) {
      dest = player.getInventory().getSelectedSlot();
    }
    if (!MacroSlots.swapInventoryToHotbar(mc, player, inventorySlot, dest)) {
      fail("White Wool swap failed");
      return;
    }
    whiteWoolSwapRequested = true;
    status = "Moving White Wool to hotbar";
  }

  private void tickInteractCarpenter(Minecraft mc, LocalPlayer player, ClientLevel level) {
    stateTicks++;
    releaseAllKeys(mc.options);

    if (carpentryUnlockDetected || (carpenterInteractSent && !playerHasWhiteWool(player))) {
      carpentryUnlockDetected = true;
      setState(State.WAIT_CARPENTRY_UNLOCK_CHAT);
      return;
    }
    if (mc.gui.screen() != null) {
      if (carpenterInteractSent) {
        carpenterGuiOpenedByMacro = true;
      }
      closeCurrentScreen(mc);
      return;
    }
    if (stateTicks > CARPENTER_INTERACT_TIMEOUT_TICKS) {
      fail("Carpenter interact timeout");
      return;
    }
    if (!isHoldingWhiteWool(player)) {
      fail("White Wool not held");
      return;
    }

    final Entity npc = updateNpc(level, false);
    if (npc == null) {
      if (stateTicks > CARPENTER_SEARCH_TIMEOUT_TICKS) {
        fail("Carpenter not found");
      }
      return;
    }
    if (!isWithinInteractRange(player, npc)) {
      approachNpc(mc, player, npc, "Carpenter", CARPENTER_INTERACT_TIMEOUT_TICKS);
      return;
    }
    if (!aimAtEntity(player, npc)) {
      return;
    }
    if (interactCooldown > 0) {
      interactCooldown--;
      return;
    }
    if (mc.options != null) {
      setUseHeld(mc.options, true);
    }
    carpenterInteractSent = true;
    interactCooldown = GuiActionTiming.randomDelayAtLeast(2);
    status = "Interacting with Carpenter";
  }

  private void tickWaitCarpentryUnlock(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);

    if (mc.gui.screen() != null) {
      if (carpenterInteractSent) {
        carpenterGuiOpenedByMacro = true;
      }
      closeCurrentScreen(mc);
      return;
    }
    if (carpentryUnlockDetected || (carpenterInteractSent && !playerHasWhiteWool(player))) {
      setState(State.DONE);
      return;
    }
    if (stateTicks > UNLOCK_CHAT_TIMEOUT_TICKS) {
      fail("Carpenter unlock timeout");
    }
  }

  private void tickDone(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (stateTicks >= DONE_SETTLE_TICKS || mc.gui.screen() == null) {
      finish();
    }
  }

  private void tickFailed(Minecraft mc) {
    releaseAllKeys(mc.options);
    stopAll(status, false);
  }

  // ---- rotation helpers ----

  private boolean aimAtEntity(LocalPlayer player, Entity target) {
    lookTarget = entityLookPoint(target);
    final boolean aimed = isLookWithin(player, lookTarget, AIM_SETTLED_THRESHOLD, desiredAim);
    if (!aimed) {
      aimSettled = false;
      return false;
    }
    if (!aimSettled) {
      aimSettled = true;
      return false;
    }
    return true;
  }

  /**
   * Keep the camera pinned on a known NPC during equip / interact without re-running the settle
   * gate.
   */
  private void keepLookingAt(Entity target) {
    if (target != null) {
      lookTarget = entityLookPoint(target);
    }
  }

  private void approachNpc(
      Minecraft mc, LocalPlayer player, Entity npc, String name, int timeoutTicks) {
    if (stateTicks > timeoutTicks) {
      fail(name + " out of range");
      return;
    }
    status = "Moving closer to " + name;
    if (aimAtEntity(player, npc) && mc.options != null) {
      MacroKeys.set(mc.options.keyUp, true);
    }
  }

  private void clearLook() {
    lookTarget = null;
    RyznBootstrap.rotationManager().resetAim(this);
  }

  // ---- NPC search ----

  private Entity updateNpc(ClientLevel level, boolean weaver) {
    final Entity cached = weaver ? woolWeaverEntity : carpenterEntity;
    final String needle = weaver ? "wool weaver" : "carpenter";
    final int x = weaver ? WOOL_WEAVER_X : CARPENTER_X;
    final int y = weaver ? WOOL_WEAVER_Y : CARPENTER_Y;
    final int z = weaver ? WOOL_WEAVER_Z : CARPENTER_Z;
    final Entity found = ensureNpcEntity(cached, level, needle, x, y, z);
    if (weaver) {
      woolWeaverEntity = found;
    } else {
      carpenterEntity = found;
    }
    return found;
  }

  private Entity ensureNpcEntity(
      Entity cached, ClientLevel level, String needle, int x, int y, int z) {
    if (cached != null
        && cached.isAlive()
        && isNearBlockCenter(cached, x, y, z, NPC_SEARCH_RADIUS)
        && entityNameLower(cached).contains(needle)) {
      return cached;
    }
    return findNearestNamedEntity(level, needle, x, y, z, NPC_SEARCH_RADIUS);
  }

  private boolean isAtExactTarget(LocalPlayer player, int x, int y, int z) {
    final double dx = (x + 0.5) - player.getX();
    final double dz = (z + 0.5) - player.getZ();
    return player.getBlockX() == x
        && player.getBlockZ() == z
        && Math.abs(player.getY() - y) <= 2.0
        && dx * dx + dz * dz <= EXACT_ARRIVAL_DIST_SQ;
  }

  // ---- screen-title checks ----

  private boolean isWoolWeaverScreenOpen(Minecraft mc) {
    return mc.gui.screen() instanceof AbstractContainerScreen<?>
        && titleContains(mc, "wool weaver");
  }

  private boolean isTradingOptionsScreenOpen(Minecraft mc) {
    return mc.gui.screen() instanceof AbstractContainerScreen<?>
        && titleContains(mc, "shop trading options");
  }

  // ---- menu slot finders ----

  private Slot findNextPageSlot(AbstractContainerMenu menu, LocalPlayer player) {
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
          final String text = normalizeSearchText(stackSearchText(stack));
          final boolean textMatch = text.contains("next page") || text.contains("page 2");
          final boolean isArrow = stack.is(Items.ARROW);
          if (!textMatch && !isArrow) {
            return MacroMenu.SKIP;
          }

          int score = 0;
          if (isArrow) {
            score += 20;
          }
          if (textMatch) {
            score += 30;
          }
          return score;
        });
  }

  private Slot findWhiteWoolTradeSlot(AbstractContainerMenu menu, LocalPlayer player) {
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
          if (!name.contains("white wool") && !search.contains("white wool")) {
            return MacroMenu.SKIP;
          }
          if (!search.contains("click to trade")) {
            return MacroMenu.SKIP;
          }
          if (!search.contains("right-click for more trading options")) {
            return MacroMenu.SKIP;
          }

          int score = 20;
          if (stack.is(Items.WOOL.white())) {
            score += 40;
          }
          if (name.contains("white wool")) {
            score += 10;
          }
          return score;
        });
  }

  private Slot findWhiteWool64OptionSlot(AbstractContainerMenu menu, LocalPlayer player) {
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
          if (!name.contains("white wool") && !search.contains("white wool")) {
            return MacroMenu.SKIP;
          }
          if (!search.contains("click to purchase")) {
            return MacroMenu.SKIP;
          }
          if (!hasWhiteWool2048Cost(search)) {
            return MacroMenu.SKIP;
          }
          final boolean x64 = stack.getCount() == 64 || containsStackAmount(search, 64);
          if (!x64) {
            return MacroMenu.SKIP;
          }

          int score = 20;
          if (stack.is(Items.WOOL.white())) {
            score += 40;
          }
          if (stack.getCount() == 64) {
            score += 20;
          }
          if (name.contains("white wool")) {
            score += 10;
          }
          return score;
        });
  }

  private boolean hasWhiteWool2048Cost(String search) {
    final String compact = search.replace(",", "");
    return compact.contains("cost 2048")
        || compact.contains("2048 coins")
        || compact.contains("cost: 2048");
  }

  // ---- inventory helpers ----

  private boolean playerHasWhiteWool(LocalPlayer player) {
    final Inventory inv = player.getInventory();
    for (int i = 0; i < INVENTORY_SIZE; i++) {
      if (isWhiteWoolStack(inv.getItem(i))) {
        return true;
      }
    }
    return false;
  }

  private boolean isHoldingWhiteWool(LocalPlayer player) {
    return player != null && isWhiteWoolStack(player.getMainHandItem());
  }

  private boolean isWhiteWoolStack(ItemStack stack) {
    if (stack == null || stack.isEmpty()) {
      return false;
    }
    if (stack.is(Items.WOOL.white())) {
      return true;
    }
    return normalizeSearchText(stackSearchText(stack)).contains("white wool");
  }

  // ---- text helpers ----

  private boolean isCarpentryUnlockMessage(String message) {
    return message.contains("Here's the recipe for the Carpentry Table")
        || message.contains(
            "You can place it in your world and craft furniture that you've unlocked")
        || message.contains("You can now gain Carpentry XP");
  }

  // ---- input + lifecycle ----

  private void setState(State next) {
    if (next == state) {
      return;
    }
    state = next;
    status = next.label;
    stateTicks = 0;
    pathRequested = false;
    interactCooldown = 0;
    guiClickCooldown = 0;
    aimSettled = false;

    switch (next) {
      case ROTATE_TO_WOOL_WEAVER -> {
        clearLook();
        woolWeaverEntity = null;
      }
      case ROTATE_TO_CARPENTER -> {
        clearLook();
        carpenterEntity = null;
        carpenterGuiOpenedByMacro = false;
        carpenterInteractSent = false;
        whiteWoolSwapRequested = false;
      }
      case CLICK_NEXT_PAGE -> nextPageClicked = false;
      case RIGHT_CLICK_WHITE_WOOL -> whiteWoolRightClicked = false;
      case BUY_WHITE_WOOL_64 -> whiteWool64Clicked = false;
      case EQUIP_WHITE_WOOL -> whiteWoolSwapRequested = false;
      default -> {}
    }
  }

  private void resetRuntime() {
    state = State.IDLE;
    status = "Starting Carpentry Unlock macro";
    stateTicks = 0;
    pathRequested = false;
    interactCooldown = 0;
    guiClickCooldown = 0;
    aimSettled = false;
    woolWeaverEntity = null;
    carpenterEntity = null;
    nextPageClicked = false;
    nextPageRetries = 0;
    whiteWoolRightClicked = false;
    whiteWool64Clicked = false;
    woolGuiOpenedByMacro = false;
    tradingOptionsOpenedByMacro = false;
    carpenterGuiOpenedByMacro = false;
    whiteWoolSwapRequested = false;
    carpenterInteractSent = false;
    carpentryUnlockDetected = false;
    clearLook();
  }

  private void fail(String message) {
    status = message;
    setState(State.FAILED);
    status = message;
  }

  private void finish() {
    status = "Complete";
    stopAll("complete", true);
  }

  private void stopAll(String reason, boolean completed) {
    final Minecraft mc = Minecraft.getInstance();
    releaseAllKeys(mc.options);
    PathfindCommand.stopNavQuiet();
    if (mc.gui.screen() != null && shouldCloseMacroScreen(mc)) {
      closeCurrentScreen(mc);
    }
    resetRuntime();
    enabled = false;
    lastRunCompleted = completed;
    lastStopReason = reason;
    status = "Stopped: " + reason;
  }

  private boolean shouldCloseMacroScreen(Minecraft mc) {
    if (woolGuiOpenedByMacro && isWoolWeaverScreenOpen(mc)) {
      return true;
    }
    if (tradingOptionsOpenedByMacro && isTradingOptionsScreenOpen(mc)) {
      return true;
    }
    return carpenterGuiOpenedByMacro && mc.gui.screen() instanceof AbstractContainerScreen<?>;
  }

  /**
   * @description Applies a run-state request from the menu, command, or failsafe.
   * @param carpentryunlockactionevent the requested run state
   */
  @Subscribe
  public void onCarpentryUnlockActionEvent(CarpentryUnlockActionEvent carpentryunlockactionevent) {
    setEnabled(carpentryunlockactionevent.enabled());
  }
}
