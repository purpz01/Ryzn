package ryzn.macro.client.feature.macros.impl.bea;

import static ryzn.macro.client.MacroInput.releaseAllKeys;
import static ryzn.macro.client.MacroInput.releaseAttackAndUse;
import static ryzn.macro.client.MacroInput.setUseHeld;
import static ryzn.macro.client.MacroInput.setUseHeldOn;
import static ryzn.macro.client.feature.commands.impl.MacroCommands.sendCommand;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.desiredYawPitch;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.entityLookPoint;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.isLookWithin;
import static ryzn.macro.client.util.gui.MacroScreens.closeCurrentScreenIfCursorEmpty;
import static ryzn.macro.client.util.gui.MacroScreens.currentContainerMenu;
import static ryzn.macro.client.util.inventory.MacroSlots.clickSlot;
import static ryzn.macro.client.util.inventory.MacroSlots.findCursorReturnSlot;
import static ryzn.macro.client.util.inventory.MacroSlots.isCursorEmpty;
import static ryzn.macro.client.util.text.MacroText.stripFormatting;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.BeaActionEvent;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.debug.MacroStateTrace;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.feature.macros.api.Macro;
import ryzn.macro.client.feature.rotation.api.RotationActivity;
import ryzn.macro.client.feature.rotation.impl.RouteGaze;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.input.MacroActionTiming;
import ryzn.macro.client.input.MacroKeys;
import ryzn.macro.client.util.inventory.CraftGridFill;
import ryzn.macro.client.util.inventory.CraftGridFill.Outcome;
import ryzn.macro.client.util.inventory.MacroSlots;
import ryzn.macro.client.util.text.RyznChat;
import ryzn.macro.client.util.world.MacroObstruction;

/** Bea: walks to Bea, buys the Bee pet with coins, and summons it. */
public final class BeaMacro extends Macro {

  private static final Pattern BEE_SUMMON_CHAT =
      Pattern.compile("You summoned your (?:\\[Lvl [0-9]+\\] )?Bee!");

  private final float[] desiredAim = new float[2];

  @RequiredArgsConstructor
  private enum State {
    IDLE("Idle"),
    PATHING_TO_BEA("Walking to Bea"),
    OPEN_CRAFT("Opening /craft"),
    WAIT_CRAFT_GUI("Waiting for Craft Item"),
    CRAFT_COAL_BLOCKS("Crafting coal blocks"),
    CRAFT_GOLD_BLOCKS("Crafting gold blocks"),
    CLOSE_CRAFT_MENU("Closing Craft Item"),
    ROTATE_TO_BEA("Aiming at Bea"),
    INTERACT_BEA("Opening Bea menu"),
    BUY_BEE_PET("Buying Bee pet"),
    EQUIP_BEE_PET("Moving Bee to hotbar"),
    RIGHT_CLICK_BEE_PET("Adding Bee pet"),
    WAIT_BEE_SUCCESS_CHAT("Waiting for pet added chat"),
    OPEN_PETS_MENU("Opening /pets"),
    WAIT_PETS_MENU("Waiting for Pets menu"),
    CLICK_BEE_IN_PETS("Summoning Bee"),
    WAIT_BEE_SUMMONED_CHAT("Waiting for summon chat"),
    DONE("Finishing");

    private final String label;
  }

  /** Which half of the run to do. The Level 7 route can resume at either one on its own. */
  public enum Phase {
    /** Walk to Bea and buy + summon the pet. */
    FULL,
    /** Legacy phase retained for existing callers. */
    CRAFT_ONLY,
    /** Walk to Bea and buy + summon the pet. */
    PET_ONLY
  }

  private static final int PATH_TIMEOUT_TICKS = 20 * 45;
  private static final int OPEN_CRAFT_TIMEOUT_TICKS = 20 * 10;
  private static final int WAIT_CRAFT_GUI_TIMEOUT_TICKS = 20 * 15;
  private static final int CRAFT_TIMEOUT_TICKS = 20 * 30;
  private static final int CLOSE_CRAFT_MENU_TIMEOUT_TICKS = 20 * 5;
  private static final int BEA_NPC_SEARCH_TIMEOUT_TICKS = 20 * 10;
  private static final int BEA_MENU_OPEN_TIMEOUT_TICKS = 20 * 25;
  private static final int PET_PURCHASE_TIMEOUT_TICKS = 20 * 20;
  private static final int HOTBAR_MOVE_TIMEOUT_TICKS = 20 * 20;
  private static final int RIGHT_CLICK_TIMEOUT_TICKS = 20 * 8;
  private static final int BEE_SUCCESS_CHAT_TIMEOUT_TICKS = 20 * 20;
  private static final int BEE_PET_EQUIP_DELAY_TICKS = 6;
  private static final int PETS_OPEN_TIMEOUT_TICKS = 20 * 10;
  private static final int PETS_BEE_SLOT_TIMEOUT_TICKS = 20 * 20;
  private static final int PETS_BEE_CLICK_TIMEOUT_TICKS = 20 * 10;
  private static final int BEE_SUMMONED_CHAT_TIMEOUT_TICKS = 20 * 20;
  private static final int CLEANUP_TIMEOUT_TICKS = 20 * 5;
  private static final int BEA_APPROACH_STUCK_TICKS = 60;
  private static final int BEA_APPROACH_REPATH_COOLDOWN_TICKS = 40;
  private static final int REQUIRED_EACH_MATERIAL = 18;
  private static final int CRAFTS_PER_MATERIAL = 2;
  private static final float AIM_SETTLED_THRESHOLD = 2.0f;
  private static final float BEE_EQUIP_MIN_PITCH = 40.0f;
  private static final float BEE_EQUIP_MAX_PITCH = 70.0f;

  @Getter private boolean enabled;
  private State state = State.IDLE;
  private String status = "Disabled";
  private boolean lastRunCompleted;
  private String lastStopReason = "";
  private final MacroActionTiming actionTiming = new MacroActionTiming();
  private final BeaApproachProgress beaApproach = new BeaApproachProgress();
  private int stateTicks;
  private final BeaCraftSession craftSession = new BeaCraftSession();

  /**
   * Shared craft-grid fill + take; owns the click confirms that keep a dropped click from
   * stuttering.
   */
  private final CraftGridFill craftFill = new CraftGridFill();

  private final BeaMenuSlots menuSlots = new BeaMenuSlots();
  private final BeaMacroScreens screens = new BeaMacroScreens();
  private final BeaWorldTarget beaTarget = new BeaWorldTarget();

  /** Watches Bea while the route walks; see {@code RotationManager.externalOwnsLook}. */
  private final RouteGaze gaze = new RouteGaze();

  private Phase phase = Phase.FULL;
  private boolean beaAimSettled;

  /**
   * Consecutive ticks a real player has blocked the click on Bea; past the norm, the bot relocates.
   */
  private int beaBlockedTicks;

  private final BeaPetSession petSession = new BeaPetSession();

  /**
   * How far the pet got, kept across a stopped run so a restart after a kick does not buy a second
   * Bee or click a summoned one away. Cleared once a run completes.
   */
  private boolean beeAddedToMenu;

  private boolean beeSummoned;
  private float beeEquipYaw;
  private float beeEquipPitch;
  private final MacroStateTrace trace = new MacroStateTrace("bea");
  private Vec3 lookTarget;

  /**
   * @description Creates the process-lifetime Bea macro.
   */
  public BeaMacro() {
    super(
        "bea", "Bea", "Buys the Bee pet from Bea with coins and summons it for the Level 7 route.");
  }

  /**
   * @description Applies a run request from the failsafe or a Level 7 route.
   * @param beaactionevent the requested run state or the half of the run to start
   */
  @Subscribe
  public void onBeaActionEvent(BeaActionEvent beaactionevent) {
    switch (beaactionevent.action()) {
      case SET_ENABLED -> setEnabled(beaactionevent.enabled());
      case START -> start(beaactionevent.phase());
      default -> throw new IllegalArgumentException("Unknown Bea action");
    }
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
    final String text = stripFormatting(message.getString());
    if ((state == State.RIGHT_CLICK_BEE_PET || state == State.WAIT_BEE_SUCCESS_CHAT)
        && text.contains("Successfully added Bee to your pet menu!")) {
      petSession.markPetAddedToMenu();
      beeAddedToMenu = true;
    }
    if ((state == State.CLICK_BEE_IN_PETS || state == State.WAIT_BEE_SUMMONED_CHAT)
        && BEE_SUMMON_CHAT.matcher(text.strip()).matches()) {
      petSession.markPetSummoned();
      beeSummoned = true;
    }
  }

  public void setEnabled(boolean on) {
    if (enabled == on) {
      return;
    }
    enabled = on;
    RyznChat.announce("Bea Macro", on);
    if (on) {
      phase = Phase.FULL;
      beginSession();
    } else {
      stopAll("disabled", false);
    }
  }

  /**
   * Start just one half of the run; used by the Level 7 route so each half is its own resumable
   * step.
   *
   * @param requested the half of the run to do
   */
  public void start(Phase requested) {
    if (enabled) {
      stopAll("restarting for " + requested, false);
    }
    phase = requested;
    enabled = true;
    RyznChat.announce("Bea Macro", true);
    beginSession();
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
    if (state == State.RIGHT_CLICK_BEE_PET) {
      return true;
    }
    if (lookTarget == null) {
      return false;
    }
    if (gaze.isOwned()) {
      return true;
    } // watching Bea mid-route; the executor keeps steering the body
    return (state == State.ROTATE_TO_BEA || state == State.INTERACT_BEA)
        && !PathfindCommand.isNavigationActive();
  }

  /**
   * True while the walk to Bea is watching her. Read by {@code RotationManager.externalOwnsLook}.
   *
   * @return true while the route is watching its target with the head
   */
  public boolean ownsRouteLook() {
    return enabled && gaze.isOwned();
  }

  public void onClientTick(Minecraft mc) {
    trace.log(state.name(), status);
    if (!enabled) {
      // Do NOT touch the movement keys here. This runs every tick even when off; releasing keys
      // unconditionally stomps the player's own WASD/sprint every tick. The macro never presses a
      // movement key itself (walking is delegated to PathExecutor), and stopAll() already releases
      // once when the macro is disabled, so there is nothing to clean up while idle.
      return;
    }

    final LocalPlayer player = mc.player;
    final ClientLevel level = mc.level;
    if (player == null || level == null) {
      fail("Waiting for world");
      return;
    }

    switch (state) {
      case PATHING_TO_BEA -> tickPathing(mc, player);
      case OPEN_CRAFT -> tickOpenCraft(mc);
      case WAIT_CRAFT_GUI -> tickWaitCraftGui(mc, player);
      case CRAFT_COAL_BLOCKS -> tickCraftMaterial(mc, player, BeaCraftMaterial.COAL);
      case CRAFT_GOLD_BLOCKS -> tickCraftMaterial(mc, player, BeaCraftMaterial.GOLD);
      case CLOSE_CRAFT_MENU -> tickCloseCraftMenu(mc);
      case ROTATE_TO_BEA -> tickRotateToBea(mc, player, level);
      case INTERACT_BEA -> tickInteractBea(mc, player, level);
      case BUY_BEE_PET -> tickBuyBeePet(mc, player);
      case EQUIP_BEE_PET -> tickEquipBeePet(mc, player);
      case RIGHT_CLICK_BEE_PET -> tickRightClickBeePet(mc, player);
      case WAIT_BEE_SUCCESS_CHAT -> tickWaitBeeSuccessChat();
      case OPEN_PETS_MENU -> tickOpenPetsMenu(mc);
      case WAIT_PETS_MENU -> tickWaitPetsMenu(mc);
      case CLICK_BEE_IN_PETS -> tickClickBeeInPets(mc, player);
      case WAIT_BEE_SUMMONED_CHAT -> tickWaitBeeSummonedChat(mc);
      case DONE -> tickDone(mc);
      default -> beginSession();
    }
  }

  /**
   * Wall-clock aim ease. Driven from the game tick (keeps turning at any fps) and again per render
   * frame.
   */
  public void tickLook() {
    if (!shouldLockMouse() || (PathfindCommand.isNavigationActive() && !gaze.isOwned())) {
      return;
    }
    final LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) {
      return;
    }
    if (state == State.RIGHT_CLICK_BEE_PET) {
      RyznBootstrap.rotationManager()
          .aimStandard(this, player, beeEquipYaw, beeEquipPitch, RotationActivity.GENERAL);
      return;
    }
    if (lookTarget == null) {
      return;
    }
    desiredYawPitch(player.getEyePosition(), lookTarget, desiredAim);
    RyznBootstrap.rotationManager().aimStandard(this, player, desiredAim[0], desiredAim[1]);
  }

  public void onRenderFrame() {
    tickLook();
  }

  private void beginSession() {
    lastRunCompleted = false;
    lastStopReason = "";
    resetRuntime();
    enabled = true;
    setState(resumeState());
  }

  /** Skip whatever part of the pet a stopped run already finished. */
  private State resumeState() {
    if (beeSummoned) {
      return State.DONE;
    }
    if (beeAddedToMenu) {
      return State.OPEN_PETS_MENU;
    }
    final LocalPlayer player = Minecraft.getInstance().player;
    if (player != null && menuSlots.beePetInventoryIndex(player) >= 0) {
      return State.EQUIP_BEE_PET;
    }
    return State.PATHING_TO_BEA;
  }

  private State afterPathingState() {
    return State.ROTATE_TO_BEA;
  }

  private void tickPathing(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);
    // Watch Bea on the way in rather than staring down the path and snapping round on arrival.
    final Entity watch = mc.level == null ? null : beaTarget.resolve(mc.level);
    if (gaze.shouldWatch(player, watch, () -> RyznBootstrap.rotationManager().resetAim(this))) {
      lookTarget = entityLookPoint(watch);
    } else {
      clearLookTarget();
    }

    if (mc.gui.screen() != null) {
      PathfindCommand.stopNavQuiet();
      status = "Paused while a screen is open";
      return;
    }

    if (beaTarget.isNear(player)) {
      PathfindCommand.stopNavQuiet();
      setState(afterPathingState());
      return;
    }

    if (!actionTiming.isPathRequested()) {
      BlockPos position = beaTarget.position();
      final boolean started =
          PathfindCommand.startToQuiet(position.getX(), position.getY(), position.getZ());
      if (!started) {
        fail("Could not start path to Bea");
        return;
      }
      actionTiming.markPathRequested();
      status = "Walking to Bea";
      return;
    }

    // Pathfinder finished or stopped. If we landed close enough, continue the full script instead
    // of
    // aborting; otherwise re-issue the path to close the remaining gap, bounded by the overall
    // timeout.
    if (!PathfindCommand.isNavigationActive() && stateTicks > 20) {
      if (beaTarget.isNear(player)) {
        PathfindCommand.stopNavQuiet();
        setState(afterPathingState());
        return;
      }
      actionTiming.clearPathRequest();
    }

    if (stateTicks > PATH_TIMEOUT_TICKS) {
      fail("Path to Bea timed out");
    }
  }

  private void tickOpenCraft(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (stateTicks > OPEN_CRAFT_TIMEOUT_TICKS) {
      fail("Timed out opening /craft");
      return;
    }
    if (mc.gui.screen() != null) {
      return;
    }
    if (mc.player == null || mc.player.connection == null) {
      fail("Network handler missing");
      return;
    }
    sendCommand(mc, "craft");
    craftSession.markGuiOpened();
    setState(State.WAIT_CRAFT_GUI);
  }

  private void tickWaitCraftGui(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (stateTicks > WAIT_CRAFT_GUI_TIMEOUT_TICKS) {
      fail("Craft Item GUI timeout");
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)
        || !screens.isCraftItemOpen(mc)) {
      status = "Waiting for Craft Item";
      return;
    }

    final AbstractContainerMenu menu = handled.getMenu();
    final int coalCount = menuSlots.countPlayerMaterial(menu, player, Items.COAL);
    final int goldCount = menuSlots.countPlayerMaterial(menu, player, Items.GOLD_INGOT);
    if (coalCount < REQUIRED_EACH_MATERIAL || goldCount < REQUIRED_EACH_MATERIAL) {
      fail("Need 18 coal and 18 gold ingots");
      return;
    }
    setState(State.CRAFT_COAL_BLOCKS);
  }

  private void tickCraftMaterial(Minecraft mc, LocalPlayer player, BeaCraftMaterial material) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (stateTicks > CRAFT_TIMEOUT_TICKS) {
      fail("Timed out crafting " + material.label() + " blocks");
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)
        || !screens.isCraftItemOpen(mc)) {
      fail("Craft Item GUI closed");
      return;
    }
    final AbstractContainerMenu menu = handled.getMenu();

    if (material.craftedCount(craftSession) >= CRAFTS_PER_MATERIAL) {
      if (!isCursorEmpty(menu)) {
        if (!returnCursorStackToPlayerInventory(mc, menu, player)) {
          fail("Cursor return failed");
        }
        return;
      }
      setState(
          material == BeaCraftMaterial.COAL ? State.CRAFT_GOLD_BLOCKS : State.CLOSE_CRAFT_MENU);
      return;
    }

    // The fill/take mechanics are shared (see CraftGridFill): cursor-driven rather than
    // step-counted,
    // so a dropped or late click can never desync into the pick-up/drop stutter.
    final Outcome outcome =
        craftFill.tick(
            mc,
            player,
            menu,
            CraftGridFill.uniform(stack -> stack.is(material.input())),
            stack -> stack.is(material.output()));
    switch (outcome) {
      case CRAFTED -> {
        material.recordCrafted(craftSession);
        status =
            "Crafted "
                + material.label()
                + " block "
                + material.craftedCount(craftSession)
                + " / "
                + CRAFTS_PER_MATERIAL;
        craftFill.begin();
      }
      case OUT_OF_MATERIAL -> fail("Ran out of " + material.label());
      case BLOCKED -> fail("Crafting grid blocked");
      case WORKING -> status = craftFill.status() + " (" + material.label() + ")";

      default -> {}
    }
  }

  private void tickCloseCraftMenu(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (mc.gui.screen() != null) {
      final AbstractContainerMenu menu = currentContainerMenu(mc);
      if (menu != null && !isCursorEmpty(menu)) {
        if (!returnCursorStackToPlayerInventory(mc, menu, mc.player)) {
          fail("Cursor return failed");
        }
        return;
      }
      if (stateTicks > CLOSE_CRAFT_MENU_TIMEOUT_TICKS) {
        fail("Craft close timeout");
        return;
      }
      if (craftSession.shouldRequestClose(stateTicks)) {
        closeCurrentScreen(mc);
      }
      return;
    }
    craftSession.clearGuiOpened();
    craftSession.resetCloseRequest();
    if (phase == Phase.CRAFT_ONLY) {
      setState(State.DONE); // the blocks are made; a later step buys the pet
      return;
    }
    setState(State.ROTATE_TO_BEA);
  }

  private void tickRotateToBea(Minecraft mc, LocalPlayer player, ClientLevel level) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (mc.gui.screen() != null) {
      return;
    }

    Entity beaEntity = beaTarget.resolve(level);
    if (beaEntity == null) {
      if (stateTicks > BEA_NPC_SEARCH_TIMEOUT_TICKS) {
        fail("Bea not found");
      } else {
        status = "Searching for Bea";
      }
      return;
    }

    if (!MacroObstruction.isWithinInteract(player, beaEntity)) {
      approachBea(mc, player, beaEntity);
      return;
    }

    if (aimAtBea(player, beaEntity)) {
      setState(State.INTERACT_BEA);
    }
  }

  private void tickInteractBea(Minecraft mc, LocalPlayer player, ClientLevel level) {
    stateTicks++;
    releaseAllKeys(mc.options);

    if (screens.isBeaOpen(mc)) {
      petSession.markBeaMenuOpened();
      setState(State.BUY_BEE_PET);
      return;
    }
    if (mc.gui.screen() != null) {
      return;
    }
    if (stateTicks > BEA_MENU_OPEN_TIMEOUT_TICKS) {
      fail("Bea GUI timeout");
      return;
    }

    Entity beaEntity = beaTarget.resolve(level);
    if (beaEntity == null) {
      if (stateTicks > BEA_NPC_SEARCH_TIMEOUT_TICKS) {
        fail("Bea not found");
      }
      return;
    }
    if (!MacroObstruction.isWithinInteract(player, beaEntity)) {
      approachBea(mc, player, beaEntity);
      return;
    }
    if (!aimAtBea(player, beaEntity)) {
      return;
    }
    // Interaction norm: the crosshair must be on Bea herself and nobody stacked on the bot. A
    // stranger
    // in the way earns a sidestep instead of a stare-off, and the exact-intent press below means a
    // click can never land on that stranger and open their profile menu.
    if (!MacroInput.isCrosshairOn(mc, beaEntity)
        || MacroObstruction.isPlayerStackedOn(level, player)) {
      setUseHeld(mc.options, false);
      tickBeaBlocked(mc, level, player, beaEntity);
      return;
    }
    beaBlockedTicks = 0;
    if (actionTiming.waitForInteraction()) {
      return;
    }
    if (mc.options != null) {
      setUseHeldOn(mc.options, true, beaEntity);
    }
    actionTiming.armInteraction(GuiActionTiming.randomDelayAtLeast(2));
    status = "Waiting for Bea menu";
  }

  /** Wait out a brief block; a lasting one walks to a tile that can actually see Bea. */
  private void tickBeaBlocked(Minecraft mc, ClientLevel level, LocalPlayer player, Entity bea) {
    status = "Blocked from Bea, waiting";
    if (++beaBlockedTicks < MacroObstruction.BLOCKED_RELOCATE_TICKS) {
      return;
    }
    beaBlockedTicks = 0;
    final BlockPos side =
        MacroObstruction.findSidestep(
            level, player, bea.blockPosition(), entityLookPoint(bea), bea);
    if (side == null) {
      return;
    }
    releaseAllKeys(mc.options);
    status = "Sidestepping for a clear view of Bea";
    PathfindCommand.startToQuiet(side.getX(), side.getY(), side.getZ());
  }

  private void tickBuyBeePet(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);
    clearLookTarget();

    if (stateTicks > PET_PURCHASE_TIMEOUT_TICKS) {
      fail("Bee pet purchase timeout");
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)) {
      if (petSession.isPetPurchaseRequested()) {
        if (actionTiming.waitForMenuClick()) {
          return;
        }
        petSession.clearBeaMenuOpened();
        setState(State.EQUIP_BEE_PET);
      } else {
        status = "Waiting for Bea menu";
      }
      return;
    }
    if (!screens.isBeaOpen(mc)) {
      status = "Waiting for Bea menu";
      return;
    }
    petSession.markBeaMenuOpened();
    if (actionTiming.waitForMenuClick()) {
      return;
    }
    if (petSession.isPetPurchaseRequested()) {
      closeCurrentScreen(mc);
      petSession.clearBeaMenuOpened();
      setState(State.EQUIP_BEE_PET);
      return;
    }
    final Slot petSlot = menuSlots.commonBeeTradeSlot(handled.getMenu(), player);
    if (petSlot == null) {
      status = "Searching Bee trade";
      // Say what was actually on the page; "searching" until timeout tells nobody which name
      // drifted.
      if (stateTicks % 20 == 0) {
        DebugLog.trace(
            DebugLog.Category.MACRO,
            "Bea: no Bee trade in the menu. It offers: %s",
            menuSlots.tradeOffers(handled.getMenu(), player));
      }
      return;
    }
    clickSlot(mc, handled.getMenu(), petSlot.index, 0, ContainerInput.PICKUP, player);
    petSession.markPetPurchaseRequested();
    actionTiming.armMenuClick(GuiActionTiming.randomDelay());
    status = "Clicked Bee trade";
  }

  private void tickEquipBeePet(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (stateTicks > HOTBAR_MOVE_TIMEOUT_TICKS) {
      fail("Bee pet hotbar timeout");
      return;
    }
    if (mc.gui.screen() != null && !(mc.gui.screen() instanceof InventoryScreen)) {
      closeCurrentScreen(mc);
      return;
    }

    final int selected = player.getInventory().getSelectedSlot();
    if (menuSlots.isBeePetStack(player.getInventory().getItem(selected))) {
      if (mc.gui.screen() != null) {
        closeCurrentScreen(mc);
        return;
      }
      if (petSession.shouldWaitForEquipDelay(BEE_PET_EQUIP_DELAY_TICKS)) {
        status = "Waiting for Bee equip";
        return;
      }
      clearLookTarget();
      beeEquipYaw = player.getYRot();
      beeEquipPitch =
          ThreadLocalRandom.current().nextFloat(BEE_EQUIP_MIN_PITCH, BEE_EQUIP_MAX_PITCH);
      setState(State.RIGHT_CLICK_BEE_PET);
      return;
    }

    if (petSession.isHotbarMoveRequested()) {
      status = "Waiting for Bee hotbar move";
      return;
    }

    final int beeSlot = menuSlots.beePetInventoryIndex(player);
    if (beeSlot < 0) {
      status = "Waiting for Bee pet item";
      return;
    }
    if (!MacroSlots.swapInventoryToHotbar(
        mc, player, beeSlot, player.getInventory().getSelectedSlot())) {
      fail("Bee pet hotbar move failed");
      return;
    }
    petSession.markHotbarMoveRequested();
    actionTiming.armMenuClick(GuiActionTiming.randomDelay());
    status = "Moving Bee to selected hotbar slot";
  }

  private void tickRightClickBeePet(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (stateTicks > RIGHT_CLICK_TIMEOUT_TICKS) {
      fail("Bee pet right-click timeout");
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    final int selected = player.getInventory().getSelectedSlot();
    if (!menuSlots.isBeePetStack(player.getInventory().getItem(selected))) {
      status = "Waiting for Bee pet in hand";
      return;
    }
    if (Math.abs(Mth.wrapDegrees(player.getYRot() - beeEquipYaw)) > AIM_SETTLED_THRESHOLD
        || Math.abs(player.getXRot() - beeEquipPitch) > AIM_SETTLED_THRESHOLD
        || player.getXRot() < BEE_EQUIP_MIN_PITCH
        || player.getXRot() > BEE_EQUIP_MAX_PITCH) {
      status = "Aiming down for Bee pet";
      return;
    }
    if (mc.options != null) {
      setUseHeld(mc.options, true);
    }
    setState(State.WAIT_BEE_SUCCESS_CHAT);
    clearLookTarget();
  }

  private void tickWaitBeeSuccessChat() {
    stateTicks++;
    releaseAllKeys(Minecraft.getInstance().options);
    if (petSession.wasPetAddedToMenu()) {
      setState(State.OPEN_PETS_MENU);
      return;
    }
    if (stateTicks > BEE_SUCCESS_CHAT_TIMEOUT_TICKS) {
      fail("Bee success chat timeout");
    }
  }

  private void tickOpenPetsMenu(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (screens.isPetsOpen(mc)) {
      petSession.markPetsMenuOpened();
      setState(State.CLICK_BEE_IN_PETS);
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    if (stateTicks > PETS_OPEN_TIMEOUT_TICKS) {
      fail("Pets command timeout");
      return;
    }
    if (petSession.wasPetsCommandSent()) {
      return;
    }
    if (mc.player == null || mc.player.connection == null) {
      fail("Network handler missing");
      return;
    }
    sendCommand(mc, "pets");
    petSession.markPetsCommandSent();
    setState(State.WAIT_PETS_MENU);
  }

  private void tickWaitPetsMenu(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (stateTicks > PETS_OPEN_TIMEOUT_TICKS) {
      fail("Pets GUI timeout");
      return;
    }
    if (screens.isPetsOpen(mc)) {
      petSession.markPetsMenuOpened();
      setState(State.CLICK_BEE_IN_PETS);
    } else {
      status = "Waiting for Pets menu";
    }
  }

  private void tickClickBeeInPets(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (petSession.wasPetSummoned()) {
      setState(State.WAIT_BEE_SUMMONED_CHAT);
      return;
    }
    if (stateTicks
        > (petSession.wasPetMenuClicked()
            ? PETS_BEE_CLICK_TIMEOUT_TICKS
            : PETS_BEE_SLOT_TIMEOUT_TICKS)) {
      fail(
          petSession.wasPetMenuClicked()
              ? "Bee pet summon click timeout"
              : "Bee pet pets slot timeout");
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> handled)) {
      if (petSession.wasPetMenuClicked()) {
        setState(State.WAIT_BEE_SUMMONED_CHAT);
      } else {
        status = "Waiting for Pets menu";
      }
      return;
    }
    if (!screens.isPetsOpen(mc)) {
      status = "Waiting for Pets menu";
      return;
    }
    petSession.markPetsMenuOpened();
    if (petSession.wasPetMenuClicked()) {
      setState(State.WAIT_BEE_SUMMONED_CHAT);
      return;
    }
    if (actionTiming.waitForMenuClick()) {
      return;
    }
    final Slot beeSlot = menuSlots.beePetSlot(handled.getMenu(), player);
    if (beeSlot == null) {
      status = "Searching Bee in Pets";
      return;
    }
    clickSlot(mc, handled.getMenu(), beeSlot.index, 0, ContainerInput.PICKUP, player);
    petSession.markPetMenuClicked();
    actionTiming.armMenuClick(GuiActionTiming.randomDelay());
    status = "Clicked Bee in Pets";
  }

  private void tickWaitBeeSummonedChat(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (petSession.wasPetSummoned()) {
      if (mc.gui.screen() != null) {
        closeCurrentScreen(mc);
        return;
      }
      setState(State.DONE);
      return;
    }
    if (stateTicks > BEE_SUMMONED_CHAT_TIMEOUT_TICKS) {
      fail("Bee summoned chat timeout");
    }
  }

  private void tickDone(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (stateTicks > CLEANUP_TIMEOUT_TICKS || mc.gui.screen() == null) {
      finish();
    }
  }

  private void setState(State next) {
    if (next == state) {
      return;
    }
    state = next;
    status = next.label;
    stateTicks = 0;
    actionTiming.enterState();
    craftFill.begin();
    beaAimSettled = false;
    resetBeaApproach();
    if (next == State.PATHING_TO_BEA) {
      clearLookTarget();
    }
    if (next == State.CLOSE_CRAFT_MENU) {
      craftSession.resetCloseRequest();
    }
    if (next == State.ROTATE_TO_BEA) {
      beaTarget.clear();
      RyznBootstrap.rotationManager().resetAim(this);
    }
    if (next == State.BUY_BEE_PET) {
      clearLookTarget();
      petSession.resetPetPurchaseRequest();
    }
    if (next == State.EQUIP_BEE_PET) {
      petSession.resetForEquip();
    }
    if (next == State.OPEN_PETS_MENU) {
      petSession.resetForOpenPetsMenu();
    }
    if (next == State.CLICK_BEE_IN_PETS) {
      petSession.resetPetMenuClick();
    }
    if (next == State.WAIT_BEE_SUMMONED_CHAT) {
      petSession.clearPetsMenuOpened();
    }
  }

  private void resetRuntime() {
    state = State.IDLE;
    status = "Starting Bea macro";
    actionTiming.enterState();
    beaApproach.reset();
    stateTicks = 0;
    craftSession.reset();
    beaTarget.clear();
    beaAimSettled = false;
    petSession.reset();
    clearLookTarget();
  }

  private void fail(String message) {
    status = message;
    stopAll(message, false);
  }

  private void finish() {
    beeAddedToMenu = false;
    beeSummoned = false;
    status = "Complete";
    stopAll("complete", true);
  }

  private void stopAll(String reason, boolean completed) {
    final Minecraft mc = Minecraft.getInstance();
    releaseAllKeys(mc.options);
    PathfindCommand.stopNavQuiet();
    if (mc.gui.screen() != null && screens.shouldClose(mc, craftSession, petSession)) {
      closeCurrentScreen(mc);
    }
    resetRuntime();
    enabled = false;
    lastRunCompleted = completed;
    lastStopReason = reason;
    status = "Stopped: " + reason;
  }

  private boolean returnCursorStackToPlayerInventory(
      Minecraft mc, AbstractContainerMenu menu, LocalPlayer player) {
    if (menu == null || menu.getCarried().isEmpty()) {
      return true;
    }
    if (mc == null || mc.gameMode == null || player == null) {
      return false;
    }
    // The callers re-run this every tick until the cursor clears, and a second click while the
    // first
    // is still in flight TAKES THE STACK BACK - that is the put-back stutter. Pace it like any
    // click.
    if (actionTiming.waitForMenuClick()) {
      return true;
    }
    // -1: no preferred slot. The craft loop owns its own cursor returns now, so the only callers
    // left
    // are the tidy-ups outside it, which just need the stack back anywhere it fits.
    final Slot returnSlot = findCursorReturnSlot(menu, player, menu.getCarried(), -1);
    if (returnSlot == null) {
      return false;
    }
    clickSlot(mc, menu, returnSlot.index, 0, ContainerInput.PICKUP, player);
    actionTiming.armMenuClick(GuiActionTiming.randomDelay());
    return true;
  }

  private boolean aimAtBea(LocalPlayer player, Entity target) {
    lookTarget = entityLookPoint(target);
    final boolean aimed = isLookWithin(player, lookTarget, AIM_SETTLED_THRESHOLD, desiredAim);
    if (!aimed) {
      beaAimSettled = false;
      status = "Aiming at Bea";
      return false;
    }
    if (!beaAimSettled) {
      beaAimSettled = true;
      return false;
    }
    return true;
  }

  private void approachBea(Minecraft mc, LocalPlayer player, Entity target) {
    if (MacroObstruction.isWithinInteract(player, target)) {
      releaseAllKeys(mc.options);
      resetBeaApproach();
      return;
    }
    if (stateTicks > BEA_MENU_OPEN_TIMEOUT_TICKS) {
      fail("Bea out of range");
      return;
    }

    final Vec3 beaLook = entityLookPoint(target);
    final double distanceSq = player.getEyePosition().distanceToSqr(beaLook);
    if (beaApproach.shouldRepath(
        distanceSq,
        0.04,
        6.25 * 6.25,
        BEA_APPROACH_STUCK_TICKS,
        BEA_APPROACH_REPATH_COOLDOWN_TICKS)) {
      releaseAllKeys(mc.options);
      setState(State.PATHING_TO_BEA);
      status = "Walking to Bea";
      return;
    }

    final boolean aimed = aimAtBea(player, target);
    status = "Moving closer to Bea";
    final Options opts = mc.options;
    if (opts == null) {
      return;
    }
    MacroKeys.set(opts.keyUp, aimed);
    MacroKeys.set(opts.keyDown, false);
    MacroKeys.set(opts.keyLeft, false);
    MacroKeys.set(opts.keyRight, false);
    MacroKeys.set(opts.keyJump, false);
    MacroKeys.set(opts.keyShift, false);
    MacroKeys.set(opts.keySprint, false);
    releaseAttackAndUse(opts);
  }

  private void resetBeaApproach() {
    beaApproach.resetProgress();
    beaBlockedTicks = 0;
  }

  private void closeCurrentScreen(Minecraft mc) {
    if (mc.gui.screen() == null) {
      return;
    }
    if (screens.isCraftItemOpen(mc) && !closeCurrentScreenIfCursorEmpty(mc)) {
      status = "Refusing to close Craft Item with held cursor";
      return;
    }
    ryzn.macro.client.util.gui.MacroScreens.closeCurrentScreen(mc);
  }

  private void clearLookTarget() {
    lookTarget = null;
    RyznBootstrap.rotationManager().resetAim(this);
  }
}
