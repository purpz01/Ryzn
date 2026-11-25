package ryzn.macro.client.feature.macros.impl.foragingquest;

import static ryzn.macro.client.MacroInput.releaseAllKeys;
import static ryzn.macro.client.MacroInput.setUseHeld;
import static ryzn.macro.client.util.gui.MacroScreens.closeCurrentScreen;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.settings.ConfigSection;
import ryzn.macro.client.config.settings.Setting;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.ForagingQuestActionEvent;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.core.events.api.TreeChopActionEvent;
import ryzn.macro.client.debug.MacroStateTrace;
import ryzn.macro.client.feature.api.MenuToggle;
import ryzn.macro.client.feature.commands.impl.MacroCommands;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.feature.hud.SkyBlockHud;
import ryzn.macro.client.feature.macros.api.Macro;
import ryzn.macro.client.feature.macros.impl.foragingquest.ForagingRoute.Target;
import ryzn.macro.client.feature.macros.impl.tree.TreeChopMacro;
import ryzn.macro.client.feature.macros.impl.tree.TreeLogType;
import ryzn.macro.client.feature.rotation.api.LookController;
import ryzn.macro.client.feature.rotation.impl.RouteGaze;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.input.MacroActionTiming;
import ryzn.macro.client.input.MacroKeys;
import ryzn.macro.client.util.text.RyznChat;

/**
 * Runs the Park foraging questline end to end: NPC hand-ins, chopping objectives, rewards, the harp
 * completion, Molbert's traps, and the optional collection grind the Level 7 route asks for. Its
 * saved switch and "Resume from" checkpoint are owned here; a death or a reconnect is handed back
 * to the run by the {@link ForagingQuestRecovery} collaborator.
 */
public final class ForagingQuestUnlockerMacro extends Macro implements ConfigSection, MenuToggle {

  static final Logger LOGGER = LoggerFactory.getLogger("ryzn-foraging");

  static final int PATH_TIMEOUT_TICKS = 20 * 60;

  final ForagingQuestNpcInteractions npcInteractions = new ForagingQuestNpcInteractions(this);
  final ForagingQuestTravel travelActions = new ForagingQuestTravel(this);
  final ForagingQuestMenuActions menuActions = new ForagingQuestMenuActions(this);

  static final int GUI_TIMEOUT_TICKS = 20 * 20;
  static final int CHAT_TIMEOUT_TICKS = 20 * 40;

  /**
   * How long the harp may go without clearing a song before it counts as stuck.
   *
   * <p>Deliberately a stall watchdog and not a cap on the whole step. A cap cannot be set: there
   * are a dozen songs, each retried until it beats the score threshold, and a fast one can take ten
   * attempts on its own. The old 20-minute cap expired mid-run every time, and because a failed
   * step restarts one second later - losing the session's record of which songs were already
   * cleared - the macro looped back to song one forever instead of ever finishing.
   */
  private static final int HARP_STALL_TICKS = 20 * 60 * 12;

  private static final int TRAP_TIMEOUT_TICKS = 20 * 25;

  /**
   * How close to the middle of the trap block counts as centered, and the per-axis steering
   * deadzone.
   */
  private static final double TRAP_CENTER_TOLERANCE = 0.12;

  private static final double TRAP_CENTER_DEADZONE = 0.05;

  /**
   * Give up on centering rather than burning the whole trap timeout on a shuffle we cannot finish.
   */
  private static final int TRAP_CENTER_GIVEUP_TICKS = 20 * 8;

  private static final int CAMPFIRE_TIMEOUT_TICKS = 20 * 18;

  /**
   * Ryzn's block cache switches a second or two after the new world does; the first path search
   * otherwise runs against the island just left and answers "no path". Same hold as the level7
   * /hub.
   */
  private static final int PARK_SETTLE_TICKS = 20 * 5;

  /**
   * No felled tree for this long means the chopper is wedged, not just walking - restart it in
   * place.
   */
  private static final int CHOP_STALL_TICKS = 20 * 60 * 5;

  private static final int DONE_SETTLE_TICKS = 6;
  private static final int FAILURE_CONTINUE_DELAY_TICKS = 20;
  private static final int CHAT_HISTORY_LIMIT = 100;
  private static final int OBJECTIVE_CHAT_GRACE_LINES = 2;

  /**
   * Dialogue chat this recent means the NPC is still talking - hold NPC timeouts open. Molbert's
   * intro pauses up to ~9 s between lines (log 2026-07-08), so the grace must sit well above that.
   */
  static final int NPC_CHAT_GRACE_TICKS = 20 * 12;

  /**
   * Ryan's brush-off when he catches us standing. Matched without the apostrophe so a curly one
   * still hits.
   */
  private static final String RYAN_REJECT_NEEDLE = "not part of our cult";

  /** Saved run toggle; startup, import, and export clear it through the run-toggle list. */
  private final Setting<Boolean> enabled =
      addSetting(Setting.bool("foraging_quest_unlocker_enabled", "Enabled", false));

  /**
   * The 1-based "Resume from" checkpoint. On a restart mid-quest it lets the run begin at a later
   * step so it does not re-attempt one-time actions it can no longer repeat. Stored unclamped, as
   * before; {@link #resumeCheckpoint()} clamps it at use.
   */
  private final Setting<Integer> savedResumeCheckpoint =
      addSetting(
          Setting.integer(
              "resume_checkpoint", "Resume from", 1, Integer.MIN_VALUE, Integer.MAX_VALUE));

  private final ForagingQuestRecovery recovery = new ForagingQuestRecovery(this);
  int ticksSinceNpcChat = NPC_CHAT_GRACE_TICKS;
  ForagingQuestState state = ForagingQuestState.IDLE;
  String status = "Disabled";
  private boolean lastRunCompleted;
  private String lastStopReason = "";
  private String continueReason = "";

  int stateTicks;
  private int parkScrollUseStartSequence;
  int hubHopSettleTicks;
  int blockedTicks;
  boolean sidestepActive;
  private boolean escapeWasDown;
  volatile boolean ryanRejected;
  Target ryanApproachTarget;
  boolean ryanWaypointReached;
  boolean macroScreenOpened;

  /**
   * Songs the harp had cleared at the last check, so the stall watchdog can tell progress from a
   * hang.
   */
  private int harpSongsCleared;

  private final ForagingChopSession chopSession = new ForagingChopSession();

  /** Created on first use: its wood legs load the block registry, which config parsing must not. */
  private ForagingCollectionGrind grind;

  private final ForagingHarpSession harpSession = new ForagingHarpSession();

  private final ForagingLogPurchaseProgress logPurchaseProgress = new ForagingLogPurchaseProgress();
  private final ForagingMenuSlots menuSlots = new ForagingMenuSlots();
  private final ForagingInventoryItems inventoryItems = new ForagingInventoryItems();

  /** Holds the head on the NPC being walked to; see {@link MacroLook#externalOwnsLook}. */
  final RouteGaze gaze = new RouteGaze();

  final ForagingRoute route = new ForagingRoute();

  final MacroActionTiming actionTiming = new MacroActionTiming();
  final ForagingInventoryActions inventoryActions =
      new ForagingInventoryActions(menuSlots, inventoryItems, actionTiming);
  final ForagingLogDropActions logDropActions =
      new ForagingLogDropActions(inventoryItems, this::look);

  /** Created on first use: its purchase table loads the item registry. */
  private ForagingLogPurchaseActions logPurchaseActions;

  final ForagingChatHistory chatHistory =
      new ForagingChatHistory(CHAT_HISTORY_LIMIT, OBJECTIVE_CHAT_GRACE_LINES);
  final ForagingChatWaits chatWaits = new ForagingChatWaits(chatHistory);
  final ForagingInventoryClear inventoryClear = new ForagingInventoryClear(actionTiming);

  /**
   * Off for a standalone run - only the level7 route asks for the bag to be emptied at the
   * merchant.
   */
  boolean clearInventoryAtMerchant;

  boolean unansweredPromptLogged;

  /**
   * Only dialogue from the quest's own NPCs counts as activity - ambient "[NPC]" chatter from
   * unrelated NPCs must not hold timeouts open.
   */
  private static final String[] questNpcDialoguePrefixes = {
    "[npc] lumber jack", "[npc] charlie", "[npc] kelly",
    "[npc] ryan", "[npc] melody", "[npc] molbert"
  };

  private static boolean isQuestNpcDialogue(String clean) {
    for (String prefix : questNpcDialoguePrefixes) {
      if (clean.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @description Creates the process-lifetime quest runner with its saved switch, resume
   *     checkpoint, and death/reconnect recovery collaborator.
   */
  public ForagingQuestUnlockerMacro() {
    super(
        "foraging_quest",
        "Foraging Quest Unlocker",
        "Runs the Park foraging quest route, rewards, harp completion, and Molbert traps.");
  }

  /**
   * @description Loads the saved switch and resume checkpoint under their original keys.
   * @param object the section JSON; a missing or mistyped value falls back to its default
   */
  @Override
  public void read(JsonObject object) {
    ConfigSection.readSettings(object, enabled, savedResumeCheckpoint);
  }

  /**
   * @description Writes the saved switch and resume checkpoint in their original key order.
   * @return a new section object for the settings file
   */
  @Override
  public JsonObject write() {
    return ConfigSection.writeSettings(enabled, savedResumeCheckpoint);
  }

  /**
   * @description Registers the recovery collaborator's reconnect listeners. Called once while the
   *     client starts, at the position the recovery listeners always held.
   */
  public void registerListeners() {
    recovery.registerListeners();
  }

  /**
   * @description Records quest NPC dialogue, Ryan's rejection, grind progress, and chat history,
   *     then lets the recovery collaborator watch for the player's own death.
   * @param gamemessageevent the received message; overlays are ignored
   */
  public void onGameMessageEvent(GameMessageEvent gamemessageevent) {
    final Component message = gamemessageevent.message();
    if (gamemessageevent.overlay() || message == null) {
      return;
    }
    final String clean = normalizeSearchText(message.getString());
    if (isEnabled() && !clean.isEmpty()) {
      if (isQuestNpcDialogue(clean) || clean.startsWith("select an option")) {
        ticksSinceNpcChat = 0;
      }
      if (clean.contains(RYAN_REJECT_NEEDLE)) {
        ryanRejected = true;
      }
      grind().observeChat(clean);
      chatHistory.add(clean, message);
    }
    recovery.onChat(clean);
  }

  /**
   * @description Applies a run or setup request from the menu, failsafe, or Level 7 route.
   * @param foragingquestactionevent the requested action and its value
   */
  @Subscribe
  public void onForagingQuestActionEvent(ForagingQuestActionEvent foragingquestactionevent) {
    final int value = foragingquestactionevent.value();
    switch (foragingquestactionevent.action()) {
      case SET_ENABLED -> setEnabled(foragingquestactionevent.flag());
      case SET_CLEAR_INVENTORY_AT_MERCHANT ->
          setClearInventoryAtMerchant(foragingquestactionevent.flag());
      case PICK_RESUME_CHECKPOINT -> onResumeCheckpointPicked(value);
      case SELECT_GRIND_LEG -> selectCollectionGrindLeg(value);
      case SELECT_FINAL_SALE -> selectCollectionFinalSale();
      case ARM_PARK_WARP_RESUME -> armParkWarpResume();
      default -> throw new IllegalArgumentException("Unknown foraging quest action");
    }
  }

  /**
   * @description Resumes a persisted run after settings load, matching the previous startup and
   *     import hand-off. Run toggles are normally cleared first, so this is usually a no-op.
   */
  public void applyConfig() {
    if (isEnabled()) {
      beginSession();
    }
  }

  /**
   * @description Advances the death recovery countdown at its original tick position.
   */
  public void tickRecovery() {
    recovery.onClientTick();
  }

  /**
   * @description Reports whether the quest run is active.
   * @return the saved run toggle
   */
  public boolean isEnabled() {
    return enabled.get();
  }

  /**
   * Level7 hands over a bag full of sand and coal; a standalone run keeps whatever the player
   * brought.
   *
   * @param on whether the inventory is sold at the merchant
   */
  public void setClearInventoryAtMerchant(boolean on) {
    clearInventoryAtMerchant = on;
  }

  public void setEnabled(boolean on) {
    if (isEnabled() == on) {
      persist();
      return;
    }
    enabled.set(on);
    RyznChat.announce("Foraging Quest Unlocker", on);
    if (on) {
      beginSession();
    } else {
      stopAll("disabled", false);
    }
    persist();
  }

  public String statusText() {
    if (isEnabled()) {
      return status;
    }
    return lastRunCompleted ? "Completed" : "Disabled";
  }

  public boolean wasLastRunCompleted() {
    return lastRunCompleted;
  }

  public String lastStopReason() {
    return lastStopReason;
  }

  public boolean shouldLockMouse() {
    return isEnabled()
        && (!PathfindCommand.isNavigationActive() || gaze.isOwned())
        && look().hasAim();
  }

  /**
   * True while the walk to an NPC is watching it. Read by {@code RotationManager.externalOwnsLook},
   * which makes the path executor yield ONLY the head and keep driving the body along the route.
   *
   * @return true while the route is watching its target with the head
   */
  public boolean ownsRouteLook() {
    return isEnabled() && gaze.isOwned();
  }

  private final MacroStateTrace trace = new MacroStateTrace("foragingquest");

  public void onClientTick(Minecraft mc) {
    trace.log(state.name(), status);
    if (!isEnabled()) {
      return;
    }
    ticksSinceNpcChat = Math.min(ticksSinceNpcChat + 1, NPC_CHAT_GRACE_TICKS);
    if (travelActions.isTravellingBetweenServers()) {
      RyznBootstrap.failsafeManager().expectWorldChange();
    }
    if (consumeEscapeStop(mc)) {
      stopAll("stopped with Escape", false);
      return;
    }

    final LocalPlayer player = mc.player;
    final ClientLevel level = mc.level;
    if (player == null || level == null) {
      releaseAllKeys(mc.options);
      return;
    }

    if (travelActions.consumeRyanReject()) {
      PathfindCommand.stopNavQuiet();
      releaseAllKeys(mc.options);
      RyznBootstrap.failsafeManager().expectServerRotation();
      setState(ForagingQuestState.RYAN_REJECTED);
      return;
    }

    switch (state) {
      case RESUME_TRAVEL -> travelActions.tickWalkBack(mc, player);
      case PATH_LUMBER_MERCHANT ->
          travelActions.tickPathing(
              mc,
              player,
              level,
              ForagingRoute.LUMBER_MERCHANT,
              ForagingQuestState.INTERACT_LUMBER_MERCHANT);
      case INTERACT_LUMBER_MERCHANT ->
          npcInteractions.tickNpcScreenInteract(
              mc,
              player,
              level,
              ForagingRoute.LUMBER_MERCHANT,
              ForagingQuestState.SELL_INVENTORY,
              false);
      case SELL_INVENTORY -> menuActions.tickSellInventory(mc, player);
      case BUY_LUMBER_LOGS -> menuActions.tickBuyLumberLogs(mc, player);
      case CLOSE_LUMBER_GUI -> menuActions.tickCloseScreen(mc, ForagingQuestState.HOLD_GOLDEN_AXE);
      case HOLD_GOLDEN_AXE -> menuActions.tickHoldGoldenAxe(mc, player);
      case PATH_LUMBER_JACK_START ->
          travelActions.tickPathing(
              mc,
              player,
              level,
              ForagingRoute.LUMBER_JACK,
              ForagingQuestState.INTERACT_LUMBER_JACK_START);
      case INTERACT_LUMBER_JACK_START -> npcInteractions.tickLumberJackStart(mc, player, level);
      case WAIT_OAK_OBJECTIVE ->
          tickWaitObjective("Collect Oak Logs", ForagingQuestState.START_OAK_CHOP);
      case START_OAK_CHOP ->
          menuActions.tickStartLogDrop(TreeLogType.OAK, ForagingQuestState.WAIT_OAK_CHOP);
      case WAIT_OAK_CHOP ->
          menuActions.tickWaitLogDrop(
              mc, player, TreeLogType.OAK, ForagingQuestState.PATH_LUMBER_JACK_RETURN);
      case PATH_LUMBER_JACK_RETURN ->
          travelActions.tickPathing(
              mc,
              player,
              level,
              ForagingRoute.LUMBER_JACK,
              ForagingQuestState.INTERACT_LUMBER_JACK_RETURN);
      case INTERACT_LUMBER_JACK_RETURN -> npcInteractions.tickLumberJackReturn(mc, player, level);
      case WAIT_LUMBER_JACK_GUI -> tickWaitAnyGui(ForagingQuestState.CLAIM_LUMBER_JACK_AXE);
      case CLAIM_LUMBER_JACK_AXE ->
          menuActions.tickClaimReward(mc, player, ForagingQuestState.PATH_BIRCH_TRANSFER);
      case PATH_BIRCH_TRANSFER ->
          travelActions.tickPathing(
              mc, player, ForagingRoute.BIRCH_TRANSFER, ForagingQuestState.WAIT_BIRCH_TRANSFER);
      case WAIT_BIRCH_TRANSFER ->
          tickWaitArea("birch park", ForagingQuestState.PATH_CHARLIE_START, CHAT_TIMEOUT_TICKS);
      case PATH_CHARLIE_START ->
          travelActions.tickPathing(
              mc, player, level, ForagingRoute.CHARLIE, ForagingQuestState.INTERACT_CHARLIE_START);
      case INTERACT_CHARLIE_START ->
          npcInteractions.tickNpcChatInteract(
              mc,
              player,
              level,
              ForagingRoute.CHARLIE,
              ForagingQuestState.WAIT_BIRCH_OBJECTIVE,
              false);
      case WAIT_BIRCH_OBJECTIVE ->
          tickWaitObjective("Collect Birch Logs", ForagingQuestState.START_BIRCH_CHOP);
      case START_BIRCH_CHOP ->
          menuActions.tickStartLogDrop(TreeLogType.BIRCH, ForagingQuestState.WAIT_BIRCH_CHOP);
      case WAIT_BIRCH_CHOP ->
          menuActions.tickWaitLogDrop(
              mc, player, TreeLogType.BIRCH, ForagingQuestState.PATH_CHARLIE_REWARD);
      case PATH_CHARLIE_REWARD ->
          travelActions.tickPathing(
              mc,
              player,
              level,
              ForagingRoute.CHARLIE,
              ForagingQuestState.INTERACT_CHARLIE_TROUSERS);
      case INTERACT_CHARLIE_TROUSERS ->
          npcInteractions.tickNpcScreenInteract(
              mc,
              player,
              level,
              ForagingRoute.CHARLIE,
              ForagingQuestState.WAIT_CHARLIE_TROUSERS_GUI,
              false);
      case WAIT_CHARLIE_TROUSERS_GUI -> tickWaitAnyGui(ForagingQuestState.CLICK_CHARLIE_TROUSERS);
      case CLICK_CHARLIE_TROUSERS ->
          menuActions.tickClickGuiItem(
              mc,
              player,
              "Charlie's Trousers",
              ForagingQuestState.INTERACT_CHARLIE_AFTER_TROUSERS,
              true);
      case INTERACT_CHARLIE_AFTER_TROUSERS ->
          npcInteractions.tickNpcChatInteract(
              mc,
              player,
              level,
              ForagingRoute.CHARLIE,
              ForagingQuestState.INTERACT_CHARLIE_SCROLL,
              false);
      case INTERACT_CHARLIE_SCROLL ->
          npcInteractions.tickNpcScreenInteract(
              mc,
              player,
              level,
              ForagingRoute.CHARLIE,
              ForagingQuestState.WAIT_CHARLIE_SCROLL_GUI,
              false);
      case WAIT_CHARLIE_SCROLL_GUI -> tickWaitAnyGui(ForagingQuestState.CLICK_PARK_SCROLL);
      case CLICK_PARK_SCROLL ->
          menuActions.tickClickGuiItem(
              mc, player, "Travel Scroll to The Park", ForagingQuestState.USE_PARK_SCROLL, true);
      case USE_PARK_SCROLL ->
          menuActions.tickUseInventoryItem(
              mc, player, "travel scroll to the park", ForagingQuestState.WAIT_SCROLL_CONSUMED);
      case WAIT_SCROLL_CONSUMED ->
          tickWaitRecent(
              "you consumed the scroll",
              ForagingQuestState.PATH_SPRUCE_TRANSFER,
              CHAT_TIMEOUT_TICKS,
              parkScrollUseStartSequence);
      case PATH_SPRUCE_TRANSFER ->
          travelActions.tickPathing(
              mc, player, ForagingRoute.SPRUCE_TRANSFER, ForagingQuestState.WAIT_SPRUCE_AREA);
      case WAIT_SPRUCE_AREA ->
          tickWaitArea("spruce woods", ForagingQuestState.DELAY_SPRUCE_AREA, CHAT_TIMEOUT_TICKS);
      case DELAY_SPRUCE_AREA -> tickDelay(ForagingQuestState.PATH_KELLY_START, 40);
      case PATH_KELLY_START ->
          travelActions.tickPathing(
              mc, player, level, ForagingRoute.KELLY, ForagingQuestState.INTERACT_KELLY_START);
      case INTERACT_KELLY_START -> npcInteractions.tickKellyStart(mc, player, level);
      case WAIT_SPRUCE_OBJECTIVE ->
          tickWaitObjective("Collect Spruce Logs", ForagingQuestState.START_SPRUCE_CHOP);
      case START_SPRUCE_CHOP ->
          menuActions.tickStartLogDrop(TreeLogType.SPRUCE, ForagingQuestState.WAIT_SPRUCE_CHOP);
      case WAIT_SPRUCE_CHOP ->
          menuActions.tickWaitLogDrop(
              mc, player, TreeLogType.SPRUCE, ForagingQuestState.PATH_KELLY_REWARD);
      case PATH_KELLY_REWARD -> {
        if (advanceIfOwnedReward(
            player, "Kelly's T-Shirt", ForagingQuestState.PATH_DARK_TRANSFER)) {
          return;
        }
        travelActions.tickPathing(
            mc, player, level, ForagingRoute.KELLY, ForagingQuestState.INTERACT_KELLY_REWARD);
      }
      case INTERACT_KELLY_REWARD -> {
        if (advanceIfOwnedReward(
            player, "Kelly's T-Shirt", ForagingQuestState.PATH_DARK_TRANSFER)) {
          return;
        }
        npcInteractions.tickNpcScreenInteract(
            mc, player, level, ForagingRoute.KELLY, ForagingQuestState.WAIT_KELLY_GUI, false);
      }
      case WAIT_KELLY_GUI -> {
        if (advanceIfOwnedReward(
            player, "Kelly's T-Shirt", ForagingQuestState.PATH_DARK_TRANSFER)) {
          return;
        }
        tickWaitAnyGui(ForagingQuestState.CLICK_KELLY_SHIRT);
      }
      case CLICK_KELLY_SHIRT -> {
        if (advanceIfOwnedReward(
            player, "Kelly's T-Shirt", ForagingQuestState.PATH_DARK_TRANSFER)) {
          return;
        }
        menuActions.tickClickGuiItem(
            mc, player, "Kelly's T-Shirt", ForagingQuestState.PATH_DARK_TRANSFER, true);
      }
      case PATH_DARK_TRANSFER ->
          travelActions.tickPathing(
              mc, player, ForagingRoute.DARK_TRANSFER, ForagingQuestState.WAIT_DARK_AREA);
      case WAIT_DARK_AREA ->
          tickWaitArea("dark thicket", ForagingQuestState.DELAY_DARK_AREA, CHAT_TIMEOUT_TICKS);
      case DELAY_DARK_AREA -> tickDelay(ForagingQuestState.PATH_RYAN_BACK, 40);
      case PATH_RYAN_BACK -> travelActions.tickPathBehindRyan(mc, player, level);
      case RYAN_REJECTED -> travelActions.tickRyanRejected(mc);
      case INTERACT_RYAN_START -> npcInteractions.tickRyanStart(mc, player, level);
      case INTERACT_RYAN_TRIAL_WHAT -> npcInteractions.tickRyanTrialWhat(mc);
      case WAIT_RYAN_TRIAL_OBJECTIVE ->
          tickWaitRecent(
              "new objective",
              ForagingQuestState.PATH_CAMPFIRE_ADJACENT,
              CHAT_TIMEOUT_TICKS,
              chatHistory.stateStartSequence());
      case PATH_CAMPFIRE_ADJACENT ->
          travelActions.tickPathing(
              mc, player, ForagingRoute.CAMPFIRE_ADJACENT, ForagingQuestState.STEP_ON_CAMPFIRE);
      case STEP_ON_CAMPFIRE -> tickStepOntoCampfire(mc, player);
      case WAIT_TRIAL_FIRE -> tickWaitObjective("Talk to Ryan", ForagingQuestState.PATH_RYAN_CHOP);
      case PATH_RYAN_CHOP ->
          travelActions.tickPathing(
              mc, player, level, ForagingRoute.RYAN, ForagingQuestState.INTERACT_RYAN_CHOP);
      case INTERACT_RYAN_CHOP ->
          npcInteractions.tickNpcChatInteract(
              mc, player, level, ForagingRoute.RYAN, ForagingQuestState.WAIT_DARK_OBJECTIVE, false);
      case WAIT_DARK_OBJECTIVE ->
          tickWaitObjective("Collect Dark Oak Logs", ForagingQuestState.START_DARK_CHOP);
      case START_DARK_CHOP ->
          menuActions.tickStartLogDrop(TreeLogType.DARK_OAK, ForagingQuestState.WAIT_DARK_CHOP);
      case WAIT_DARK_CHOP ->
          menuActions.tickWaitLogDrop(
              mc, player, TreeLogType.DARK_OAK, ForagingQuestState.PATH_RYAN_REWARD);
      case PATH_RYAN_REWARD -> {
        if (advanceIfOwnedReward(
            player, "Campfire Initiate Badge I", ForagingQuestState.PATH_ACACIA_TRANSFER)) {
          return;
        }
        travelActions.tickPathing(
            mc, player, level, ForagingRoute.RYAN, ForagingQuestState.INTERACT_RYAN_REWARD);
      }
      case INTERACT_RYAN_REWARD -> {
        if (advanceIfOwnedReward(
            player, "Campfire Initiate Badge I", ForagingQuestState.PATH_ACACIA_TRANSFER)) {
          return;
        }
        npcInteractions.tickNpcScreenInteract(
            mc, player, level, ForagingRoute.RYAN, ForagingQuestState.WAIT_RYAN_REWARD_GUI, false);
      }
      case WAIT_RYAN_REWARD_GUI -> {
        if (advanceIfOwnedReward(
            player, "Campfire Initiate Badge I", ForagingQuestState.PATH_ACACIA_TRANSFER)) {
          return;
        }
        tickWaitAnyGui(ForagingQuestState.CLICK_RYAN_BADGE);
      }
      case CLICK_RYAN_BADGE -> {
        if (advanceIfOwnedReward(
            player, "Campfire Initiate Badge I", ForagingQuestState.PATH_ACACIA_TRANSFER)) {
          return;
        }
        menuActions.tickClickGuiItem(
            mc,
            player,
            "Campfire Initiate Badge I",
            ForagingQuestState.CLOSE_RYAN_REWARD_GUI,
            false);
      }
      case CLOSE_RYAN_REWARD_GUI -> {
        if (advanceIfOwnedReward(
            player, "Campfire Initiate Badge I", ForagingQuestState.PATH_ACACIA_TRANSFER)) {
          return;
        }
        menuActions.tickCloseScreen(mc, ForagingQuestState.PATH_ACACIA_TRANSFER);
      }
      case PATH_ACACIA_TRANSFER ->
          travelActions.tickPathing(
              mc, player, ForagingRoute.ACACIA_TRANSFER, ForagingQuestState.WAIT_ACACIA_AREA);
      case WAIT_ACACIA_AREA ->
          tickWaitArea(
              "savanna woodland", ForagingQuestState.DELAY_ACACIA_AREA, CHAT_TIMEOUT_TICKS);
      case DELAY_ACACIA_AREA -> tickDelay(ForagingQuestState.PATH_MELODY_START, 40);
      case PATH_MELODY_START ->
          travelActions.tickPathing(
              mc, player, level, ForagingRoute.MELODY, ForagingQuestState.INTERACT_MELODY_START);
      case INTERACT_MELODY_START -> npcInteractions.tickMelodyStart(mc, player, level);
      case WAIT_ACACIA_OBJECTIVE ->
          tickWaitObjective("Collect Acacia Logs", ForagingQuestState.START_ACACIA_CHOP);
      case START_ACACIA_CHOP ->
          menuActions.tickStartLogDrop(TreeLogType.ACACIA, ForagingQuestState.WAIT_ACACIA_CHOP);
      case WAIT_ACACIA_CHOP ->
          menuActions.tickWaitLogDrop(
              mc, player, TreeLogType.ACACIA, ForagingQuestState.PATH_MELODY_REWARD);
      case PATH_MELODY_REWARD ->
          travelActions.tickPathing(
              mc, player, level, ForagingRoute.MELODY, ForagingQuestState.INTERACT_MELODY_REWARD);
      case INTERACT_MELODY_REWARD ->
          npcInteractions.tickNpcScreenInteract(
              mc,
              player,
              level,
              ForagingRoute.MELODY,
              ForagingQuestState.WAIT_MELODY_REWARD_GUI,
              false);
      case WAIT_MELODY_REWARD_GUI -> tickWaitAnyGui(ForagingQuestState.CLICK_MELODY_SHOES);
      case CLICK_MELODY_SHOES ->
          menuActions.tickClickGuiItem(
              mc, player, "Melody's Shoes", ForagingQuestState.START_FULL_HARP, true);
      case START_FULL_HARP -> tickStartFullHarp(mc);
      case WAIT_FULL_HARP -> tickWaitFullHarp(mc);
      case CLOSE_AFTER_HARP ->
          menuActions.tickCloseScreen(mc, ForagingQuestState.PATH_JUNGLE_TRANSFER);
      case PATH_JUNGLE_TRANSFER ->
          travelActions.tickPathing(
              mc, player, ForagingRoute.JUNGLE_TRANSFER, ForagingQuestState.WAIT_JUNGLE_AREA);
      case WAIT_JUNGLE_AREA ->
          tickWaitArea("jungle island", ForagingQuestState.DELAY_JUNGLE_AREA, CHAT_TIMEOUT_TICKS);
      case DELAY_JUNGLE_AREA -> tickDelay(ForagingQuestState.PATH_MOLBERT_START, 40);
      case PATH_MOLBERT_START ->
          travelActions.tickPathing(
              mc, player, level, ForagingRoute.MOLBERT, ForagingQuestState.INTERACT_MOLBERT_START);
      case INTERACT_MOLBERT_START -> npcInteractions.tickMolbertStart(mc, player, level);
      case WAIT_JUNGLE_OBJECTIVE ->
          tickWaitObjective("Collect Jungle Logs", ForagingQuestState.START_JUNGLE_CHOP);
      case START_JUNGLE_CHOP ->
          menuActions.tickStartLogDrop(TreeLogType.JUNGLE, ForagingQuestState.WAIT_JUNGLE_CHOP);
      case WAIT_JUNGLE_CHOP ->
          menuActions.tickWaitLogDrop(
              mc, player, TreeLogType.JUNGLE, ForagingQuestState.PATH_MOLBERT_LEAVE);
      case PATH_MOLBERT_LEAVE ->
          travelActions.tickPathing(
              mc, player, level, ForagingRoute.MOLBERT, ForagingQuestState.INTERACT_MOLBERT_LEAVE);
      case INTERACT_MOLBERT_LEAVE ->
          npcInteractions.tickNpcChatInteract(
              mc,
              player,
              level,
              ForagingRoute.MOLBERT,
              ForagingQuestState.WAIT_LEAVE_OBJECTIVE,
              false);
      case WAIT_LEAVE_OBJECTIVE ->
          tickWaitObjective("Leave the area", ForagingQuestState.PATH_LEAVE_ONE_A);
      case PATH_LEAVE_ONE_A ->
          travelActions.tickPathing(
              mc, player, ForagingRoute.LEAVE_A, ForagingQuestState.DELAY_LEAVE_ONE_A);
      case DELAY_LEAVE_ONE_A -> tickDelay(ForagingQuestState.PATH_LEAVE_ONE_B, 40);
      case PATH_LEAVE_ONE_B ->
          travelActions.tickPathToJungleIsland(mc, player, ForagingQuestState.DELAY_LEAVE_ONE_B);
      case DELAY_LEAVE_ONE_B -> tickDelay(ForagingQuestState.PATH_MOLBERT_TRAPS, 40);
      case PATH_MOLBERT_TRAPS ->
          travelActions.tickPathing(
              mc, player, level, ForagingRoute.MOLBERT, ForagingQuestState.INTERACT_MOLBERT_TRAPS);
      case INTERACT_MOLBERT_TRAPS ->
          npcInteractions.tickNpcChatInteract(
              mc,
              player,
              level,
              ForagingRoute.MOLBERT,
              ForagingQuestState.WAIT_TRAPS_OBJECTIVE,
              false);
      case WAIT_TRAPS_OBJECTIVE ->
          tickWaitObjective("Place Mole traps", ForagingQuestState.PATH_TRAP_ONE);
      case PATH_TRAP_ONE ->
          travelActions.tickPathing(
              mc, player, ForagingRoute.TRAP_ONE, ForagingQuestState.PLACE_TRAP_ONE);
      case PLACE_TRAP_ONE -> tickPlaceTrap(mc, player, 1, ForagingQuestState.PATH_TRAP_TWO);
      case PATH_TRAP_TWO ->
          travelActions.tickPathing(
              mc, player, ForagingRoute.TRAP_TWO, ForagingQuestState.PLACE_TRAP_TWO);
      case PLACE_TRAP_TWO -> tickPlaceTrap(mc, player, 2, ForagingQuestState.PATH_TRAP_THREE);
      case PATH_TRAP_THREE ->
          travelActions.tickPathing(
              mc, player, ForagingRoute.TRAP_THREE, ForagingQuestState.PLACE_TRAP_THREE);
      case PLACE_TRAP_THREE ->
          tickPlaceTrap(mc, player, 3, ForagingQuestState.WAIT_TALK_MOLBERT_OBJECTIVE);
      case WAIT_TALK_MOLBERT_OBJECTIVE ->
          tickWaitObjective("Talk to Molbert", ForagingQuestState.PATH_MOLBERT_LEAVE_AGAIN);
      case PATH_MOLBERT_LEAVE_AGAIN ->
          travelActions.tickPathing(
              mc,
              player,
              level,
              ForagingRoute.MOLBERT,
              ForagingQuestState.INTERACT_MOLBERT_LEAVE_AGAIN);
      case INTERACT_MOLBERT_LEAVE_AGAIN ->
          npcInteractions.tickNpcChatInteract(
              mc,
              player,
              level,
              ForagingRoute.MOLBERT,
              ForagingQuestState.WAIT_LEAVE_AGAIN_OBJECTIVE,
              false);
      case WAIT_LEAVE_AGAIN_OBJECTIVE ->
          tickWaitObjective("Leave the area again", ForagingQuestState.PATH_LEAVE_TWO_A);
      case PATH_LEAVE_TWO_A ->
          travelActions.tickPathing(
              mc, player, ForagingRoute.LEAVE_A, ForagingQuestState.DELAY_LEAVE_TWO_A);
      case DELAY_LEAVE_TWO_A -> tickDelay(ForagingQuestState.PATH_LEAVE_TWO_B, 40);
      case PATH_LEAVE_TWO_B ->
          travelActions.tickPathToJungleIsland(mc, player, ForagingQuestState.DELAY_LEAVE_TWO_B);
      case DELAY_LEAVE_TWO_B -> tickDelay(ForagingQuestState.PATH_FINAL_MOLBERT, 40);
      case PATH_FINAL_MOLBERT -> npcInteractions.tickPathToFinalMolbert(mc, player, level);
      case INTERACT_FINAL_MOLBERT_HELP ->
          npcInteractions.tickNpcChatInteract(
              mc,
              player,
              level,
              ForagingRoute.FINAL_MOLBERT,
              ForagingQuestState.WAIT_HELP_MOLBERT_CLICKABLE,
              false);
      case WAIT_HELP_MOLBERT_CLICKABLE -> npcInteractions.tickHelpMolbertClickable(mc);
      case INTERACT_MOLBERT_MOLE -> npcInteractions.tickMolbertMole(mc, player, level);
      case WAIT_FINAL_TALK_OBJECTIVE -> tickWaitFinalTalk(mc);
      case INTERACT_FINAL_MOLBERT_REWARD -> {
        if (advanceIfOwnedReward(player, "Mole Hat", ForagingQuestState.PATH_COLLECTION_AREA)) {
          return;
        }
        npcInteractions.tickNpcScreenInteract(
            mc,
            player,
            level,
            ForagingRoute.FINAL_MOLBERT,
            ForagingQuestState.WAIT_MOLBERT_REWARD_GUI,
            false);
      }
      case WAIT_MOLBERT_REWARD_GUI -> {
        if (advanceIfOwnedReward(player, "Mole Hat", ForagingQuestState.PATH_COLLECTION_AREA)) {
          return;
        }
        tickWaitAnyGui(ForagingQuestState.CLICK_MOLE_HAT);
      }
      case CLICK_MOLE_HAT -> {
        if (advanceIfOwnedReward(player, "Mole Hat", ForagingQuestState.PATH_COLLECTION_AREA)) {
          return;
        }
        menuActions.tickClickGuiItem(
            mc, player, "Mole Hat", ForagingQuestState.PATH_COLLECTION_AREA, true);
      }
      case WARP_HUB -> travelActions.tickWarpHub(mc, ForagingQuestState.WAIT_HUB_AREA);
      case WAIT_HUB_AREA -> tickDelay(ForagingQuestState.PATH_COLLECTION_AREA, PARK_SETTLE_TICKS);
      case CHECK_HUB_PLAYERS -> travelActions.tickCheckHubPlayers(mc);
      case LOBBY_HOP_SEND_IS -> travelActions.tickLobbyHopSendIs(mc);
      case LOBBY_HOP_WAIT_IS -> travelActions.tickLobbyHopWaitIs(mc);
      case LOBBY_HOP_SEND_HUB -> travelActions.tickLobbyHopSendHub(mc);
      case LOBBY_HOP_SETTLE -> travelActions.tickLobbyHopSettle(mc);
      case WARP_PARK -> travelActions.tickWarpPark(mc);
      case WAIT_PARK_AREA ->
          tickDelay(
              walkBackLeg < walkBackLegs ? ForagingQuestState.RESUME_TRAVEL : walkBackNext,
              PARK_SETTLE_TICKS);
      case PATH_COLLECTION_AREA -> tickPathToWood(mc, player);
      case PATH_COLLECTION_JUNGLE ->
          travelActions.tickPathToJungleIsland(mc, player, ForagingQuestState.PATH_COLLECTION_AREA);
      case START_COLLECTION_CHOP ->
          tickStartChop(mc, player, grind().leg().type(), ForagingQuestState.WAIT_COLLECTION_CHOP);
      case WAIT_COLLECTION_CHOP -> tickWaitCollectionChop();
      case WAIT_FORAGING_12 -> tickWaitForaging12(mc);
      case WARP_FINAL_SELL_HUB ->
          travelActions.tickWarpHub(mc, ForagingQuestState.WAIT_FINAL_SELL_HUB);
      case WAIT_FINAL_SELL_HUB ->
          tickDelay(ForagingQuestState.PATH_FINAL_SELL_MERCHANT, PARK_SETTLE_TICKS);
      case PATH_FINAL_SELL_MERCHANT ->
          travelActions.tickPathing(
              mc,
              player,
              level,
              ForagingRoute.LUMBER_MERCHANT,
              ForagingQuestState.INTERACT_FINAL_SELL_MERCHANT);
      case INTERACT_FINAL_SELL_MERCHANT ->
          npcInteractions.tickNpcScreenInteract(
              mc,
              player,
              level,
              ForagingRoute.LUMBER_MERCHANT,
              ForagingQuestState.SELL_FINAL_LOGS,
              false);
      case SELL_FINAL_LOGS -> menuActions.tickSellFinalLogs(mc, player);
      case DONE -> tickDone(mc);
      case FAILED -> tickFailed(mc);
      default -> beginSession();
    }
  }

  /** Functional aim on the game tick (fps-independent; works minimized at ~1 fps). */
  public void tickLook() {
    if (!shouldLockMouse()) {
      return;
    }
    final LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) {
      return;
    }
    look().tick(player);
  }

  public void onRenderFrame() {
    if (!shouldLockMouse()) {
      return;
    }
    final LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) {
      return;
    }
    look().tick(player);
  }

  private void beginSession() {
    final boolean selectedCollectionFinalSale = collectionFinalSaleOnStart;
    final int selectedCollectionLeg = collectionStartLeg;
    collectionStartLeg = -1;
    collectionFinalSaleOnStart = false;
    resetRuntime();
    enabled.set(true);
    lastRunCompleted = false;
    lastStopReason = "";
    escapeWasDown = isEscapeDown(Minecraft.getInstance());
    if (selectedCollectionFinalSale) {
      setState(ForagingQuestState.WARP_FINAL_SELL_HUB);
      return;
    }
    if (selectedCollectionLeg >= 0) {
      grind().resumeAt(selectedCollectionLeg);
      beginCollectionTravel();
      return;
    }
    if (deathResumeOnStart) {
      deathResumeOnStart = false;
      if (beginDeathResume()) {
        return;
      }
    }
    if (parkWarpOnStart) {
      parkWarpOnStart = false;
      if (beginParkResume()) {
        return;
      }
      // No /warp park before the scroll, but the park is still walked to from the Hub.
      walkBackOnStart = true;
    }
    final Checkpoint checkpoint = resumeCheckpoint();
    if (checkpoint == Checkpoint.WOOD_COLLECTIONS) {
      beginDeathResume();
      walkBackOnStart = false;
      return;
    }
    if (checkpoint == Checkpoint.values()[0]) {
      grind().clearCollections();
    }
    final boolean walkBack = walkBackOnStart && checkpoint.transfers > 0;
    walkBackOnStart = false;
    if (walkBack) {
      walkBackLeg = 0;
      walkBackLegs = checkpoint.transfers;
      walkBackNext = checkpoint.firstState;
      setState(ForagingQuestState.RESUME_TRAVEL);
      return;
    }
    setState(checkpoint.firstState);
  }

  /**
   * Restart where the run left off after {@code MacroReconnect} put us back in SkyBlock. The rejoin
   * lands at the park entrance, not where the run was working, so the checkpoint's own entry state
   * would path at an NPC on the far side of the island - walk back to that area's waypoint first.
   * See {@link ForagingQuestRecovery} for who calls this and when.
   */
  public void resumeAfterRejoin() {
    if (isEnabled()) {
      return;
    }
    walkBackOnStart = true;
    setEnabled(true);
  }

  /**
   * Get back to the wood the run was cutting after the player died. Death drops you at spawn with
   * the whole park between you and the tree, which is why the run just stopped there until now.
   *
   * <p>Rejoin recovery cannot cover this: that one re-enters at a quest checkpoint, and a
   * collection grind has none - it is one state that chops wherever it stands. The wood itself is
   * the checkpoint, and it survives in the tree chopper's own config because each leg saves it
   * there. See {@link ForagingQuestRecovery} for who calls this and when.
   */
  public void resumeAfterDeath() {
    if (isEnabled()) {
      return;
    }
    // Only the collection grind knows how to pick itself up from the park entrance. A death
    // mid-quest
    // leaves the run stopped exactly as it did before - re-entering a quest checkpoint from spawn
    // would
    // path at an NPC on the far side of the island.
    if (resumeCheckpoint() != Checkpoint.WOOD_COLLECTIONS) {
      return;
    }
    deathResumeOnStart = true;
    setEnabled(true);
  }

  /**
   * Arm a {@code /warp park} for the next start, for a caller that re-enables this module itself
   * (the Level 7 route, after it hops the whole run off a restarting server). Consumed by the next
   * {@link #beginSession}; a checkpoint that has not yet burned Charlie's Travel Scroll ignores it,
   * because Hypixel refuses the warp outright until the scroll is used.
   */
  public void armParkWarpResume() {
    parkWarpOnStart = true;
  }

  /**
   * Come back to the park from anywhere - the hub, the private island - rather than assuming we are
   * still standing in it, which is what the rejoin walk-back assumes. {@code /warp park} lands in
   * Birch Park, so the species gates out to the saved checkpoint's area are walked from there,
   * exactly as the death resume does for the collection grind().
   */
  private boolean beginParkResume() {
    final Checkpoint checkpoint = resumeCheckpoint();
    if (!isParkUnlocked(checkpoint)) {
      return false;
    }
    // The collection grind has no checkpoint of its own; the wood it was cutting is the anchor.
    if (checkpoint == Checkpoint.WOOD_COLLECTIONS) {
      return beginDeathResume();
    }
    walkBackLeg = PARK_ENTRANCE_LEG;
    walkBackLegs = checkpoint.transfers;
    walkBackNext = checkpoint.firstState;
    setState(ForagingQuestState.WARP_PARK);
    return true;
  }

  /**
   * {@code /warp park} exists only once Charlie's Travel Scroll has been consumed, which happens at
   * the end of the birch quest - so every checkpoint from the spruce travel onward can warp, and
   * everything before it has to be reached the long way through the Hub.
   */
  private boolean isParkUnlocked(Checkpoint checkpoint) {
    return checkpoint.ordinal() >= Checkpoint.SPRUCE_TRAVEL.ordinal();
  }

  /** Recover the saved wood, including the Hub-only Oak leg. */
  private boolean beginDeathResume() {
    grind()
        .resumeAt(
            ForagingCollectionGrind.legIndexOf(
                RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).chopLogType()));
    beginCollectionTravel();
    return true;
  }

  private void beginCollectionTravel() {
    if (grind().leg().type() == TreeLogType.OAK) {
      setState(ForagingQuestState.WARP_HUB);
      return;
    }
    walkBackLeg = PARK_ENTRANCE_LEG;
    walkBackLegs =
        switch (grind().leg().type()) {
          case BIRCH -> 1;
          case SPRUCE -> 2;
          case DARK_OAK -> 3;
          case ACACIA -> 4;
          case JUNGLE -> 5;
          default -> throw new IllegalStateException("Unsupported collection wood");
        };
    walkBackNext = ForagingQuestState.PATH_COLLECTION_AREA;
    setState(ForagingQuestState.WARP_PARK);
  }

  private void tickStartChop(
      Minecraft mc, LocalPlayer player, TreeLogType type, ForagingQuestState next) {
    stateTicks++;
    releaseAllKeys(mc.options);
    closeCurrentScreen(mc);
    if (RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).isRunning()) {
      RyznBootstrap.eventBus().post(new TreeChopActionEvent(TreeChopActionEvent.Action.STOP));
    }
    final RyznSettings cfg = RyznSettings.get();
    RyznBootstrap.eventBus()
        .post(new TreeChopActionEvent(TreeChopActionEvent.Action.SET_LOG_TYPE, type));
    cfg.save();
    RyznBootstrap.eventBus()
        .post(new TreeChopActionEvent(TreeChopActionEvent.Action.LOG_TYPE_CHANGED));
    chopSession.begin(
        inventoryItems.countLogs(player, type),
        RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).choppedCount());
    RyznBootstrap.eventBus()
        .post(new TreeChopActionEvent(TreeChopActionEvent.Action.START_FOR_COLLECTION));
    status = "Chopping " + type.displayName() + " logs";
    setState(next);
  }

  /** Walk the current collection landmark after the transfer chain; Oak starts in the Hub. */
  private void tickPathToWood(Minecraft mc, LocalPlayer player) {
    if (grind().reachedTarget(grind().leg().type())) {
      advanceCollection();
      return;
    }
    if (grind().leg().type() == TreeLogType.JUNGLE && !ForagingRoute.isOnJungleIsland(player)) {
      setState(ForagingQuestState.PATH_COLLECTION_JUNGLE);
      return;
    }
    if (grind().leg().type() != TreeLogType.JUNGLE
        && (ForagingRoute.isOnJungleIsland(player)
            || (ForagingRoute.LEAVE_A.equals(pathTarget)
                && !ForagingRoute.hasLeftJungleIsland(player)))) {
      // The island only connects down through the "Leave the area" launcher.
      travelActions.tickPathing(mc, player, ForagingRoute.LEAVE_A, null, PATH_TIMEOUT_TICKS);
      return;
    }
    final Target travel = grind().leg().travel();
    if (travelActions.isWithinTargetRadius(player, travel, 10.0)) {
      PathfindCommand.stopNavQuiet();
      setState(ForagingQuestState.START_COLLECTION_CHOP);
      return;
    }
    travelActions.tickPathing(mc, player, travel, ForagingQuestState.START_COLLECTION_CHOP);
  }

  private void tickWaitCollectionChop() {
    stateTicks++;
    look().clear();
    if (hopForContestedOak()) {
      return;
    }
    TreeLogType type = grind().leg().type();
    if (grind().reachedTarget(type)) {
      advanceCollection();
      return;
    }
    status =
        type.displayName()
            + " collection "
            + grind().level(type)
            + " / "
            + ForagingCollectionGrind.TARGET_COLLECTION_LEVEL;
    restartChopIfStalled();
  }

  private void advanceCollection() {
    if (!grind().advance()) {
      setState(ForagingQuestState.WAIT_FORAGING_12);
      return;
    }
    stopQuestChop();
    PathfindCommand.stopNavQuiet();
    actionTiming.clearPathRequest();
    stateTicks = 0;
    RyznBootstrap.eventBus()
        .post(
            new TreeChopActionEvent(TreeChopActionEvent.Action.SET_LOG_TYPE, grind().leg().type()));
    RyznSettings.get().save();
    beginCollectionTravel();
  }

  private void tickWaitForaging12(Minecraft mc) {
    stateTicks++;
    look().clear();
    if (hopForContestedOak()) {
      return;
    }
    final int foraging = grind().foragingLevel(mc);
    if (foraging >= ForagingCollectionGrind.FORAGING_TARGET_LEVEL) {
      setState(ForagingQuestState.WARP_FINAL_SELL_HUB);
      return;
    }
    status =
        foraging < 0
            ? "Waiting for TAB data (Foraging)"
            : "Foraging " + foraging + " / " + ForagingCollectionGrind.FORAGING_TARGET_LEVEL;
    restartChopIfStalled();
  }

  /**
   * The grind runs for hours, so a chopper that stopped or wedged is restarted rather than failed.
   * Both grind waits route back through {@code START_COLLECTION_CHOP}: it restarts the CURRENT leg,
   * and a leg already past its target falls straight back out on the next tick.
   */
  private void restartChopIfStalled() {
    if (!RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).isRunning()) {
      if (stateTicks > 20) {
        setState(ForagingQuestState.START_COLLECTION_CHOP);
      }
      return;
    }
    if (grind()
        .isStalled(
            RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).choppedCount(),
            CHOP_STALL_TICKS)) {
      setState(ForagingQuestState.START_COLLECTION_CHOP);
    }
  }

  private boolean hopForContestedOak() {
    if (grind().leg().type() != TreeLogType.OAK
        || RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).consecutiveLostTrees()
            < 3) {
      return false;
    }
    stopQuestChop();
    setState(ForagingQuestState.LOBBY_HOP_SEND_IS);
    status = "Three Oak trees lost in a row; switching Hub";
    return true;
  }

  void stopQuestChop() {
    if (chopSession.wasStartedByQuest()
        && RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).isRunning()) {
      RyznBootstrap.eventBus().post(new TreeChopActionEvent(TreeChopActionEvent.Action.STOP));
    }
    chopSession.markStopped();
  }

  private void tickWaitObjective(String objective, ForagingQuestState next) {
    stateTicks++;
    releaseAllKeys(Minecraft.getInstance().options);
    look().clear();
    if (sidebarObjectiveContains(objective)) {
      setState(next);
      return;
    }
    applyChatWait(
        chatWaits.objective(objective, npcInteractions.npcTimedOut(CHAT_TIMEOUT_TICKS)), next);
  }

  private void tickWaitRecent(String needle, ForagingQuestState next, int timeout, int sinceSeq) {
    stateTicks++;
    releaseAllKeys(Minecraft.getInstance().options);
    look().clear();
    applyChatWait(
        chatWaits.containsSince(sinceSeq, needle, npcInteractions.npcTimedOut(timeout)), next);
  }

  /**
   * Confirms arrival in a sub-zone by the scoreboard sidebar location line (e.g. "Birch Park"), not
   * a chat line - walking into a Foraging sub-area is an in-server transition that emits no
   * reliable chat, so the old "sending to server" wait hung forever. {@link
   * SkyBlockHud#currentArea()} reads the sidebar regardless of whether the HUD is toggled on.
   */
  private void tickWaitArea(String areaNeedle, ForagingQuestState next, int timeout) {
    stateTicks++;
    releaseAllKeys(Minecraft.getInstance().options);
    look().clear();
    final String area = SkyBlockHud.currentArea();
    if (area != null && normalizeSearchText(area).contains(normalizeSearchText(areaNeedle))) {
      setState(next);
      return;
    }
    status = state.label();
    if (timedOut(timeout)) {
      fail("Never entered " + areaNeedle);
    }
  }

  private void applyChatWait(ForagingChatWaits.Outcome outcome, ForagingQuestState next) {
    if (outcome.result() == ForagingChatWaits.Result.MATCHED) {
      setState(next);
    } else if (outcome.result() == ForagingChatWaits.Result.FAILED) {
      fail(outcome.message());
    } else {
      status = state.label();
    }
  }

  private boolean sidebarObjectiveContains(String needle) {
    final String objective = SkyBlockHud.currentObjective();
    return objective != null
        && normalizeSearchText(objective).contains(normalizeSearchText(needle));
  }

  private boolean advanceIfOwnedReward(
      LocalPlayer player, String itemName, ForagingQuestState next) {
    if (!inventoryItems.hasItem(player, itemName)) {
      return false;
    }
    setState(next);
    return true;
  }

  /**
   * After the mole reveal, "Talk to Molbert" is usually ALREADY the active objective (it lands at
   * the HELP step), so no NEW-objective chat line ever re-fires and waiting for one stalls forever.
   * Advance on any signal the reveal is done: the reward GUI already open (a leftover click opened
   * it), the compensation line, the objective line - or on timeout just try opening the reward
   * anyway; if the quest truly is not ready, the reward interact state fails loudly on its own.
   */
  private void tickWaitFinalTalk(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    look().clear();
    if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
      macroScreenOpened = true;
      setState(ForagingQuestState.WAIT_MOLBERT_REWARD_GUI);
      return;
    }
    if (chatWaits.hasObjectiveSinceState("Talk to Molbert")
        || chatHistory.containsSinceState("take this as compensation")
        || npcInteractions.npcTimedOut(CHAT_TIMEOUT_TICKS)) {
      setState(ForagingQuestState.INTERACT_FINAL_MOLBERT_REWARD);
      return;
    }
    // If the answer did not take, Molbert replays the speech and re-offers the prompt - answer it
    // again.
    // markStateStart drops the line just clicked so this fires once per fresh prompt; stateTicks
    // keeps
    // running so the hard ceiling above still ends the state.
    final ClickEvent mole = chatWaits.selectOptionClick("i know");
    if (mole != null) {
      MacroCommands.runChatClick(mc, mole);
      chatHistory.markStateStart();
      status = "Told Molbert he's a mole";
      return;
    }
    status = "Waiting for Molbert reward";
  }

  private void tickWaitAnyGui(ForagingQuestState next) {
    stateTicks++;
    releaseAllKeys(Minecraft.getInstance().options);
    look().clear();
    if (Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>) {
      macroScreenOpened = true;
      setState(next);
      return;
    }
    status = "Waiting for GUI";
    if (timedOut(GUI_TIMEOUT_TICKS)) {
      fail("GUI timed out");
    }
  }

  private void tickStartFullHarp(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    closeCurrentScreen(mc);
    look().clear();
    harpSession.start();
    harpSongsCleared = 0;
    status = "Completing Melody harp";
    setState(ForagingQuestState.WAIT_FULL_HARP);
  }

  private void tickWaitFullHarp(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    look().clear();
    switch (harpSession.result()) {
      case COMPLETE -> {
        setState(ForagingQuestState.CLOSE_AFTER_HARP);
        return;
      }
      case STOPPED -> {
        fail("Auto Harp stopped: " + harpSession.stopReason());
        return;
      }
      case RUNNING -> {}

      default -> {}
    }
    status = "Harp: " + harpSession.status();
    // Every song cleared is progress, so the watchdog starts over. Only a run that stops clearing
    // songs at all is actually stuck.
    final int cleared = harpSession.songsCleared();
    if (cleared != harpSongsCleared) {
      harpSongsCleared = cleared;
      stateTicks = 0;
      return;
    }
    if (timedOut(HARP_STALL_TICKS)) {
      fail("Harp stopped clearing songs");
    }
  }

  private void tickStepOntoCampfire(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (timedOut(CAMPFIRE_TIMEOUT_TICKS)) {
      fail("Campfire step timed out");
      return;
    }
    final Vec3 campfireAim =
        new Vec3(
            ForagingRoute.CAMPFIRE_CENTER.x() + 0.5,
            player.getEyeY(),
            ForagingRoute.CAMPFIRE_CENTER.z() + 0.5);
    if (route.isAtTarget(player, ForagingRoute.CAMPFIRE_CENTER)) {
      releaseAllKeys(mc.options);
      setState(ForagingQuestState.WAIT_TRIAL_FIRE);
      return;
    }
    if (look().aimAtPoint(player, campfireAim) && mc.options != null) {
      MacroKeys.set(mc.options.keyUp, true);
      MacroKeys.set(mc.options.keyShift, false);
      status = "Stepping onto campfire";
    }
  }

  private void tickPlaceTrap(
      Minecraft mc, LocalPlayer player, int trapNumber, ForagingQuestState next) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (chatWaits.trapPlaced(trapNumber)) {
      setState(next);
      return;
    }
    if (timedOut(TRAP_TIMEOUT_TICKS)) {
      fail("Trap " + trapNumber + " placement timed out");
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    // The trap is not an inventory item - just right-click the floor at the spot. Prefer an empty
    // hand (a held log would attempt a block place, the SkyBlock menu item would open a GUI).
    if (!inventoryActions.equipEmptyHand(player)
        && !inventoryActions.equipHandItem(mc, player, "axe")
        && inventoryActions.moveHandOffArmor(player)) {
      return;
    }
    // A straight-down ray lands on the floor at the player's own X/Z, and the trap only registers
    // near the
    // middle of the block - so being off-centre IS being off-target. Walk onto the exact centre
    // first.
    if (!stepToBlockCenter(mc, player, trapSpot(trapNumber))) {
      status = "Centering on trap " + trapNumber;
      return;
    }
    if (!look().aimDown(player)) {
      status = "Aiming down for trap " + trapNumber;
      return;
    }
    if (actionTiming.waitForUse()) {
      return;
    }
    if (mc.options != null) {
      setUseHeld(mc.options, true);
    }
    actionTiming.armUse(GuiActionTiming.randomDelayAtLeast(3));
    status = "Placing trap " + trapNumber;
  }

  private Target trapSpot(int trapNumber) {
    return switch (trapNumber) {
      case 1 -> ForagingRoute.TRAP_ONE;
      case 2 -> ForagingRoute.TRAP_TWO;
      default -> ForagingRoute.TRAP_THREE;
    };
  }

  /**
   * Sneak-walk onto the middle of {@code spot}; true once standing there (or once centering has run
   * long).
   */
  private boolean stepToBlockCenter(Minecraft mc, LocalPlayer player, Target spot) {
    final double dx = spot.x() + 0.5 - player.getX();
    final double dz = spot.z() + 0.5 - player.getZ();
    if (dx * dx + dz * dz <= TRAP_CENTER_TOLERANCE * TRAP_CENTER_TOLERANCE) {
      return true;
    }
    if (stateTicks > TRAP_CENTER_GIVEUP_TICKS || mc.options == null) {
      return true;
    }

    // Steer with the movement keys against the CURRENT yaw instead of turning to face the centre:
    // that
    // point is under the player's own feet, where the atan2 yaw to it is pure noise.
    final double[] steer = centerSteering(dx, dz, player.getYRot());
    MacroKeys.set(mc.options.keyUp, steer[0] > TRAP_CENTER_DEADZONE);
    MacroKeys.set(mc.options.keyDown, steer[0] < -TRAP_CENTER_DEADZONE);
    MacroKeys.set(mc.options.keyRight, steer[1] > TRAP_CENTER_DEADZONE);
    MacroKeys.set(mc.options.keyLeft, steer[1] < -TRAP_CENTER_DEADZONE);
    MacroKeys.set(
        mc.options.keyShift, true); // sneak: ~0.065 blocks/tick, small enough to land on the centre
    return false;
  }

  /**
   * World offset -> {forward, right} in the player's own frame. Package-private for the steering
   * test.
   */
  static double[] centerSteering(double dx, double dz, float yawDegrees) {
    final double yaw = Math.toRadians(yawDegrees);
    final double sin = Math.sin(yaw);
    final double cos = Math.cos(yaw);
    return new double[] {-sin * dx + cos * dz, -cos * dx - sin * dz};
  }

  void tickDelay(ForagingQuestState next, int ticks) {
    stateTicks++;
    releaseAllKeys(Minecraft.getInstance().options);
    look().clear();
    status = "Waiting " + Math.max(0, ticks - stateTicks) + " ticks";
    if (stateTicks >= ticks) {
      setState(next);
    }
  }

  private void tickDone(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    closeCurrentScreen(mc);
    if (stateTicks >= DONE_SETTLE_TICKS || mc.gui.screen() == null) {
      stopAll("completed", true);
    }
  }

  private void tickFailed(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    PathfindCommand.stopNavQuiet();
    if (chopSession.wasStartedByQuest()
        && RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).isRunning()) {
      RyznBootstrap.eventBus().post(new TreeChopActionEvent(TreeChopActionEvent.Action.STOP));
    }
    harpSession.stopOwnedMacro();
    if (mc.gui.screen() != null && macroScreenOpened) {
      closeCurrentScreen(mc);
    }
    status = "Continuing: " + continueReason;
    if (stateTicks < FAILURE_CONTINUE_DELAY_TICKS) {
      return;
    }
    beginSession();
  }

  boolean timedOut(int timeoutTicks) {
    return stateTicks >= timeoutTicks;
  }

  private boolean consumeEscapeStop(Minecraft mc) {
    final boolean down = isEscapeDown(mc);
    final boolean pressed = down && !escapeWasDown;
    escapeWasDown = down;
    return pressed;
  }

  private boolean isEscapeDown(Minecraft mc) {
    if (mc == null || mc.getWindow() == null) {
      return false;
    }
    return InputConstants.isKeyDown(mc.getWindow(), InputConstants.KEY_ESCAPE);
  }

  /**
   * Fine-grained resume points, in quest order. {@link #beginSession} starts the run at the
   * checkpoint chosen in the GUI "Start at" dropdown, so a restart mid-quest can skip past one-time
   * steps already completed (accepting a quest, claiming a reward, using the park scroll, the
   * campfire trial, placing traps) that the server will not let it repeat. Entry states are
   * PATH_/INTERACT_/START_ states that self-position (walk to the NPC, start the sub-macro), so
   * resuming at one is safe as long as you are roughly in that area.
   */
  @RequiredArgsConstructor
  private enum Checkpoint {
    OAK_BUY("Buy oak logs", ForagingQuestState.PATH_LUMBER_MERCHANT, 0),
    LUMBER_JACK_ACCEPT("Meet Lumber Jack", ForagingQuestState.PATH_LUMBER_JACK_START, 0),
    OAK_CHOP("Chop oak logs", ForagingQuestState.START_OAK_CHOP, 0),
    LUMBER_JACK_TURN_IN("Return to Lumber Jack", ForagingQuestState.PATH_LUMBER_JACK_RETURN, 0),
    BIRCH_TRAVEL("Travel to Birch Park", ForagingQuestState.PATH_BIRCH_TRANSFER, 0),
    CHARLIE_ACCEPT("Meet Charlie", ForagingQuestState.PATH_CHARLIE_START, 1),
    BIRCH_CHOP("Chop birch logs", ForagingQuestState.START_BIRCH_CHOP, 1),
    CHARLIE_TROUSERS("Claim Charlie's trousers", ForagingQuestState.PATH_CHARLIE_REWARD, 1),
    CHARLIE_SCROLL("Talk to Charlie again", ForagingQuestState.INTERACT_CHARLIE_AFTER_TROUSERS, 1),
    SPRUCE_TRAVEL("Travel to Spruce Woods", ForagingQuestState.PATH_SPRUCE_TRANSFER, 1),
    KELLY_ACCEPT("Meet Kelly", ForagingQuestState.PATH_KELLY_START, 2),
    SPRUCE_CHOP("Chop spruce logs", ForagingQuestState.START_SPRUCE_CHOP, 2),
    KELLY_SHIRT("Claim Kelly's shirt", ForagingQuestState.PATH_KELLY_REWARD, 2),
    DARK_TRAVEL("Travel to Dark Thicket", ForagingQuestState.PATH_DARK_TRANSFER, 2),
    RYAN_ACCEPT("Meet Ryan", ForagingQuestState.PATH_RYAN_BACK, 3),
    RYAN_TRIAL("Complete the campfire trial", ForagingQuestState.PATH_CAMPFIRE_ADJACENT, 3),
    RYAN_DARK_START("Start the dark oak task", ForagingQuestState.PATH_RYAN_CHOP, 3),
    DARK_CHOP("Chop dark oak logs", ForagingQuestState.START_DARK_CHOP, 3),
    RYAN_BADGE("Claim Ryan's badge", ForagingQuestState.PATH_RYAN_REWARD, 3),
    ACACIA_TRAVEL("Travel to the acacia area", ForagingQuestState.PATH_ACACIA_TRANSFER, 3),
    MELODY_ACCEPT("Meet Melody", ForagingQuestState.PATH_MELODY_START, 4),
    ACACIA_CHOP("Chop acacia logs", ForagingQuestState.START_ACACIA_CHOP, 4),
    MELODY_SHOES("Claim Melody's shoes", ForagingQuestState.PATH_MELODY_REWARD, 4),
    MELODY_HARP("Complete Melody's harp", ForagingQuestState.START_FULL_HARP, 4),
    JUNGLE_TRAVEL("Travel to Jungle Island", ForagingQuestState.PATH_JUNGLE_TRANSFER, 4),
    MOLBERT_ACCEPT("Meet Molbert", ForagingQuestState.PATH_MOLBERT_START, 5),
    JUNGLE_CHOP("Chop jungle logs", ForagingQuestState.START_JUNGLE_CHOP, 5),
    MOLBERT_LEAVE("Return to Molbert", ForagingQuestState.PATH_MOLBERT_LEAVE, 5),
    MOLBERT_TRAPS("Start the mole trap task", ForagingQuestState.PATH_MOLBERT_TRAPS, 5),
    PLACE_TRAPS("Place mole traps", ForagingQuestState.PATH_TRAP_ONE, 5),
    MOLBERT_AFTER_TRAPS(
        "Return after placing traps", ForagingQuestState.PATH_MOLBERT_LEAVE_AGAIN, 5),
    FINAL_TREK("Leave Molbert's area", ForagingQuestState.PATH_LEAVE_TWO_A, 5),
    FINAL_HELP("Help Molbert again", ForagingQuestState.PATH_FINAL_MOLBERT, 5),
    FINAL_MOLE("Tell Molbert the truth", ForagingQuestState.INTERACT_MOLBERT_MOLE, 5),
    FINAL_MOLE_HAT("Claim the Mole Hat", ForagingQuestState.INTERACT_FINAL_MOLBERT_REWARD, 5),
    WOOD_COLLECTIONS(
        "All wood collections III, then Oak to Foraging 12",
        ForagingQuestState.PATH_COLLECTION_AREA,
        0);

    final String label;
    final ForagingQuestState firstState;

    /**
     * How many legs of {@link #TRANSFER_CHAIN} a rejoin must walk before re-entering this
     * checkpoint. The park is ONE server - the "transfers" are plain walks between locations
     * (verified 2026-08-24: entering Spruce Woods printed "NEW AREA DISCOVERED" with no server hop
     * and no login packet) - but they are SEQUENTIAL: reaching the acacia transfer means walking
     * spruce then dark oak then acacia, so this is a leg COUNT, not a single destination. Zero for
     * the park entrance, and for the {@code *_TRAVEL} checkpoints, whose own first state already
     * walks their last leg.
     */
    final int transfers;
  }

  /**
   * Labels for the GUI "Start at" dropdown, in quest order (index 0 = start from the beginning).
   *
   * @return the checkpoint labels, the first meaning the beginning
   */
  public static String[] resumeCheckpointLabels() {
    final Checkpoint[] all = Checkpoint.values();
    final String[] labels = new String[all.length];
    for (int i = 0; i < all.length; i++) {
      labels[i] = all[i].label;
    }
    return labels;
  }

  /**
   * In-memory progress for THIS game session only (never persisted): continuation after a boundary
   * miss starts from the stage the run reached, instead of the user stepping the dropdown forward
   * by hand. A game relaunch forgets it and falls back to the saved dropdown value; a manual
   * dropdown pick overrides it; finishing the quest clears it.
   */
  private int sessionCheckpoint;

  /** First gate to walk from inside the park: {@code /warp park} lands past the birch one. */
  private static final int PARK_ENTRANCE_LEG = 1;

  /** The park transfers in walk order. Sequential: acacia is reached via spruce then dark oak. */
  static final Target[] transferChain = {
    ForagingRoute.BIRCH_TRANSFER,
    ForagingRoute.SPRUCE_TRANSFER,
    ForagingRoute.DARK_TRANSFER,
    ForagingRoute.ACACIA_TRANSFER,
    ForagingRoute.JUNGLE_TRANSFER,
  };

  /** The states that walk each entry of {@link #transferChain}, in the same order. */
  static final ForagingQuestState[] transferStates = {
    ForagingQuestState.PATH_BIRCH_TRANSFER,
    ForagingQuestState.PATH_SPRUCE_TRANSFER,
    ForagingQuestState.PATH_DARK_TRANSFER,
    ForagingQuestState.PATH_ACACIA_TRANSFER,
    ForagingQuestState.PATH_JUNGLE_TRANSFER,
  };

  /** The target the current walk last asked the pathfinder for, so a detour swap re-paths. */
  Target pathTarget;

  /** Set by {@link #resumeAfterRejoin}; consumed by the next {@link #beginSession}. */
  private boolean walkBackOnStart;

  /** Set by {@link #resumeAfterDeath}; consumed by the next {@link #beginSession}. */
  private boolean deathResumeOnStart;

  /** Set by {@link #armParkWarpResume}; consumed by the next {@link #beginSession}. */
  private boolean parkWarpOnStart;

  /** A Level 7 collection-stage pick, consumed by the next collection-only start. */
  private int collectionStartLeg = -1;

  /** A Level 7 collection cleanup-stage pick, consumed by the next collection-only start. */
  private boolean collectionFinalSaleOnStart;

  int walkBackLeg;
  int walkBackLegs;
  ForagingQuestState walkBackNext = ForagingQuestState.IDLE;

  private void trackCheckpoint(ForagingQuestState next) {
    if (next == ForagingQuestState.DONE) {
      sessionCheckpoint = 0;
      return;
    }
    final Checkpoint[] all = Checkpoint.values();
    for (int i = 0; i < all.length; i++) {
      if (all[i].firstState == next) {
        sessionCheckpoint = i + 1;
        return;
      }
    }
  }

  /**
   * GUI hook: a manual "Resume from" pick beats the session progress.
   *
   * @param index the picked checkpoint index
   */
  public void onResumeCheckpointPicked(int index) {
    sessionCheckpoint = index;
    collectionStartLeg = index == Checkpoint.values().length && !parkWarpOnStart ? 0 : -1;
  }

  /**
   * Labels for the Level 7 collection-grind stages, in the same order as the runner's Park route.
   *
   * @return the stage labels in route order
   */
  public static String[] collectionGrindStageLabels() {
    return ForagingCollectionGrind.legLabels();
  }

  /**
   * Start a collection-only run at one of the existing Park-wood legs.
   *
   * @param index the Park-wood leg to start at
   */
  public void selectCollectionGrindLeg(int index) {
    final TreeLogType type = ForagingCollectionGrind.typeAt(index);
    if (type == null) {
      return;
    }
    collectionStartLeg = index;
    final RyznSettings cfg = RyznSettings.get();
    RyznBootstrap.eventBus()
        .post(new TreeChopActionEvent(TreeChopActionEvent.Action.SET_LOG_TYPE, type));
    cfg.save();
  }

  /** Start a collection-only run directly at its final Lumber Merchant log sale. */
  public void selectCollectionFinalSale() {
    collectionFinalSaleOnStart = true;
  }

  /**
   * What the GUI dropdown shows: live session progress while there is any, else the saved value.
   *
   * @return the checkpoint index the dropdown shows
   */
  public int displayedResumeCheckpoint() {
    return sessionCheckpoint > 0 ? sessionCheckpoint : savedResumeCheckpoint.get();
  }

  /**
   * The checkpoint this run begins at: session progress first, else the saved "Resume from" value.
   */
  private Checkpoint resumeCheckpoint() {
    final Checkpoint[] all = Checkpoint.values();
    return all[Mth.clamp(displayedResumeCheckpoint() - 1, 0, all.length - 1)];
  }

  void setState(ForagingQuestState next) {
    if (next == state) {
      return;
    }
    state = next;
    status = next.label();
    stateTicks = 0;
    pathTarget = null;
    trackCheckpoint(next);
    blockedTicks = 0;
    sidestepActive = false;
    actionTiming.enterState();
    look().resetSettle();
    gaze.release();
    inventoryActions.resetClick();
    chatHistory.markStateStart();
    unansweredPromptLogged = false;
    if (next == ForagingQuestState.PATH_RYAN_BACK) {
      ryanApproachTarget = null;
      ryanWaypointReached = false;
    }

    switch (next) {
      case SELL_INVENTORY, SELL_FINAL_LOGS -> inventoryClear.begin();
      case USE_PARK_SCROLL -> parkScrollUseStartSequence = chatHistory.stateStartSequence();
      case PATH_BIRCH_TRANSFER,
          PATH_SPRUCE_TRANSFER,
          PATH_DARK_TRANSFER,
          PATH_ACACIA_TRANSFER,
          PATH_JUNGLE_TRANSFER ->
          chatHistory.markTravelStart();
      case RESUME_TRAVEL,
          PATH_LUMBER_MERCHANT,
          PATH_LUMBER_JACK_START,
          PATH_LUMBER_JACK_RETURN,
          PATH_CHARLIE_START,
          PATH_CHARLIE_REWARD,
          PATH_KELLY_START,
          PATH_KELLY_REWARD,
          PATH_RYAN_BACK,
          PATH_RYAN_CHOP,
          PATH_RYAN_REWARD,
          PATH_MELODY_START,
          PATH_MELODY_REWARD,
          PATH_MOLBERT_START,
          PATH_MOLBERT_LEAVE,
          PATH_MOLBERT_TRAPS,
          PATH_MOLBERT_LEAVE_AGAIN,
          PATH_FINAL_MOLBERT -> {
        route.resetNpc();
        look().clear();
      }
      case BUY_LUMBER_LOGS -> {
        logPurchaseProgress.reset();
      }
      case PATH_COLLECTION_AREA -> {
        stopQuestChop();
        RyznBootstrap.eventBus()
            .post(
                new TreeChopActionEvent(
                    TreeChopActionEvent.Action.SET_LOG_TYPE, grind().leg().type()));
        RyznSettings.get().save();
      }
      case WARP_FINAL_SELL_HUB -> stopQuestChop();
      default -> {}
    }
  }

  private void resetRuntime() {
    ryanRejected = false;
    ryanApproachTarget = null;
    ryanWaypointReached = false;
    state = ForagingQuestState.IDLE;
    status = "Starting Foraging Quest Unlocker";
    stateTicks = 0;
    parkScrollUseStartSequence = 0;
    hubHopSettleTicks = 0;
    ticksSinceNpcChat = NPC_CHAT_GRACE_TICKS;
    actionTiming.enterState();
    look().resetSettle();
    inventoryActions.resetClick();
    macroScreenOpened = false;
    chopSession.reset();
    grind().reset();
    RyznBootstrap.eventBus()
        .post(new TreeChopActionEvent(TreeChopActionEvent.Action.RESET_LOST_TREES));
    harpSession.reset();
    route.resetNpc();
    logPurchaseProgress.reset();
    chatHistory.reset();
    look().clear();
    gaze.release();
    walkBackLeg = 0;
    walkBackLegs = 0;
    walkBackNext = ForagingQuestState.PATH_COLLECTION_AREA;
  }

  void fail(String message) {
    continueReason = message;
    lastRunCompleted = false;
    lastStopReason = message;
    setState(ForagingQuestState.FAILED);
    status = "Continuing: " + message;
  }

  private void stopAll(String reason, boolean completed) {
    final Minecraft mc = Minecraft.getInstance();
    releaseAllKeys(mc.options);
    PathfindCommand.stopNavQuiet();
    if (chopSession.wasStartedByQuest()
        && RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).isRunning()) {
      RyznBootstrap.eventBus().post(new TreeChopActionEvent(TreeChopActionEvent.Action.STOP));
    }
    harpSession.stopOwnedMacro();
    if (mc.gui.screen() != null && macroScreenOpened) {
      closeCurrentScreen(mc);
    }
    PathfindCommand.setSneak(false);
    resetRuntime();
    enabled.set(false);
    lastRunCompleted = completed;
    if (completed) {
      grind().clearCollections();
    }
    lastStopReason = reason;
    status = completed ? "Completed" : "Stopped: " + reason;
    persist();
  }

  /**
   * @description Returns the log purchase collaborator, creating it on first use.
   * @return the purchase actions owned by this macro
   */
  ForagingLogPurchaseActions logPurchaseActions() {
    if (logPurchaseActions == null) {
      logPurchaseActions =
          new ForagingLogPurchaseActions(
              logPurchaseProgress, menuSlots, inventoryItems, actionTiming);
    }
    return logPurchaseActions;
  }

  /**
   * @description Returns the collection-grind collaborator, creating it on first use.
   * @return the grind state owned by this macro
   */
  private ForagingCollectionGrind grind() {
    if (grind == null) {
      grind = new ForagingCollectionGrind();
    }
    return grind;
  }

  /**
   * @description Marks the settings file dirty so the owned values are saved.
   */
  private void persist() {
    RyznSettings.get().save();
  }

  @Override
  public boolean menuEnabled() {
    return isEnabled();
  }

  @Override
  public void menuToggle() {
    RyznBootstrap.eventBus()
        .post(
            new ForagingQuestActionEvent(
                ForagingQuestActionEvent.Action.SET_ENABLED, !isEnabled()));
  }

  /**
   * @description Returns this macro's aim controller from the shared rotation subsystem.
   * @return the controller the rotation manager keeps for this owner
   */
  LookController look() {
    return RyznBootstrap.rotationManager().lookController(this, 88f);
  }
}
