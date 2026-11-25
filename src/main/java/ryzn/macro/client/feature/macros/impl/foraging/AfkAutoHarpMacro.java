package ryzn.macro.client.feature.macros.impl.foraging;

import static ryzn.macro.client.MacroInput.isCrosshairOn;
import static ryzn.macro.client.MacroInput.releaseAllKeys;
import static ryzn.macro.client.MacroInput.releaseMovementAndAttack;
import static ryzn.macro.client.MacroInput.releaseUse;
import static ryzn.macro.client.MacroInput.setUseHeld;
import static ryzn.macro.client.MacroInput.setUseHeldOn;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.desiredYawPitch;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.entityLookPoint;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.isLookWithin;
import static ryzn.macro.client.util.gui.MacroScreens.cleanTitle;
import static ryzn.macro.client.util.gui.MacroScreens.closeCurrentScreen;
import static ryzn.macro.client.util.gui.MacroScreens.titleContains;
import static ryzn.macro.client.util.inventory.MacroSlots.clickSlotUnguarded;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;
import static ryzn.macro.client.util.text.MacroText.stripFormatting;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.settings.ConfigSection;
import ryzn.macro.client.config.settings.Setting;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.AfkAutoHarpActionEvent;
import ryzn.macro.client.core.events.api.AutoHarpActionEvent;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.debug.MacroStateTrace;
import ryzn.macro.client.feature.macros.api.Macro;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.input.MacroKeys;
import ryzn.macro.client.util.text.RyznChat;
import ryzn.macro.client.util.world.MacroObstruction;

/**
 * Auto Harp full completion - a controller macro that drives the full Melody's Harp loop and leans
 * on {@link AutoHarpMacro} to actually press the falling notes. This class only handles navigation,
 * menu selection, opening the harp, scoring/retry, and song progression; it never touches note
 * slots itself.
 *
 * <p>Flow: path to Melody, find + aim at her, right-click to open her song menu, pick the leftmost
 * song not yet cleared, close the menu, aim at the harp's quartz slab, right-click to open the Harp
 * GUI (Auto Harp then plays), wait for the completion + score chat, retry the same song while the
 * score is below the threshold, then advance to the next song. All interaction is vanilla ({@code
 * interact}/{@code useItemOn}); walking is Ryzn's native pathfinder and rotation is the shared
 * per-frame ease used by the other macros.
 */
public final class AfkAutoHarpMacro extends Macro implements ConfigSection {

  @RequiredArgsConstructor
  private enum State {
    IDLE("Idle"),
    PATHING_TO_MELODY("Pathing to Melody"),
    FIND_MELODY("Searching for Melody"),
    ROTATE_TO_MELODY("Aiming at Melody"),
    OPEN_MELODY_MENU("Opening Melody menu"),
    SELECT_SONG("Selecting song"),
    WALK_TO_HARP_STAND("Walking to harp spot"),
    ROTATE_TO_HARP("Rotating to harp"),
    OPEN_HARP("Opening harp"),
    WAIT_FOR_HARP("Playing song"),
    HARP_BUSY_WAIT("Harp busy, waiting"),
    SONG_RESULT("Checking score"),
    NEXT_SONG("Selecting next song"),
    DONE("All songs complete"),
    FAILED("Failed");

    private final String label;
  }

  private static final int PATH_TIMEOUT_TICKS = 20 * 45;
  private static final int NPC_SEARCH_TIMEOUT_TICKS = 20 * 10;
  private static final int INTERACT_TIMEOUT_TICKS = 20 * 20;
  private static final int MENU_STEP_TIMEOUT_TICKS = 20 * 12;
  private static final int ROTATE_TIMEOUT_TICKS = 20 * 8;
  private static final int OPEN_HARP_TIMEOUT_TICKS = 20 * 15;
  private static final int WAIT_HARP_TIMEOUT_TICKS = 20 * 90;
  private static final int SCORE_WAIT_TICKS = 20 * 3;
  private static final int CLOSE_TIMEOUT_TICKS = 20 * 5;
  private static final int MENU_CLICK_COOLDOWN_TICKS = 4;
  private static final int MAX_MELODY_PATH_ATTEMPTS = 8;
  private static final int MAX_SONG_RETRIES = 25;
  private static final int MAX_HARP_OPEN_ATTEMPTS = 6;
  private static final int BLOCKED_RELOCATE_TICKS = MacroObstruction.BLOCKED_RELOCATE_TICKS;

  private static final float AIM_SETTLED_THRESHOLD = 2.0f;
  // Walking only needs the yaw roughly right; a stricter gate left the forward key unpressed.
  private static final float STEP_YAW_THRESHOLD = 6.0f;
  private static final int STEP_ONTO_TIMEOUT_TICKS = 20 * 5; // nudge can't finish -> drop the tile

  private final Setting<Boolean> enabled =
      addSetting(Setting.bool("afk_auto_harp_enabled", "Full completion", false));
  private final Setting<Integer> retryDelayTicks =
      addSetting(Setting.integer("afk_auto_harp_retry_delay_ticks", "Retry delay", 20, 10, 100));
  private final Setting<Integer> minScore =
      addSetting(Setting.integer("afk_auto_harp_min_score", "Minimum score", 90, 50, 100));
  private final Setting<Boolean> debugHud =
      addSetting(Setting.bool("afk_auto_harp_debug_hud", "Debug HUD", true));

  private final float[] desiredAim = new float[2];
  private boolean lastRunCompleted;
  private String lastStopReason = "";

  private State state = State.IDLE;
  private String status = "Disabled";
  private int stateTicks;
  private int blockedTicks;

  private boolean aimSettled;
  private boolean songClickSent;
  private int pendingSongSlot = -1;

  private final HarpSongSession songSession = new HarpSongSession();
  private final HarpChatInterpreter chatInterpreter = new HarpChatInterpreter();
  private final HarpSongMenu songMenu = new HarpSongMenu();
  private final HarpRouteAttempts routeAttempts = new HarpRouteAttempts();
  private final HarpActionCooldowns actionCooldowns = new HarpActionCooldowns();
  private final HarpWorldTargets worldTargets = new HarpWorldTargets();
  private final HarpStandPositions stand = new HarpStandPositions();
  private final MacroStateTrace trace = new MacroStateTrace("afkharp");

  // The tile we are currently trying to stand on to use the harp (primary -409,110,70 or a free
  // alternate).
  private BlockPos currentStand;
  // Ticks spent hand-walking the last block onto currentStand (see stepOntoStand).
  private int stepOntoTicks;

  // Whether Auto Harp was already on before this session (so we can restore it cleanly).
  private boolean autoHarpWasEnabled;

  // Rotation (driven per-frame in onRenderFrame, mirroring the Bea / Gold Shovel / Mining macros).
  private Vec3 lookTarget;

  /**
   * @description Creates the process-lifetime full-completion controller with its original saved
   *     run toggle, retry delay, score threshold, and debug HUD preference.
   */
  public AfkAutoHarpMacro() {
    super(
        "afk_auto_harp",
        "AFK Auto Harp",
        "Paths to Melody, picks each song, and replays it until the score threshold is met.");
  }

  /**
   * @description Loads the saved values under their original keys, clamping the retry delay to
   *     10-100 ticks and the score threshold to 50-100 percent. The live session is not started
   *     here; {@link #applyConfig()} does that once every section has loaded.
   * @param object the section JSON, where missing or mistyped values fall back to defaults
   */
  @Override
  public void read(JsonObject object) {
    ConfigSection.readSettings(object, enabled, retryDelayTicks, minScore, debugHud);
  }

  /**
   * @description Writes the saved values in their original key order.
   * @return a new section object for the settings file
   */
  @Override
  public JsonObject write() {
    return ConfigSection.writeSettings(enabled, retryDelayTicks, minScore, debugHud);
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
   * @description Feeds Melody's chat lines to the song interpreter at the original callback slot.
   * @param gamemessageevent the received message; overlays and blank lines are ignored
   */
  public void onGameMessageEvent(GameMessageEvent gamemessageevent) {
    if (gamemessageevent.overlay() || !isEnabled() || gamemessageevent.message() == null) {
      return;
    }
    final String text = stripFormatting(gamemessageevent.message().getString()).trim();
    if (text.isEmpty()) {
      return;
    }
    chatInterpreter.accept(text, songSession);
  }

  /**
   * @description Applies a run-state request from the menu, failsafe, or foraging quest.
   * @param afkautoharpactionevent the requested run state
   */
  @Subscribe
  public void onAfkAutoHarpActionEvent(AfkAutoHarpActionEvent afkautoharpactionevent) {
    setEnabled(afkautoharpactionevent.enabled());
  }

  /**
   * @description Reports whether the full-completion loop is running.
   * @return the saved run toggle
   */
  public boolean isEnabled() {
    return enabled.get();
  }

  /**
   * @description Reports whether the previous run ended by clearing every song.
   * @return true only after a completed run, false after a stop or failure
   */
  public boolean wasLastRunCompleted() {
    return lastRunCompleted;
  }

  /**
   * @description Returns the status that ended the previous run.
   * @return the final status, or an empty string while running or before the first run
   */
  public String lastStopReason() {
    return lastStopReason;
  }

  /**
   * @description Starts or stops the loop, announcing a real transition in chat. Starting also
   *     enables Auto Harp for the note presses; stopping restores it if this run turned it on.
   * @param on the requested run state
   */
  public void setEnabled(boolean on) {
    if (isEnabled() == on) {
      persist();
      return;
    }
    enabled.set(on);
    RyznChat.announce("AFK Auto Harp", on);
    if (on) {
      beginSession();
    } else {
      stopAll("Disabled");
    }
    persist();
  }

  /**
   * @description Returns how long a busy harp is left before the next attempt.
   * @return the saved retry delay in ticks, between 10 and 100
   */
  public int getRetryDelay() {
    return retryDelayTicks.get();
  }

  /**
   * @description Changes and saves the busy-harp retry delay.
   * @param value the requested delay in ticks, clamped to 10-100
   */
  public void setRetryDelay(int value) {
    retryDelayTicks.set(value);
    persist();
  }

  /**
   * @description Returns the score a song needs before the loop moves on.
   * @return the saved threshold percentage, between 50 and 100
   */
  public int getMinScore() {
    return minScore.get();
  }

  /**
   * @description Changes and saves the score threshold.
   * @param value the requested percentage, clamped to 50-100
   */
  public void setMinScore(int value) {
    minScore.set(value);
    persist();
  }

  /**
   * @description Reports whether the macro HUD shows the detailed debug rows.
   * @return the saved debug HUD preference
   */
  public boolean isDebugHud() {
    return debugHud.get();
  }

  /**
   * @description Decides whether the debug HUD should draw this frame.
   * @param mc the current client, which may be missing a player during loading
   * @return true while running with the debug HUD on and the vanilla HUD visible
   */
  public boolean shouldRenderHud(Minecraft mc) {
    return isEnabled() && isDebugHud() && mc != null && mc.player != null && !mc.gui.hud.isHidden();
  }

  /**
   * @description Returns the current player-facing status line.
   * @return the live status, or Disabled when the loop is off
   */
  public String statusText() {
    return isEnabled() ? status : "Disabled";
  }

  /**
   * @description Returns the menu slot of the song being played.
   * @return the slot index, or a negative value before a song is chosen
   */
  public int currentSongSlot() {
    return songSession.currentSongSlot();
  }

  /**
   * @description Returns the position of the chosen song in Melody's list.
   * @return the song number shown on the HUD
   */
  public int selectedSongNumber() {
    return songSession.selectedSongNumber();
  }

  /**
   * @description Returns the display name of the chosen song.
   * @return the song name, or a placeholder before a song is chosen
   */
  public String lastSongName() {
    return songSession.displaySongName();
  }

  /**
   * @description Returns the last score Melody reported.
   * @return the score percentage from the latest completion message
   */
  public int lastScore() {
    return songSession.lastScore();
  }

  /**
   * @description Returns how many songs this run has cleared.
   * @return the completed song count
   */
  public int songsCompleted() {
    return songSession.completedSongCount();
  }

  /**
   * @description Returns the latest harp-related chat line for the debug HUD.
   * @return the last interpreted line, or an empty string
   */
  public String lastChatLine() {
    return songSession.lastChatLine();
  }

  /**
   * @description Advances the workflow one client tick. Melody's reward menu is closed on sight,
   *     and every key is released while the player or world is missing.
   * @param mc the current client
   */
  public void onClientTick(Minecraft mc) {
    trace.log(state.name(), status);
    if (!isEnabled()) {
      return;
    }

    final LocalPlayer player = mc.player;
    final ClientLevel level = mc.level;
    if (player == null || level == null) {
      releaseAllKeys(mc.options);
      return;
    }

    // Melody's reward menu ("Melody's Hair") can interrupt the loop - just close it (ESC) and
    // resume.
    if (isMelodysHairScreen(mc)) {
      releaseAllKeys(mc.options);
      closeCurrentScreen(mc);
      return;
    }

    switch (state) {
      case PATHING_TO_MELODY -> tickPathingToMelody(mc, player, level);
      case FIND_MELODY -> tickFindMelody(mc, player, level);
      case ROTATE_TO_MELODY -> tickRotateToMelody(mc, player, level);
      case OPEN_MELODY_MENU -> tickOpenMelodyMenu(mc, player, level);
      case SELECT_SONG -> tickSelectSong(mc, player);
      case WALK_TO_HARP_STAND -> tickWalkToHarpStand(mc, player, level);
      case ROTATE_TO_HARP -> tickRotateToHarp(mc, player, level);
      case OPEN_HARP -> tickOpenHarp(mc, player, level);
      case WAIT_FOR_HARP -> tickWaitForHarp(mc);
      case HARP_BUSY_WAIT -> tickHarpBusyWait(mc);
      case SONG_RESULT -> tickSongResult(mc);
      case NEXT_SONG -> tickNextSong(mc);
      case DONE -> finish();
      case FAILED -> tickFailed(mc);
      default -> beginSession();
    }
  }

  /**
   * @description Applies the functional aim on the game tick, so aiming works at any frame rate.
   *     Does nothing while navigation owns the look or no aim target is set.
   */
  public void tickLook() {
    if (!shouldLockMouse() || lookTarget == null || WalkingNavigation.isNavigationActive()) {
      return;
    }
    final LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) {
      return;
    }
    desiredYawPitch(player.getEyePosition(), lookTarget, desiredAim);
    RyznBootstrap.rotationManager().aimStandard(this, player, desiredAim[0], desiredAim[1]);
  }

  /**
   * @description Repeats the tick aim each render frame for a smooth camera turn.
   */
  public void onRenderFrame() {
    tickLook();
  }

  /**
   * @description Reports whether the macro currently owns the camera.
   * @return true in the aim and open states, and during the last-block nudge onto the harp spot
   */
  public boolean shouldLockMouse() {
    if (!isEnabled() || WalkingNavigation.isNavigationActive()) {
      return false;
    }
    return switch (state) {
      case ROTATE_TO_MELODY, OPEN_MELODY_MENU, ROTATE_TO_HARP, OPEN_HARP -> true;
      // Only aims during the last-block nudge: lookTarget is null for the rest of the walk.
      case WALK_TO_HARP_STAND -> lookTarget != null;
      default -> false;
    };
  }

  /**
   * @description Resets the run, turns on Auto Harp for the note presses, and starts pathing.
   */
  private void beginSession() {
    lastRunCompleted = false;
    lastStopReason = "";
    resetRuntime();
    enabled.set(true);
    autoHarpWasEnabled = RyznBootstrap.macroManager().getByClass(AutoHarpMacro.class).isEnabled();
    if (!autoHarpWasEnabled) {
      RyznBootstrap.eventBus().post(new AutoHarpActionEvent(true));
    }
    setState(State.PATHING_TO_MELODY);
  }

  /**
   * @description Paths to Melody until she is within interaction range. Fails after too many path
   *     attempts or the path timeout, and pauses while a screen is open.
   * @param mc the current client
   * @param player the local player
   * @param level the loaded world
   */
  private void tickPathingToMelody(Minecraft mc, LocalPlayer player, ClientLevel level) {
    stateTicks++;
    releaseAllKeys(mc.options);
    clearLook();

    if (mc.gui.screen() != null) {
      WalkingNavigation.stopNavQuiet();
      status = "Paused while a screen is open";
      return;
    }
    if (routeAttempts.exceededMelodyPaths(MAX_MELODY_PATH_ATTEMPTS)) {
      fail("Could not reach Melody");
      return;
    }
    if (stateTicks % 5 == 1) {
      final Entity npc = worldTargets.findMelody(level);
      if (npc != null && MacroObstruction.isWithinInteract(player, npc)) {
        worldTargets.rememberMelody(npc);
        WalkingNavigation.stopNavQuiet();
        setState(State.FIND_MELODY);
        return;
      }
    }
    if (worldTargets.isAtMelody(player)) {
      WalkingNavigation.stopNavQuiet();
      setState(State.FIND_MELODY);
      return;
    }
    if (!routeAttempts.isPathRequested()) {
      BlockPos melodyPosition = worldTargets.melodyPosition();
      if (!WalkingNavigation.startToQuiet(
          melodyPosition.getX(), melodyPosition.getY(), melodyPosition.getZ())) {
        fail("Could not start path to Melody");
        return;
      }
      routeAttempts.markPathRequested();
      status = "Pathing to Melody";
      return;
    }
    if (!WalkingNavigation.isNavigationActive() && stateTicks > 20) {
      setState(State.FIND_MELODY);
      return;
    }
    if (stateTicks > PATH_TIMEOUT_TICKS) {
      fail("Path to Melody timed out");
    }
  }

  /**
   * @description Locates Melody near the player, resuming the matching state if one of the macro's
   *     menus is already open. Fails when she cannot be found in time.
   * @param mc the current client
   * @param player the local player
   * @param level the loaded world
   */
  private void tickFindMelody(Minecraft mc, LocalPlayer player, ClientLevel level) {
    stateTicks++;
    releaseAllKeys(mc.options);
    clearLook();

    if (mc.gui.screen() != null) {
      if (isMelodyMenu(mc)) {
        setState(State.SELECT_SONG);
        return;
      }
      if (isHarpScreenOpen(mc)) {
        setState(State.WAIT_FOR_HARP);
        return;
      }
      closeCurrentScreen(mc);
      return;
    }

    final Entity npc = worldTargets.updateMelody(level);
    if (npc == null) {
      if (stateTicks > NPC_SEARCH_TIMEOUT_TICKS) {
        fail("Melody not found");
      }
      return;
    }
    if (!MacroObstruction.isWithinInteract(player, npc)) {
      status = "Melody out of range";
      setState(State.PATHING_TO_MELODY);
      return;
    }
    WalkingNavigation.stopNavQuiet();
    setState(State.ROTATE_TO_MELODY);
  }

  /**
   * @description Turns toward Melody and moves on once the aim settles or the turn times out.
   * @param mc the current client
   * @param player the local player
   * @param level the loaded world
   */
  private void tickRotateToMelody(Minecraft mc, LocalPlayer player, ClientLevel level) {
    stateTicks++;
    releaseAllKeys(mc.options);
    if (mc.gui.screen() != null) {
      handleStrayScreen(mc);
      return;
    }

    final Entity npc = worldTargets.updateMelody(level);
    if (npc == null) {
      if (stateTicks > NPC_SEARCH_TIMEOUT_TICKS) {
        setState(State.FIND_MELODY);
      }
      return;
    }
    if (!MacroObstruction.isWithinInteract(player, npc)) {
      setState(State.FIND_MELODY);
      return;
    }
    if (aimAtEntity(player, npc) || stateTicks > ROTATE_TIMEOUT_TICKS) {
      setState(State.OPEN_MELODY_MENU);
    }
  }

  /**
   * @description Right-clicks Melody to open her song menu. The click only fires while the
   *     crosshair pick is Melody herself, and a player blocking her triggers a sidestep.
   * @param mc the current client
   * @param player the local player
   * @param level the loaded world
   */
  private void tickOpenMelodyMenu(Minecraft mc, LocalPlayer player, ClientLevel level) {
    stateTicks++;
    releaseAllKeys(mc.options);

    if (isMelodyMenu(mc)) {
      setState(State.SELECT_SONG);
      return;
    }
    if (isHarpScreenOpen(mc)) {
      setState(State.WAIT_FOR_HARP);
      return;
    }
    if (mc.gui.screen() != null) {
      if (stateTicks > INTERACT_TIMEOUT_TICKS) {
        fail("Unexpected screen while opening Melody");
      } else {
        closeCurrentScreen(mc);
      }
      return;
    }
    if (stateTicks > INTERACT_TIMEOUT_TICKS) {
      fail("Melody menu did not open");
      return;
    }

    final Entity npc = worldTargets.updateMelody(level);
    if (npc == null) {
      if (stateTicks > NPC_SEARCH_TIMEOUT_TICKS) {
        fail("Melody not found");
      }
      return;
    }
    if (WalkingNavigation.isNavigationActive()) {
      status = "Relocating for a clear view of Melody";
      return;
    }
    // Interaction norm: click from at most 3 blocks; farther than that, walk back in first.
    if (!MacroObstruction.isWithinInteract(player, npc)) {
      setState(State.PATHING_TO_MELODY);
      return;
    }
    if (!aimAtEntity(player, npc)) {
      return;
    }
    // Norm: the crosshair pick must be Melody herself and nobody stacked on the bot. A persistent
    // blocker triggers a sidestep to a tile with a clear line instead of waiting them out.
    if (!isCrosshairOn(mc, npc) || MacroObstruction.isPlayerStackedOn(level, player)) {
      if (++blockedTicks >= BLOCKED_RELOCATE_TICKS) {
        blockedTicks = 0;
        final BlockPos side =
            MacroObstruction.findSidestep(
                level, player, npc.blockPosition(), entityLookPoint(npc), npc);
        if (side != null) {
          WalkingNavigation.startToQuiet(side.getX(), side.getY(), side.getZ());
        }
      }
      status = "Player blocking Melody";
      return;
    }
    blockedTicks = 0;
    if (actionCooldowns.waitForInteraction()) {
      return;
    }
    if (mc.options != null) {
      // Exact-intent press: only fires while the crosshair pick is Melody herself, so a player
      // standing in front of her can never receive the click.
      setUseHeldOn(mc.options, true, npc);
    }
    actionCooldowns.armInteraction(Math.max(2, MENU_CLICK_COOLDOWN_TICKS));
    status = "Opening Melody menu";
  }

  /**
   * @description Clicks the first uncleared song in Melody's menu, re-clicking the same committed
   *     slot until the menu closes. Finishes the run when no selectable song remains.
   * @param mc the current client
   * @param player the local player
   */
  private void tickSelectSong(Minecraft mc, LocalPlayer player) {
    stateTicks++;
    releaseAllKeys(mc.options);

    if (isHarpScreenOpen(mc)) {
      setState(State.WAIT_FOR_HARP);
      return;
    }
    if (!isMelodyMenu(mc)) {
      // Hypixel closes the menu itself once the song registers - that close IS the confirmation.
      if (songClickSent) {
        setState(State.WALK_TO_HARP_STAND);
      } else if (stateTicks > MENU_STEP_TIMEOUT_TICKS) {
        fail("Melody menu closed early");
      }
      return;
    }
    if (actionCooldowns.waitForMenuClick()) {
      return;
    }
    if (songClickSent) {
      // Menu still open after the click: it did not take, so click again (bounded by the step
      // timeout).
      if (stateTicks > MENU_STEP_TIMEOUT_TICKS) {
        fail("Song selection did not register");
        return;
      }
    }
    if (mc.gameMode == null) {
      fail("Interaction manager missing");
      return;
    }

    final AbstractContainerMenu menu = ((AbstractContainerScreen<?>) mc.gui.screen()).getMenu();
    // Once a song is committed, every retry re-clicks THAT slot. Re-scanning would return the next
    // song
    // and rename the selection, so the score of the song actually playing lands on its neighbour -
    // the
    // neighbour gets marked complete unplayed and the played one repeats.
    if (pendingSongSlot < 0) {
      final List<HarpSongMenu.Song> songs = songMenu.scan(menu, player, getMinScore());
      final HarpSongMenu.Song pick = songMenu.firstSelectable(songs, songSession);
      if (pick == null) {
        setState(State.DONE);
        return;
      }
      songSession.selectSong(pick.order(), pick.slotIndex(), pick.displayName());
      pendingSongSlot = pick.slotIndex();
    }

    clickSlotUnguarded(mc, menu, pendingSongSlot, 0, ContainerInput.PICKUP, player);
    songClickSent = true;
    actionCooldowns.armMenuClick(GuiActionTiming.randomDelayAtLeast(MENU_CLICK_COOLDOWN_TICKS * 3));
    status = "Selecting song slot " + pendingSongSlot;
  }

  /**
   * @description Walks to and stands on the harp tile before ever right-clicking. Priority is the
   *     primary tile; if another player is on it (or the current target), that tile is blacklisted
   *     for about 10 seconds and the macro moves to the nearest free standable tile near it. Only
   *     once actually standing there does it hand off to the aim and open states.
   * @param mc the current client
   * @param player the local player
   * @param level the loaded world
   */
  private void tickWalkToHarpStand(Minecraft mc, LocalPlayer player, ClientLevel level) {
    stateTicks++;
    releaseAllKeys(mc.options);

    if (isHarpScreenOpen(mc)) {
      setState(State.WAIT_FOR_HARP);
      return;
    }
    if (songSession.wasHarpBusy()) {
      setState(State.HARP_BUSY_WAIT);
      return;
    }
    if (mc.gui.screen() != null) {
      WalkingNavigation.stopNavQuiet();
      closeCurrentScreen(mc);
      return;
    }

    final int now = player.tickCount;
    final BlockPos harpSlab = worldTargets.refreshHarpSlab(player, level);
    // Commit to one target while a path is live so we don't flip-flop mid-walk; re-choose only when
    // idle.
    if (!routeAttempts.isPathRequested()) {
      currentStand = stand.choose(player, level, harpSlab, now);
    }
    final BlockPos target = currentStand;
    if (target == null) {
      WalkingNavigation.stopNavQuiet();
      status = "Waiting for a free harp spot";
      if (stateTicks > PATH_TIMEOUT_TICKS) {
        fail("No free harp spot");
      }
      return;
    }
    // Someone is on our target tile - blacklist it and pick another next tick.
    if (stand.isPlayerOnBlock(level, player, target)) {
      stand.blacklist(target, now);
      WalkingNavigation.stopNavQuiet();
      routeAttempts.enterState();
      currentStand = null;
      return;
    }
    // The server only accepts the harp click from the tile itself, so "in reach" is not arrival.
    if (isAtStand(player, target)) {
      WalkingNavigation.stopNavQuiet();
      setState(State.ROTATE_TO_HARP);
      return;
    }
    // Route ended a step short: within 1.5 blocks, walk the last bit by hand rather than
    // re-pathing.
    if (!WalkingNavigation.isNavigationActive() && isNearStand(player, target)) {
      if (stateTicks > PATH_TIMEOUT_TICKS) {
        fail("Path to harp spot timed out");
        return;
      }
      // Something is in the way of the last block (a fence, a step up, a body) - the nudge can
      // never
      // land, so drop this tile and pick another instead of standing here until the path timeout.
      if (++stepOntoTicks > STEP_ONTO_TIMEOUT_TICKS) {
        stand.blacklist(target, now);
        releaseAllKeys(mc.options);
        clearLook();
        currentStand = null;
        stepOntoTicks = 0;
        routeAttempts.enterState();
        return;
      }
      stepOntoStand(mc.options, player, target);
      status = "Stepping onto harp spot";
      return;
    }
    stepOntoTicks = 0;
    if (!routeAttempts.isPathRequested()) {
      if (!WalkingNavigation.startToQuiet(target.getX(), target.getY(), target.getZ())) {
        stand.blacklist(target, now); // unreachable - stop picking it
        currentStand = null;
        return;
      }
      routeAttempts.markPathRequested();
      status = "Walking to harp spot " + target.getX() + " " + target.getY() + " " + target.getZ();
      return;
    }
    if (!WalkingNavigation.isNavigationActive() && stateTicks > 20) {
      routeAttempts.enterState(); // path ended without arriving - re-choose + re-path next tick
    }
    if (stateTicks > PATH_TIMEOUT_TICKS) {
      fail("Path to harp spot timed out");
    }
  }

  /**
   * @description Turns toward the harp slab, falling back to the crosshair target when the slab
   *     cannot be found before the turn times out.
   * @param mc the current client
   * @param player the local player
   * @param level the loaded world
   */
  private void tickRotateToHarp(Minecraft mc, LocalPlayer player, ClientLevel level) {
    stateTicks++;
    releaseAllKeys(mc.options);

    if (isHarpScreenOpen(mc)) {
      setState(State.WAIT_FOR_HARP);
      return;
    }
    if (songSession.wasHarpBusy()) {
      setState(State.HARP_BUSY_WAIT);
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }

    worldTargets.refreshHarpSlab(player, level);
    if (!worldTargets.hasHarpSlab()) {
      status = "Harp block not found";
      if (stateTicks > ROTATE_TIMEOUT_TICKS) {
        // No slab detected - fall back to whatever the crosshair lands on.
        setState(State.OPEN_HARP);
      }
      return;
    }

    lookTarget = worldTargets.harpAimPoint();
    if (settle(aimedAt(player, lookTarget)) || stateTicks > ROTATE_TIMEOUT_TICKS) {
      setState(State.OPEN_HARP);
    } else {
      status = "Rotating to harp";
    }
  }

  /**
   * @description Holds right-click on the harp once the player is stable on the stand tile with the
   *     aim settled. A player on the harp, under the crosshair, or stacked on the bot blocks the
   *     click and eventually moves the macro to another tile.
   * @param mc the current client
   * @param player the local player
   * @param level the loaded world
   */
  private void tickOpenHarp(Minecraft mc, LocalPlayer player, ClientLevel level) {
    stateTicks++;

    if (isHarpScreenOpen(mc)) {
      releaseAllKeys(mc.options);
      setState(State.WAIT_FOR_HARP);
      return;
    }
    if (songSession.wasHarpBusy()) {
      releaseAllKeys(mc.options);
      setState(State.HARP_BUSY_WAIT);
      return;
    }
    // "Talk to Melody to select a song" - the selection never took, so go re-do it instead of
    // right-clicking a harp that will never open.
    if (songSession.wasSongMissing()) {
      releaseAllKeys(mc.options);
      status = "Song not selected, returning to Melody";
      setState(State.FIND_MELODY);
      return;
    }
    if (mc.gui.screen() != null) {
      // A stray menu (e.g. Melody reopened) - close it and keep trying.
      releaseAllKeys(mc.options);
      closeCurrentScreen(mc);
      return;
    }

    // A player stepped onto our stand tile: give it up, blacklist it 10s, and rotate to another
    // free spot.
    if (currentStand != null && stand.isPlayerOnBlock(level, player, currentStand)) {
      releaseUse(mc.options);
      stand.blacklist(currentStand, player.tickCount);
      currentStand = null;
      setState(State.WALK_TO_HARP_STAND);
      return;
    }

    releaseMovementKeys(mc.options);
    if (worldTargets.hasHarpSlab()) {
      lookTarget = worldTargets.harpAimPoint();
    }

    // Never right-click when a player is on the harp or under the crosshair - that opens their menu
    // instead of the harp. Wait briefly; if they keep blocking, blacklist this tile and re-approach
    // from a free one (a different angle usually clears the line of sight).
    final boolean playerBlocking =
        stand.isPlayerOnHarp(level, player, worldTargets.harpSlab())
            || MacroInput.isCrosshairOnRealPlayer(mc)
            || MacroObstruction.isPlayerStackedOn(level, player);
    if (playerBlocking) {
      setUseHeld(mc.options, false);
      stateTicks = 0; // don't count the wait against the open timeout
      if (++blockedTicks >= BLOCKED_RELOCATE_TICKS) {
        if (currentStand != null) {
          stand.blacklist(currentStand, player.tickCount);
        }
        currentStand = null;
        setState(State.WALK_TO_HARP_STAND);
        return;
      }
      status = "Player blocking harp, relocating soon";
      return;
    }
    blockedTicks = 0;

    // Only hold right-click once STABILISED: standing on the chosen tile, not moving, aim settled
    // on the
    // harp. Holding the use key then lets vanilla startUseItem() interact with the harp block above
    // the slab.
    final boolean stabilised =
        isAtStand(player, currentStand)
            && !WalkingNavigation.isNavigationActive()
            && lookTarget != null
            && aimedAt(player, lookTarget);
    final boolean rightClick = stabilised && worldTargets.isLookingAtHarp(mc);
    setUseHeld(mc.options, rightClick);
    status =
        rightClick
            ? "Right-clicking harp"
            : stabilised ? "Aiming at harp" : "Settling on harp spot";

    if (stateTicks > OPEN_HARP_TIMEOUT_TICKS) {
      releaseUse(mc.options);
      if (songSession.wasHarpBusy()) {
        setState(State.HARP_BUSY_WAIT);
      } else if (routeAttempts.exceededHarpOpens(MAX_HARP_OPEN_ATTEMPTS)) {
        fail("Harp did not open");
      } else {
        setState(State.WALK_TO_HARP_STAND);
      }
    }
  }

  /**
   * @description Waits while Auto Harp plays the song, then moves to scoring once the completion
   *     and score lines arrive, the harp closes early, or the song times out.
   * @param mc the current client
   */
  private void tickWaitForHarp(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    clearLook();

    if (songSession.wasHarpBusy()) {
      setState(State.HARP_BUSY_WAIT);
      return;
    }
    if (songSession.wasSongCompleted()) {
      status =
          songSession.wasScoreSeen()
              ? "Score " + songSession.lastScore() + "%, checking"
              : "Waiting for score";
      if (songSession.shouldEvaluateResult(SCORE_WAIT_TICKS)) {
        setState(State.SONG_RESULT);
      }
      return;
    }
    status = "Playing song";
    if (mc.gui.screen() == null && stateTicks > 60) {
      // Harp closed without a completion message - treat as an interrupted run and re-evaluate.
      setState(State.SONG_RESULT);
      return;
    }
    if (stateTicks > WAIT_HARP_TIMEOUT_TICKS) {
      setState(State.SONG_RESULT);
    }
  }

  /**
   * @description Waits out a busy harp for the saved retry delay before walking back to it.
   * @param mc the current client
   */
  private void tickHarpBusyWait(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    clearLook();
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
    }
    final int retryDelay = getRetryDelay();
    status = "Harp busy, retrying in " + Math.max(0, retryDelay - stateTicks) + " ticks";
    if (stateTicks >= retryDelay) {
      setState(State.WALK_TO_HARP_STAND);
    }
  }

  /**
   * @description Marks the song cleared when its score meets the threshold, otherwise retries it.
   *     Fails once the song has been retried too many times.
   * @param mc the current client
   */
  private void tickSongResult(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    clearLook();

    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      if (stateTicks <= CLOSE_TIMEOUT_TICKS) {
        return;
      }
    }

    final boolean success = songSession.isSuccessful(getMinScore());
    if (success) {
      songSession.markSelectedSongComplete(normalizeSearchText(songSession.displaySongName()));
      status = "Score " + songSession.lastScore() + "%, next song";
      resetSongChat();
      setState(State.NEXT_SONG);
      return;
    }

    if (songSession.recordRetry() > MAX_SONG_RETRIES) {
      fail("Song retry limit reached");
      return;
    }
    status =
        "Score "
            + (songSession.wasScoreSeen() ? songSession.lastScore() + "%" : "?")
            + ", retrying";
    resetSongChat();
    setState(State.WALK_TO_HARP_STAND);
  }

  /**
   * @description Clears the per-song state and returns to Melody for the next song.
   * @param mc the current client
   */
  private void tickNextSong(Minecraft mc) {
    stateTicks++;
    releaseAllKeys(mc.options);
    clearLook();
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    worldTargets.clearMelody();
    songSession.resetRetries();
    status = "Selecting next song";
    setState(State.FIND_MELODY);
  }

  /**
   * @description Stops the run with the failure status that led here.
   * @param mc the current client
   */
  private void tickFailed(Minecraft mc) {
    releaseAllKeys(mc.options);
    stopAll(status, false);
  }

  /**
   * @description Aims at an entity's look point and reports once the aim has settled.
   * @param player the local player
   * @param target the entity to face
   * @return true after the aim has been on target for two consecutive checks
   */
  private boolean aimAtEntity(LocalPlayer player, Entity target) {
    lookTarget = entityLookPoint(target);
    return settle(aimedAt(player, lookTarget));
  }

  /**
   * @description Checks whether the player's look is within the settle threshold of a point.
   * @param player the local player
   * @param target the world point to compare against
   * @return true when both yaw and pitch are within the threshold
   */
  private boolean aimedAt(LocalPlayer player, Vec3 target) {
    return isLookWithin(player, target, AIM_SETTLED_THRESHOLD, desiredAim);
  }

  /**
   * @description Checks whether the player stands on a tile at its centre, the stabilised condition
   *     for clicking the harp.
   * @param player the local player
   * @param stand the stand tile, or null when none is chosen
   * @return true within 0.6 blocks of the tile centre and one block of its height
   */
  private static boolean isAtStand(LocalPlayer player, BlockPos stand) {
    if (stand == null) {
      return false;
    }
    final double dx = (stand.getX() + 0.5) - player.getX();
    final double dz = (stand.getZ() + 0.5) - player.getZ();
    return player.getBlockX() == stand.getX()
        && player.getBlockZ() == stand.getZ()
        && Math.abs(player.getBlockY() - stand.getY()) <= 1
        && dx * dx + dz * dz <= 0.36; // within 0.6 blocks of the tile centre
  }

  /**
   * @description Checks whether the player is close enough that the last step is a nudge rather
   *     than a new route.
   * @param player the local player
   * @param stand the target stand tile
   * @return true within 1.5 blocks horizontally and vertically
   */
  private static boolean isNearStand(LocalPlayer player, BlockPos stand) {
    final double dx = (stand.getX() + 0.5) - player.getX();
    final double dz = (stand.getZ() + 0.5) - player.getZ();
    return Math.abs(player.getY() - stand.getY()) <= 1.5 && dx * dx + dz * dz <= 1.5 * 1.5;
  }

  /**
   * @description Faces the tile centre and holds forward until {@link #isAtStand} is true. {@link
   *     #shouldLockMouse} has to include WALK_TO_HARP_STAND for this to work: without it nothing
   *     ever turned the player, the aim gate stayed false, and the macro sat frozen one block off
   *     the spot until the 45 s path timeout.
   * @param options the client options holding the movement keys
   * @param player the local player
   * @param stand the tile to step onto
   */
  private void stepOntoStand(Options options, LocalPlayer player, BlockPos stand) {
    lookTarget = new Vec3(stand.getX() + 0.5, player.getEyeY(), stand.getZ() + 0.5);
    desiredYawPitch(player.getEyePosition(), lookTarget, desiredAim);
    // Yaw only - the pitch eases to level on its own and must not hold the walk up.
    final float yawError = Mth.wrapDegrees(desiredAim[0] - player.getYRot());
    MacroKeys.set(options.keyUp, Math.abs(yawError) <= STEP_YAW_THRESHOLD);
  }

  /**
   * @description Requires the aim to hold for two consecutive checks before it counts as settled.
   * @param aimedNow whether the aim is on target this check
   * @return true on the second consecutive on-target check
   */
  private boolean settle(boolean aimedNow) {
    if (!aimedNow) {
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
   * @description Drops the aim target and releases this macro's rotation state.
   */
  private void clearLook() {
    lookTarget = null;
    RyznBootstrap.rotationManager().resetAim(this);
  }

  /**
   * @description Resumes the matching state for one of the macro's own menus, or closes any other
   *     screen.
   * @param mc the current client
   */
  private void handleStrayScreen(Minecraft mc) {
    if (isMelodyMenu(mc)) {
      setState(State.SELECT_SONG);
    } else if (isHarpScreenOpen(mc)) {
      setState(State.WAIT_FOR_HARP);
    } else {
      closeCurrentScreen(mc);
    }
  }

  /**
   * @description Recognizes Melody's song menu by its title.
   * @param mc the current client
   * @return true for a container titled with Melody that is not the harp or reward menu
   */
  private static boolean isMelodyMenu(Minecraft mc) {
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?>)) {
      return false;
    }
    final String title = cleanTitle(mc).toLowerCase(Locale.ROOT);
    return title.contains("melody") && !title.contains("harp") && !title.contains("hair");
  }

  /**
   * @description Recognizes Melody's Hair reward menu, an interrupt the macro dismisses and resumes
   *     from.
   * @param mc the current client
   * @return true when a container titled with Hair is open
   */
  private static boolean isMelodysHairScreen(Minecraft mc) {
    return mc.gui.screen() instanceof AbstractContainerScreen<?> && titleContains(mc, "hair");
  }

  /**
   * @description Recognizes the Harp GUI by its title.
   * @param mc the current client
   * @return true when a container titled with Harp is open
   */
  private static boolean isHarpScreenOpen(Minecraft mc) {
    return mc.gui.screen() instanceof AbstractContainerScreen<?> && titleContains(mc, "harp");
  }

  /**
   * @description Recognizes either menu the macro opens itself.
   * @param mc the current client
   * @return true for Melody's song menu or the Harp GUI
   */
  private static boolean isMacroScreen(Minecraft mc) {
    return isMelodyMenu(mc) || isHarpScreenOpen(mc);
  }

  /**
   * @description Releases movement and attack but leaves the use key under {@link #tickOpenHarp}
   *     control, and stops any active route.
   * @param opts the client options, ignored when null
   */
  private static void releaseMovementKeys(Options opts) {
    if (opts == null) {
      return;
    }
    WalkingNavigation.stopNavQuiet();
    releaseMovementAndAttack(opts);
  }

  /**
   * @description Clears the chat observations for the current song attempt.
   */
  private void resetSongChat() {
    songSession.resetAttempt();
  }

  /**
   * @description Enters a workflow state, resetting per-state counters and running its entry
   *     action. Re-entering the current state does nothing.
   * @param next the state to enter
   */
  private void setState(State next) {
    if (next == state) {
      return;
    }
    state = next;
    status = next.label;
    stateTicks = 0;
    blockedTicks = 0;
    routeAttempts.enterState();
    actionCooldowns.reset();
    aimSettled = false;
    songClickSent = false;
    pendingSongSlot = -1;
    stepOntoTicks = 0;

    switch (next) {
      case PATHING_TO_MELODY -> {
        clearLook();
        routeAttempts.enterMelodyPath();
      }
      // A just-opened Hypixel menu is not click-ready; the old zero-delay click was silently
      // dropped.
      case SELECT_SONG -> actionCooldowns.armMenuClick(GuiActionTiming.firstMenuClickDelay());
      case FIND_MELODY -> {
        clearLook();
        songSession.clearSongMissing();
      }
      case ROTATE_TO_MELODY, WALK_TO_HARP_STAND -> clearLook();
      case ROTATE_TO_HARP -> {
        clearLook();
        worldTargets.clearHarpSlab();
      }
      case OPEN_HARP -> routeAttempts.enterHarpOpen();
      case HARP_BUSY_WAIT -> {
        clearLook();
        songSession.clearBusy();
      }
      case WAIT_FOR_HARP -> {
        clearLook();
        songSession.resetCompletionWait();
        routeAttempts.clearHarpOpens();
      }
      case NEXT_SONG -> worldTargets.clearMelody();
      default -> {}
    }
  }

  /**
   * @description Clears all transient run state and collaborators back to idle.
   */
  private void resetRuntime() {
    state = State.IDLE;
    status = "Starting Auto Harp completion";
    stateTicks = 0;
    blockedTicks = 0;
    stepOntoTicks = 0;
    routeAttempts.reset();
    actionCooldowns.reset();
    aimSettled = false;
    songClickSent = false;
    pendingSongSlot = -1;
    worldTargets.reset();
    songSession.reset();
    stand.clear();
    currentStand = null;
    clearLook();
  }

  /**
   * @description Turns Auto Harp back off when this run was the one that enabled it.
   */
  private void restoreAutoHarp() {
    if (!autoHarpWasEnabled
        && RyznBootstrap.macroManager().getByClass(AutoHarpMacro.class).isEnabled()) {
      RyznBootstrap.eventBus().post(new AutoHarpActionEvent(false));
    }
  }

  /**
   * @description Moves to the failed state while keeping the failure message as the status.
   * @param message the reason shown on the HUD and recorded as the stop reason
   */
  private void fail(String message) {
    status = message;
    setState(State.FAILED);
    status = message;
  }

  /**
   * @description Ends a run in which every song was cleared.
   */
  private void finish() {
    stopAll("All songs complete", true);
  }

  /**
   * @description Ends the run as an incomplete stop.
   * @param finalStatus the status recorded as the stop reason
   */
  private void stopAll(String finalStatus) {
    stopAll(finalStatus, false);
  }

  /**
   * @description Releases input, stops navigation, closes the macro's own menus, restores Auto
   *     Harp, resets runtime state, and saves the disabled run toggle.
   * @param finalStatus the status recorded as the stop reason
   * @param completed whether the run cleared every song
   */
  private void stopAll(String finalStatus, boolean completed) {
    final Minecraft mc = Minecraft.getInstance();
    releaseAllKeys(mc.options);
    WalkingNavigation.stopNavQuiet();
    if (mc.gui.screen() != null && isMacroScreen(mc)) {
      closeCurrentScreen(mc);
    }
    restoreAutoHarp();
    resetRuntime();
    enabled.set(false);
    lastRunCompleted = completed;
    lastStopReason = finalStatus;
    status = finalStatus;
    persist();
  }

  /**
   * @description Marks the settings file dirty so the owned values are saved.
   */
  private void persist() {
    RyznSettings.get().save();
  }
}
