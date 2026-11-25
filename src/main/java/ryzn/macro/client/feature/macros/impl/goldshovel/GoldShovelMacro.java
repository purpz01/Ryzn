package ryzn.macro.client.feature.macros.impl.goldshovel;

import static ryzn.macro.client.MacroInput.releaseAllKeys;
import static ryzn.macro.client.MacroInput.releaseMovementAndAttack;
import static ryzn.macro.client.MacroInput.setUseHeld;
import static ryzn.macro.client.MacroInput.setUseHeldOn;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.entityLookPoint;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.settings.ConfigSection;
import ryzn.macro.client.config.settings.Setting;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.GoldShovelActionEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.debug.MacroStateTrace;
import ryzn.macro.client.feature.macros.api.Macro;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.feature.rotation.api.LookController;
import ryzn.macro.client.feature.rotation.impl.RouteGaze;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.input.MacroActionTiming;
import ryzn.macro.client.input.MacroKeys;
import ryzn.macro.client.util.text.RyznChat;
import ryzn.macro.client.util.world.MacroObstruction;

/**
 * Ryzn-native Gold Shovel macro. Buys Stick x32 from the Lumber Merchant, buys an Experience Bottle
 * stack from the Librarian, throws the bottles while aimed down, crafts a Golden Axe and a Golden
 * Shovel via {@code /craft}, then enchants the shovel with Efficiency II at a nearby enchanting
 * table. With the shovel done it goes back to the Librarian for three more bottle stacks, throws
 * them until the vanilla exp bar reads level 30, and puts Efficiency V on the axe at the same
 * table. Walking uses Ryzn's native pathfinder ({@link WalkingNavigation}); rotation, GUI handling,
 * crafting and slot logic mirror the proven Ryzn Bea macro (self-contained).
 *
 * <p>The supply, throw and enchant states are shared by both legs; {@link #target} says which tool
 * is being worked on, and every place the legs differ reads it rather than duplicating a state.
 */
public final class GoldShovelMacro extends Macro implements ConfigSection {

  private final GoldShovelWorkshop workshop = new GoldShovelWorkshop(this);

  private final GoldShovelExperience experience = new GoldShovelExperience(this);

  @RequiredArgsConstructor
  enum State {
    IDLE("Idle"),
    PATHING_TO_LUMBER("Walking to Lumber Merchant"),
    ROTATE_TO_LUMBER("Aiming at Lumber Merchant"),
    INTERACT_LUMBER("Opening Lumber Merchant"),
    BUY_STICKS("Buying Stick x32"),
    CLOSE_LUMBER_MENU("Closing Lumber Merchant"),
    PATHING_TO_LIBRARIAN("Walking to Librarian"),
    ROTATE_TO_LIBRARIAN("Aiming at Librarian"),
    INTERACT_LIBRARIAN("Opening Librarian"),
    RIGHT_CLICK_EXP_BOTTLE("Opening Experience Bottle options"),
    BUY_EXP_BOTTLE_STACK("Buying Experience Bottle stack"),
    CLOSE_LIBRARIAN_MENU("Closing Librarian"),
    EQUIP_EXP_BOTTLES("Equipping Experience Bottles"),
    ROTATE_DOWN("Aiming down"),
    THROW_EXP_BOTTLES("Throwing Experience Bottles"),
    EVADE_PLAYER("Moving away from a player"),
    RETURN_TO_LIBRARIAN("Walking back to the Librarian"),
    HOP_SEND_IS("Leaving the lobby"),
    HOP_WAIT_IS("Waiting on the island"),
    HOP_SEND_HUB("Returning to the Hub"),
    HOP_SETTLE("Waiting for the Hub"),
    OPEN_CRAFT("Opening /craft"),
    WAIT_CRAFT_GUI("Waiting for Craft Item"),
    CRAFT_AXE("Crafting Golden Axe"),
    CRAFT_SHOVEL("Crafting Golden Shovel"),
    MOVE_SHOVEL_TO_HOTBAR("Moving Golden Shovel to hotbar"),
    CLOSE_CRAFT_MENU("Closing Craft Item"),
    MOVE_TARGET_TO_HOTBAR("Moving tool to hotbar"),
    ROTATE_TO_ENCHANT_TABLE("Aiming at enchanting table"),
    OPEN_ENCHANT_TABLE("Opening enchanting table"),
    WAIT_ENCHANT_GUI("Waiting for Enchant Item"),
    INSERT_TOOL("Inserting the tool"),
    CLICK_ENCHANTMENT("Clicking enchantment"),
    CLICK_ENCHANT_LEVEL("Clicking enchantment level"),
    WAIT_ENCHANT_CHAT("Waiting for enchant chat"),
    CLOSE_ENCHANT_MENU("Closing Enchant Item"),
    DONE("Finishing");

    private final String label;
  }

  private static final int PATH_TIMEOUT_TICKS = 20 * 45;
  private static final int NPC_SEARCH_TIMEOUT_TICKS = 20 * 10;
  private static final int INTERACT_TIMEOUT_TICKS = 20 * 25;

  private static final int DONE_SETTLE_TICKS = 5;

  @Getter private boolean enabled;
  State state = State.IDLE;
  String status = "Disabled";
  private boolean lastRunCompleted;
  private String lastStopReason = "";

  int stateTicks;

  /**
   * Consecutive ticks a real player has blocked the NPC click; past the norm, the bot relocates.
   */
  private int blockedTicks;

  /** Which tool the shared supply / throw / enchant states are working for right now. */
  GoldShovelMenuSlots.EnchantTarget target;

  final GoldShovelCraftSession craftSession = new GoldShovelCraftSession();

  final GoldShovelEnchantSession enchantSession = new GoldShovelEnchantSession();
  final GoldShovelMenuSlots menuSlots = new GoldShovelMenuSlots();
  private final GoldShovelMacroScreens screens = new GoldShovelMacroScreens(menuSlots);

  /** Watches the NPC being walked to; see {@code RotationManager.externalOwnsLook}. */
  private final RouteGaze gaze = new RouteGaze();

  final GoldShovelWorldTargets worldTargets = new GoldShovelWorldTargets();

  final MacroActionTiming actionTiming = new MacroActionTiming();
  final GoldShovelCraftGrid craftGrid =
      new GoldShovelCraftGrid(craftSession, menuSlots, actionTiming);
  final GoldShovelEnchantActions enchantActions =
      new GoldShovelEnchantActions(enchantSession, menuSlots, actionTiming);
  private final MacroStateTrace trace = new MacroStateTrace("goldshovel");

  /** Saved run flag; startup, import, and export clear it through the run-toggle list. */
  private final Setting<Boolean> savedEnabled =
      addSetting(Setting.bool("gold_shovel_macro_enabled", "Enabled", false));

  /**
   * @description Creates the process-lifetime Gold Shovel macro.
   */
  public GoldShovelMacro() {
    super(
        "gold_shovel",
        "Gold Shovel",
        "Buys supplies, crafts a Golden Shovel and Golden Axe, and enchants both for"
            + " the Level 7 route.");
  }

  /**
   * @description Loads the saved run flag under its original key.
   * @param object the section JSON; a missing or mistyped value falls back to off
   */
  @Override
  public void read(JsonObject object) {
    ConfigSection.readSettings(object, savedEnabled);
  }

  /**
   * @description Writes the saved run flag under its original key.
   * @return a new section object for the settings file
   */
  @Override
  public JsonObject write() {
    return ConfigSection.writeSettings(savedEnabled);
  }

  /**
   * @description Applies a run request from the command, the failsafe, or a Level 7 route.
   * @param goldshovelactionevent the request; step and Rev lvl 2 starts record whether they started
   */
  @Subscribe
  public void onGoldShovelActionEvent(GoldShovelActionEvent goldshovelactionevent) {
    switch (goldshovelactionevent.action()) {
      case SET_ENABLED -> setEnabled(goldshovelactionevent.flag());
      case START_AT ->
          goldshovelactionevent.setStarted(
              startAt(goldshovelactionevent.step(), goldshovelactionevent.flag()));
      case START_UNDEAD_SWORD_SMITE -> goldshovelactionevent.setStarted(startUndeadSwordSmite());
      default -> throw new IllegalArgumentException("Unknown Gold Shovel action");
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
    if (state != State.CLICK_ENCHANT_LEVEL
        && state != State.WAIT_ENCHANT_CHAT
        && state != State.CLOSE_ENCHANT_MENU) {
      return;
    }
    // Only the leg's own line confirms: the shovel's Efficiency II must not end the axe's
    // wait.
    if (normalizeSearchText(message.getString()).contains(target.confirmationText())) {
      enchantSession.markEnchantConfirmed();
    }
  }

  /**
   * Level 7 is the only thing that starts this now (it has no menu card), so a stale enabled flag
   * must not resume it on launch - the run would have no GUI switch to stop it.
   */
  public void applyConfig() {
    enabled = false;
    savedEnabled.set(false);
  }

  public void setEnabled(boolean on) {
    if (enabled == on) {
      persist();
      return;
    }
    enabled = on;
    RyznChat.announce("Gold Shovel", on);
    if (on) {
      beginSession();
    } else {
      stopAll("disabled", false);
    }
    persist();
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

  /** A {@link #startAt} step plus its leg; a null step means the top of the run. */
  public record ResumePoint(String step, boolean axeLeg) {}

  /**
   * Where a run cut off right now should pick back up, so a kick never repeats a purchase, craft or
   * enchant that already went through (the 32 gold is spent by the craft, so a second craft fails).
   *
   * @return the step to restart at for the current state
   */
  public ResumePoint resumePoint() {
    final boolean axeLeg = target == GoldShovelMenuSlots.EnchantTarget.AXE;
    return switch (state) {
      case IDLE, PATHING_TO_LUMBER, ROTATE_TO_LUMBER, INTERACT_LUMBER, BUY_STICKS ->
          new ResumePoint(null, false);
      case CLOSE_LUMBER_MENU,
          PATHING_TO_LIBRARIAN,
          ROTATE_TO_LIBRARIAN,
          INTERACT_LIBRARIAN,
          RIGHT_CLICK_EXP_BOTTLE,
          BUY_EXP_BOTTLE_STACK ->
          new ResumePoint(State.PATHING_TO_LIBRARIAN.name(), axeLeg);
      case RETURN_TO_LIBRARIAN -> new ResumePoint(experience.returnNext.name(), axeLeg);
      case CLOSE_LIBRARIAN_MENU,
          EQUIP_EXP_BOTTLES,
          ROTATE_DOWN,
          THROW_EXP_BOTTLES,
          EVADE_PLAYER,
          HOP_SEND_IS,
          HOP_WAIT_IS,
          HOP_SEND_HUB,
          HOP_SETTLE ->
          new ResumePoint(State.EQUIP_EXP_BOTTLES.name(), axeLeg);
      case OPEN_CRAFT, WAIT_CRAFT_GUI, CRAFT_AXE, CRAFT_SHOVEL ->
          new ResumePoint(State.OPEN_CRAFT.name(), axeLeg);
      case MOVE_SHOVEL_TO_HOTBAR,
          CLOSE_CRAFT_MENU,
          MOVE_TARGET_TO_HOTBAR,
          ROTATE_TO_ENCHANT_TABLE,
          OPEN_ENCHANT_TABLE,
          WAIT_ENCHANT_GUI,
          INSERT_TOOL,
          CLICK_ENCHANTMENT,
          CLICK_ENCHANT_LEVEL,
          WAIT_ENCHANT_CHAT ->
          new ResumePoint(State.MOVE_TARGET_TO_HOTBAR.name(), axeLeg);
      // The enchant landed: the shovel's is the halfway point, the axe's is the end.
      case CLOSE_ENCHANT_MENU, DONE ->
          axeLeg
              ? new ResumePoint(State.DONE.name(), true)
              : new ResumePoint(State.PATHING_TO_LIBRARIAN.name(), true);
    };
  }

  public boolean shouldLockMouse() {
    if (!enabled) {
      return false;
    }
    if (gaze.isOwned()) {
      return true;
    } // watching the NPC mid-route; the executor keeps the body
    if (WalkingNavigation.isNavigationActive()) {
      return false;
    }
    return switch (state) {
      case ROTATE_TO_LUMBER,
          INTERACT_LUMBER,
          ROTATE_TO_LIBRARIAN,
          INTERACT_LIBRARIAN,
          ROTATE_DOWN,
          THROW_EXP_BOTTLES,
          ROTATE_TO_ENCHANT_TABLE,
          OPEN_ENCHANT_TABLE ->
          true;
      default -> false;
    };
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
      case PATHING_TO_LUMBER ->
          tickPathing(
              mc, player, worldTargets.lumberPosition(), State.ROTATE_TO_LUMBER, "Lumber Merchant");
      case ROTATE_TO_LUMBER -> tickRotateToNpc(mc, player, level, true);
      case INTERACT_LUMBER -> tickInteractNpc(mc, player, level, true);
      case BUY_STICKS -> experience.tickBuySticks(mc, player);
      case CLOSE_LUMBER_MENU -> experience.tickCloseLumberMenu(mc);
      case PATHING_TO_LIBRARIAN ->
          tickPathing(
              mc, player, worldTargets.librarianPosition(), State.ROTATE_TO_LIBRARIAN, "Librarian");
      case ROTATE_TO_LIBRARIAN -> tickRotateToNpc(mc, player, level, false);
      case INTERACT_LIBRARIAN -> tickInteractNpc(mc, player, level, false);
      case RIGHT_CLICK_EXP_BOTTLE -> experience.tickRightClickExpBottle(mc, player);
      case BUY_EXP_BOTTLE_STACK -> experience.tickBuyExpBottleStack(mc, player);
      case CLOSE_LIBRARIAN_MENU -> experience.tickCloseLibrarianMenu(mc);
      case EQUIP_EXP_BOTTLES -> experience.tickEquipExpBottles(mc, player);
      case ROTATE_DOWN -> experience.tickRotateDown(mc, player);
      case THROW_EXP_BOTTLES -> experience.tickThrowExpBottles(mc, player);
      case EVADE_PLAYER -> experience.tickEvadePlayer(mc, player, level);
      case RETURN_TO_LIBRARIAN ->
          tickPathing(
              mc, player, worldTargets.librarianPosition(), experience.returnNext, "Librarian");
      case HOP_SEND_IS -> experience.tickHopSendIs(mc);
      case HOP_WAIT_IS -> experience.tickHopWaitIs(mc);
      case HOP_SEND_HUB -> experience.tickHopSendHub(mc);
      case HOP_SETTLE -> experience.tickHopSettle(mc, player);
      case OPEN_CRAFT -> workshop.tickOpenCraft(mc);
      case WAIT_CRAFT_GUI -> workshop.tickWaitCraftGui(mc, player);
      case CRAFT_AXE -> workshop.tickCraftAxe(mc, player);
      case CRAFT_SHOVEL -> workshop.tickCraftShovel(mc, player);
      case MOVE_SHOVEL_TO_HOTBAR -> workshop.tickMoveShovelToHotbar(mc, player);
      case CLOSE_CRAFT_MENU -> workshop.tickCloseCraftMenu(mc);
      case MOVE_TARGET_TO_HOTBAR -> workshop.tickMoveTargetToHotbar(mc, player);
      case ROTATE_TO_ENCHANT_TABLE -> workshop.tickRotateToEnchantTable(mc, player, level);
      case OPEN_ENCHANT_TABLE -> workshop.tickOpenEnchantTable(mc, player);
      case WAIT_ENCHANT_GUI -> workshop.tickWaitEnchantGui(mc);
      case INSERT_TOOL -> workshop.tickInsertTool(mc, player);
      case CLICK_ENCHANTMENT -> workshop.tickClickEnchantment(mc, player);
      case CLICK_ENCHANT_LEVEL -> workshop.tickClickEnchantLevel(mc, player);
      case WAIT_ENCHANT_CHAT -> workshop.tickWaitEnchantChat(mc);
      case CLOSE_ENCHANT_MENU -> workshop.tickCloseEnchantMenu(mc);
      case DONE -> tickDone(mc);
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
    if (!shouldLockMouse() || (WalkingNavigation.isNavigationActive() && !gaze.isOwned())) {
      return;
    }
    final LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) {
      return;
    }
    look().tick(player);
  }

  public void onRenderFrame() {
    tickLook();
  }

  /**
   * Every step a run can be dropped into, in order. Index 0 here is step 1 in the menu dropdown.
   */
  private static State[] steps() {
    final List<State> steps = new ArrayList<>();
    for (State value : State.values()) {
      if (value != State.IDLE) {
        steps.add(value);
      }
    }
    return steps.toArray(new State[0]);
  }

  /**
   * Starts the run at one step instead of the top, so the bottle, craft and enchant legs can be
   * tested without walking the whole route first. The step's own preconditions are the tester's
   * problem.
   *
   * @param step the step name, matched without case
   * @param axeLeg true to run the step as the axe leg
   * @return true when a step with that name exists and the run started
   */
  public boolean startAt(String step, boolean axeLeg) {
    for (State value : steps()) {
      if (value.name().equalsIgnoreCase(step)) {
        begin(value, axeLeg);
        return true;
      }
    }
    return false;
  }

  /**
   * Runs only the Rev level 2 supply and enchant leg after the starter sword is bought.
   *
   * @return true when the leg started, false when a run was already going
   */
  public boolean startUndeadSwordSmite() {
    if (enabled) {
      return false;
    }
    resetRuntime();
    lastRunCompleted = false;
    lastStopReason = "";
    enabled = true;
    target = GoldShovelMenuSlots.EnchantTarget.UNDEAD_SWORD;
    experience.supplySession.beginExpBottleVisit();
    setState(State.PATHING_TO_LIBRARIAN);
    RyznChat.announce("Gold Shovel", true);
    persist();
    return true;
  }

  private void begin(State entry, boolean axeLeg) {
    resetRuntime();
    lastRunCompleted = false;
    lastStopReason = "";
    enabled = true;
    target =
        axeLeg ? GoldShovelMenuSlots.EnchantTarget.AXE : GoldShovelMenuSlots.EnchantTarget.SHOVEL;
    experience.supplySession.beginExpBottleVisit();
    enterAt(entry);
    RyznChat.announce("Gold Shovel", true);
    persist();
  }

  /**
   * A step entered from anywhere, e.g. the Hub spawn after a kick: the throw and enchant steps work
   * around the Librarian, so walk there first, and a throw with nothing left to throw moves on.
   */
  private void enterAt(State entry) {
    final LocalPlayer player = Minecraft.getInstance().player;
    State next = entry;
    if (player != null
        && next == State.EQUIP_EXP_BOTTLES
        && !menuSlots.playerHasExperienceBottles(player)) {
      next = target.requiredExperienceLevel() > 0 ? State.PATHING_TO_LIBRARIAN : State.OPEN_CRAFT;
    }
    if (player != null
        && (next == State.EQUIP_EXP_BOTTLES
            || next == State.MOVE_TARGET_TO_HOTBAR
            || next == State.ROTATE_TO_ENCHANT_TABLE)) {
      experience.leaveThrowSpot(player, next);
      return;
    }
    setState(next);
  }

  private void beginSession() {
    resetRuntime();
    lastRunCompleted = false;
    lastStopReason = "";
    enabled = true;
    setState(State.PATHING_TO_LUMBER);
  }

  /**
   * The shovel is enchanted; go back to the Librarian for the axe's bottles and run the same
   * states.
   */
  void beginAxeLeg() {
    target = GoldShovelMenuSlots.EnchantTarget.AXE;
    experience.levelFiveSupplyTrips = 0;
    experience.supplySession.clearLibrarianScreens();
    enchantSession.reset();
    setState(State.PATHING_TO_LIBRARIAN);
  }

  // ---- pathing ----

  private void tickPathing(
      Minecraft mc, LocalPlayer player, BlockPos target, State next, String label) {
    stateTicks++;
    releaseAllKeys(mc.options);
    // Watch the NPC on the way in rather than staring down the path and snapping round on arrival.
    final Entity watch =
        mc.level == null ? null : worldTargets.npc(mc.level, state == State.PATHING_TO_LUMBER);
    if (!gaze.update(player, watch, look())) {
      look().clear();
    }

    if (mc.gui.screen() != null) {
      WalkingNavigation.stopNavQuiet();
      status = "Paused while a screen is open";
      return;
    }

    if (worldTargets.isAtTarget(player, target)) {
      WalkingNavigation.stopNavQuiet();
      setState(next);
      return;
    }

    if (!actionTiming.isPathRequested()) {
      if (!WalkingNavigation.startToQuiet(target.getX(), target.getY(), target.getZ())) {
        fail("Could not start path to " + label);
        return;
      }
      actionTiming.markPathRequested();
      status = "Walking to " + label;
      return;
    }

    if (!WalkingNavigation.isNavigationActive() && stateTicks > 20) {
      // The route routinely finishes a step short of the exact block, and momentum can drift the
      // body
      // off it after the executor lets go. Only an end far from the target is a real no-path
      // answer;
      // from anywhere near it the rotate state walks the last bit in on its own.
      if (worldTargets.isNearTarget(player, target)) {
        setState(next);
        return;
      }
      fail("Path to " + label + " stopped early");
      return;
    }

    if (stateTicks > PATH_TIMEOUT_TICKS) {
      fail("Path to " + label + " timed out");
    }
  }

  // ---- NPC rotation + interaction ----

  private void tickRotateToNpc(
      Minecraft mc, LocalPlayer player, ClientLevel level, boolean lumber) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (mc.gui.screen() != null) {
      return;
    }

    final Entity npc = worldTargets.npc(level, lumber);
    if (npc == null) {
      if (stateTicks > NPC_SEARCH_TIMEOUT_TICKS) {
        fail((lumber ? "Lumber Merchant" : "Librarian") + " not found");
      } else {
        status = "Searching for " + (lumber ? "Lumber Merchant" : "Librarian");
      }
      return;
    }

    // Interaction norm: be within 3 blocks before handing off to the click state. The aim-range
    // gate
    // reads as "in range" a block past the server's actual reach, which clicks at nothing.
    if (!MacroObstruction.isWithinInteract(player, npc)) {
      if (stateTicks > INTERACT_TIMEOUT_TICKS) {
        fail((lumber ? "Lumber Merchant" : "Librarian") + " out of range");
        return;
      }
      status = "Moving closer to " + (lumber ? "Lumber Merchant" : "Librarian");
      if (look().aimAtEntity(player, npc) && mc.options != null) {
        MacroKeys.set(mc.options.keyUp, true);
      }
      return;
    }

    if (look().aimAtEntity(player, npc)) {
      setState(lumber ? State.INTERACT_LUMBER : State.INTERACT_LIBRARIAN);
    }
  }

  private void tickInteractNpc(
      Minecraft mc, LocalPlayer player, ClientLevel level, boolean lumber) {
    stateTicks++;
    releaseAllKeys(mc.options);

    if (lumber ? menuSlots.isLumberMerchantScreen(mc) : menuSlots.isLibrarianScreen(mc)) {
      if (lumber) {
        experience.supplySession.markLumberGuiOpened();
        setState(State.BUY_STICKS);
      } else {
        experience.supplySession.markLibrarianGuiOpened();
        setState(State.RIGHT_CLICK_EXP_BOTTLE);
      }
      return;
    }
    if (mc.gui.screen() != null) {
      return;
    }
    if (stateTicks > INTERACT_TIMEOUT_TICKS) {
      fail((lumber ? "Lumber Merchant" : "Librarian") + " GUI timeout");
      return;
    }

    final Entity npc = worldTargets.npc(level, lumber);
    if (npc == null) {
      if (stateTicks > NPC_SEARCH_TIMEOUT_TICKS) {
        fail((lumber ? "Lumber Merchant" : "Librarian") + " not found");
      }
      return;
    }
    final String name = lumber ? "Lumber Merchant" : "Librarian";
    if (!MacroObstruction.isWithinInteract(player, npc)) {
      status = "Moving closer to " + name;
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
      tickNpcBlocked(mc, level, player, npc, name);
      return;
    }
    blockedTicks = 0;
    if (actionTiming.waitForInteraction()) {
      return;
    }
    if (mc.options != null) {
      setUseHeldOn(mc.options, true, npc);
    }
    actionTiming.armInteraction(GuiActionTiming.randomDelayAtLeast(2));
    status = "Opening " + name;
  }

  /** Wait out a brief block; a lasting one walks to a tile that can actually see the NPC. */
  private void tickNpcBlocked(
      Minecraft mc, ClientLevel level, LocalPlayer player, Entity npc, String name) {
    status = "Blocked from " + name + ", waiting";
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
    status = "Sidestepping for a clear view of " + name;
    WalkingNavigation.startToQuiet(side.getX(), side.getY(), side.getZ());
  }

  // ---- keeping thrown experience away from other players ----

  // ---- lobby swap: /is, then /hub, then back to the Librarian ----

  // ---- crafting the Golden Shovel ----

  // ---- enchanting ----

  private void tickDone(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (stateTicks >= DONE_SETTLE_TICKS || mc.gui.screen() == null) {
      finish();
    }
  }

  void closeCurrentScreen(Minecraft mc) {
    if (!screens.closeCurrent(mc)) {
      status = "Refusing to close Craft Item with held cursor";
    }
  }

  // ---- text helpers ----

  // ---- input + lifecycle ----

  /**
   * Release movement + attack but leave keyUse alone, so callers can hold right-click (throw /
   * open).
   */
  void releaseMovementKeepUse(Options opts) {
    if (opts == null) {
      return;
    }
    WalkingNavigation.stopNavQuiet();
    releaseMovementAndAttack(opts);
  }

  void setUse(Options opts, boolean held) {
    if (opts != null) {
      setUseHeld(opts, held);
    }
  }

  void setState(State next) {
    if (next == state) {
      return;
    }
    if (next != State.PATHING_TO_LUMBER
        && next != State.PATHING_TO_LIBRARIAN
        && next != State.RETURN_TO_LIBRARIAN) {
      gaze.release();
    }
    state = next;
    status = next.label;
    stateTicks = 0;
    actionTiming.enterState();
    look().resetSettle();

    switch (next) {
      case ROTATE_TO_LUMBER -> {
        look().clear();
        worldTargets.clearLumber();
      }
      case PATHING_TO_LIBRARIAN -> {
        // One visit = one baseline for the stacks bought, even if the menu has to be re-opened.
        experience.supplySession.beginExpBottleVisit();
        if (target.requiredExperienceLevel() > 0) {
          experience.levelFiveSupplyTrips++;
        }
      }
      case ROTATE_TO_LIBRARIAN -> {
        look().clear();
        worldTargets.clearLibrarian();
      }
      case ROTATE_DOWN, ROTATE_TO_ENCHANT_TABLE -> {
        look().clear();
        if (next == State.ROTATE_TO_ENCHANT_TABLE) {
          enchantSession.clearTable();
        }
      }
      case BUY_STICKS -> experience.supplySession.resetStickClickRequest();
      case RIGHT_CLICK_EXP_BOTTLE -> {
        look().clear();
        experience.supplySession.resetExpBottleOptionsRequest();
      }
      case EQUIP_EXP_BOTTLES -> experience.supplySession.resetExpBottleSwapRequest();
      case OPEN_CRAFT -> {
        look().clear();
        craftSession.resetForOpenCraft();
      }
      case CRAFT_AXE, CRAFT_SHOVEL -> {
        craftSession.resetProgress();
        craftGrid.begin(); // second begin() reuses the same open menu
      }
      // The target tool borrows the craft session's swap marker; it means "a hotbar swap click is
      // in flight".
      case MOVE_SHOVEL_TO_HOTBAR, MOVE_TARGET_TO_HOTBAR -> craftSession.resetHotbarMoveRequest();
      case CLOSE_CRAFT_MENU -> craftSession.resetCloseRequest();
      case WAIT_ENCHANT_GUI -> look().clear();
      case INSERT_TOOL -> enchantSession.resetToolInsertionRequest();
      case CLICK_ENCHANTMENT -> enchantSession.resetEfficiencyClickRequest();
      case CLICK_ENCHANT_LEVEL -> enchantSession.resetForEnchantLevel();
      case CLOSE_ENCHANT_MENU -> enchantSession.resetCloseRequest();
      default -> {}
    }
  }

  private void resetRuntime() {
    gaze.release();
    state = State.IDLE;
    status = "Starting Gold Shovel macro";
    stateTicks = 0;
    target = GoldShovelMenuSlots.EnchantTarget.SHOVEL;
    experience.levelFiveSupplyTrips = 0;
    experience.evasions = 0;
    experience.evadeTarget = null;
    experience.returnNext = State.EQUIP_EXP_BOTTLES;
    experience.crowdGuard.reset();
    actionTiming.enterState();
    look().resetSettle();
    worldTargets.reset();
    experience.supplySession.reset();
    craftSession.reset();
    enchantSession.reset();
    look().clear();
  }

  void fail(String message) {
    status = message;
    stopAll(message, false);
  }

  private void finish() {
    status = "Complete";
    stopAll("complete", true);
  }

  private void stopAll(String reason, boolean completed) {
    final Minecraft mc = Minecraft.getInstance();
    releaseAllKeys(mc.options);
    WalkingNavigation.stopNavQuiet();
    if (mc.gui.screen() != null
        && screens.shouldClose(mc, experience.supplySession, craftSession, enchantSession)) {
      closeCurrentScreen(mc);
    }
    resetRuntime();
    enabled = false;
    lastRunCompleted = completed;
    lastStopReason = reason;
    status = "Stopped: " + reason;
    persist();
  }

  private void persist() {
    savedEnabled.set(enabled);
    RyznSettings.get().save();
  }

  /**
   * @description Returns this macro's aim controller from the shared rotation subsystem.
   * @return the controller the rotation manager keeps for this owner
   */
  LookController look() {
    return RyznBootstrap.rotationManager().lookController(this, 90f);
  }
}
