package ryzn.macro.client.feature.macros.impl.wheat;

import static ryzn.macro.client.feature.commands.impl.MacroCommands.sendCommand;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.entityLookPoint;
import static ryzn.macro.client.util.gui.MacroScreens.closeCurrentScreen;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.Locale;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.settings.ConfigSection;
import ryzn.macro.client.config.settings.Setting;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.core.events.api.WheatBreakerActionEvent;
import ryzn.macro.client.debug.MacroStateTrace;
import ryzn.macro.client.feature.api.MenuToggle;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.feature.macros.api.Macro;
import ryzn.macro.client.feature.macros.impl.level7.SkillLevelDetector;
import ryzn.macro.client.feature.rotation.impl.RouteGaze;
import ryzn.macro.client.util.text.RyznChat;
import ryzn.macro.client.world.WorldChangeDetector;

/**
 * Loops the Hub wheat zones and breaks fully grown vanilla wheat, with optional hoe purchase, crop
 * selling, and a lobby hop once every zone is clear. Its saved options are owned here.
 */
public final class WheatBreakerMacro extends Macro implements ConfigSection, MenuToggle {

  /** Saved run toggle; startup, import, and export clear it through the run-toggle list. */
  private final Setting<Boolean> enabled =
      addSetting(Setting.bool("wheat_breaker_enabled", "Enabled", false));

  /** Whether the walk keeps moving through a zone instead of stopping per cluster. */
  private final Setting<Boolean> continuousMovement =
      addSetting(Setting.bool("wheat_continuous_movement", "Continuous sweep", true));

  /** Whether the route starts over after the last zone. */
  private final Setting<Boolean> loopZones =
      addSetting(Setting.bool("wheat_loop_zones", "Loop zones", true));

  /** Blocks searched around the player for grown wheat. */
  private final Setting<Integer> scanRadius =
      addSetting(Setting.integer("wheat_scan_radius", "Scan radius", 12, 4, 12));

  /** Clusters left in a zone before the route moves on. */
  private final Setting<Integer> clusterRemainingThreshold =
      addSetting(
          Setting.integer("wheat_cluster_remaining_threshold", "Cluster threshold", 8, 0, 50));

  /** Head sweep speed step. */
  private final Setting<Integer> sweepLookSpeed =
      addSetting(Setting.integer("wheat_sweep_look_speed", "Sweep look speed", 5, 1, 5));

  public static final int MIN_SWEEP_TRACKING_PERCENT = 75;
  public static final int MAX_SWEEP_TRACKING_PERCENT = 250;
  public static final int MAX_SWEEP_PITCH_OFFSET = 15;

  private final Setting<Integer> sweepTrackingPercent =
      addSetting(
          Setting.integer(
              "wheat_sweep_tracking_percent",
              "ABCurves tracking",
              150,
              MIN_SWEEP_TRACKING_PERCENT,
              MAX_SWEEP_TRACKING_PERCENT));

  private final Setting<Integer> sweepPitchOffset =
      addSetting(
          Setting.integer(
              "wheat_sweep_pitch_offset",
              "Sweep pitch offset",
              0,
              -MAX_SWEEP_PITCH_OFFSET,
              MAX_SWEEP_PITCH_OFFSET));

  public int getSweepTrackingPercent() {
    return sweepTrackingPercent.get();
  }

  public void setSweepTrackingPercent(int value) {
    sweepTrackingPercent.set(value);
  }

  public int getSweepPitchOffset() {
    return sweepPitchOffset.get();
  }

  public void setSweepPitchOffset(int value) {
    sweepPitchOffset.set(value);
  }

  /** Head turn acceleration, in percent. */
  private final Setting<Integer> lookAccelPercent =
      addSetting(Setting.integer("wheat_look_accel_percent", "Look acceleration", 45, 0, 200));

  /** Walking speed step while sweeping. */
  private final Setting<Integer> sweepMoveSpeed =
      addSetting(Setting.integer("wheat_sweep_move_speed", "Sweep move speed", 2, 1, 5));

  /**
   * Sweep arc in degrees. Values of 12 or less are the old step scale and are mapped onto the
   * degree range on load, as they always were.
   */
  private final Setting<Integer> sweepAmount =
      addSetting(
          Setting.integer(
              "wheat_sweep_amount",
              "Sweep amount",
              79,
              60,
              360,
              value -> value <= 12 ? 60 + value * 24 : value));

  /** Whether a hoe is bought when none is carried. */
  private final Setting<Boolean> autoBuyHoe =
      addSetting(Setting.bool("wheat_auto_buy_hoe", "Auto buy hoe", true));

  /** Whether a carried hoe is moved to the hand. */
  private final Setting<Boolean> autoEquipHoe =
      addSetting(Setting.bool("wheat_auto_equip_hoe", "Auto equip hoe", true));

  /** Whether harvesting waits until a hoe is held. */
  private final Setting<Boolean> requireHoe =
      addSetting(Setting.bool("wheat_require_hoe", "Require hoe", false));

  /** Whether crops are sold when the inventory fills. */
  private final Setting<Boolean> autoSell =
      addSetting(Setting.bool("wheat_auto_sell", "Auto sell crops", true));

  /** Whether the macro hops lobbies once every zone is clear. */
  private final Setting<Boolean> lobbyHopAfterClear =
      addSetting(Setting.bool("wheat_lobby_hop_after_clear", "Lobby hop after clear", true));

  public static final int ISLAND_WAIT_MIN_MS = 200;
  public static final int ISLAND_WAIT_MAX_MS = 2000;

  /** Milliseconds spent on the island during a lobby hop. */
  private final Setting<Integer> lobbyHopIslandWaitMs =
      addSetting(
          Setting.integer(
              "wheat_lobby_hop_island_wait_ms",
              "Island wait",
              2000,
              ISLAND_WAIT_MIN_MS,
              ISLAND_WAIT_MAX_MS));

  /**
   * @description Creates the process-lifetime wheat macro with its saved route and harvest options.
   */
  public WheatBreakerMacro() {
    super(
        "wheat_breaker",
        "Wheat Breaker",
        "Loops the Hub wheat zones and breaks fully grown vanilla wheat only.");
  }

  /**
   * @description Loads every saved option under its original key, clamping the numbers.
   * @param object the section JSON; a missing or mistyped value falls back to its default
   */
  @Override
  public void read(JsonObject object) {
    readSettings(object);
  }

  /**
   * @description Writes every saved option in its original key order.
   * @return a new section object for the settings file
   */
  @Override
  public JsonObject write() {
    return writeSettings();
  }

  private final WheatPathAttempts pathAttempts = new WheatPathAttempts();
  private final WheatInventory inventory = new WheatInventory();
  private final WheatMerchantActions merchantActions = new WheatMerchantActions(inventory);
  private final WheatHarvestController harvestController = new WheatHarvestController(inventory);
  private final WheatZoneRoute zoneRoute = new WheatZoneRoute();

  private static final double ZONE_RADIUS = 5.0;
  private static final double ARRIVAL_RADIUS = 3.0;
  private static final double PRE_ZONE_SWEEP_RADIUS = ZONE_RADIUS + 6.0;
  private static final double PRE_ZONE_Y_TOLERANCE = 0.25;
  private static final int FIELD_LEAVE_THRESHOLD = 4;

  /**
   * Watches the Farm Merchant while the route walks; see {@code RotationManager.externalOwnsLook}.
   */
  private final RouteGaze gaze = new RouteGaze();

  private static final int PATH_RETRY_COOLDOWN_TICKS = 40;
  private static final int MAX_PATH_RETRIES = 8;
  private static final int HUB_SETTLE_TICKS = 60;
  private static final int HUB_RESEND_TICKS = 40;
  private static final int HUB_HOP_GIVE_UP_TICKS = 20 * 180;

  private static final int HOE_MIN_FARMING_LEVEL = 1;
  private static final long HOE_MIN_COINS = 10L;

  /** Pause before re-checking when a whole pass found every zone already broken. */
  private static final int REGROW_WAIT_TICKS = 100;

  private final WheatZoneProbe zoneProbe = new WheatZoneProbe();
  private final SkillLevelDetector skills = new SkillLevelDetector();
  private final ScoreboardPurseTracker purse = new ScoreboardPurseTracker();
  private int zonesSkippedInPass;
  private int regrowWaitTicks;

  private WheatPhase phase = WheatPhase.IDLE;
  private WheatResumeAction resumeAction = WheatResumeAction.NONE;
  private int phaseTicks;
  private int hubHopSettleTicks;
  private boolean autoBuyDone;

  /**
   * A pre-zone sweep that gave up hands the walk back to the pathfinder - re-hijacking it just
   * loops.
   */
  private boolean preZoneSweepBlocked;

  private boolean inventoryFullSellRequested;
  private boolean level7PauseAfterSell;
  @Getter private boolean level7PausedAfterSell;
  private String status = "Disabled";

  private final MacroStateTrace trace = new MacroStateTrace("wheat");

  /**
   * @description Requests a sell when Hypixel reports a full inventory while auto sell is on.
   * @param gamemessageevent the received message; overlays are ignored
   */
  public void onGameMessageEvent(GameMessageEvent gamemessageevent) {
    final Component message = gamemessageevent.message();
    if (gamemessageevent.overlay() || !enabled.get() || message == null) {
      return;
    }
    skills.recordLevelUpMessage(message.getString());
    if (!autoSell.get()) {
      return;
    }
    final String text = normalizeSearchText(message.getString());
    if (text.contains("inventory full")
        && (text.contains("skyblock menu") || text.contains("storage"))) {
      inventoryFullSellRequested = true;
    }
  }

  /**
   * @description Applies a run, option, or Level 7 hand-off request from another feature, the menu,
   *     or the failsafe, in the order it is published.
   * @param wheatbreakeractionevent the requested action and its switch value
   */
  @Subscribe
  public void onWheatBreakerActionEvent(WheatBreakerActionEvent wheatbreakeractionevent) {
    final boolean value = wheatbreakeractionevent.value();
    switch (wheatbreakeractionevent.action()) {
      case SET_ENABLED -> setEnabled(value);
      case SET_AUTO_SELL -> setAutoSell(value);
      case SET_AUTO_BUY_HOE -> setAutoBuyHoe(value);
      case SET_AUTO_EQUIP_HOE -> setAutoEquipHoe(value);
      case SET_LOBBY_HOP_AFTER_CLEAR -> setLobbyHopAfterClear(value);
      case SET_LOOP_ZONES -> setLoopZones(value);
      case CLEAR_LEVEL7_PAUSE -> clearLevel7Pause();
      case SELL_THEN_PAUSE_FOR_LEVEL7 -> sellInventoryThenPauseForLevel7();
      case RESUME_LEVEL7_PAUSED_ROUTE -> resumeLevel7PausedRoute();
      default -> throw new IllegalArgumentException("Unknown Wheat Breaker action");
    }
  }

  /**
   * @description Resumes a persisted run after settings load, matching the previous startup and
   *     import hand-off. Run toggles are normally cleared first, so this is usually a no-op.
   */
  public void applyConfig() {
    if (enabled.get()) {
      beginSession();
    }
  }

  public boolean isEnabled() {
    return enabled.get();
  }

  public boolean isHarvesting() {
    return enabled.get() && phase == WheatPhase.HARVESTING;
  }

  public void setEnabled(boolean on) {
    if (enabled.get() == on) {
      persist();
      return;
    }
    RyznChat.announce("Wheat Breaker", on);
    if (on) {
      if (level7PausedAfterSell) {
        resumeLevel7PausedRoute();
      } else {
        enabled.set(true);
        beginSession();
      }
    } else {
      level7PauseAfterSell = false;
      level7PausedAfterSell = false;
      enabled.set(false);
      harvestController.stop(true);
      resetRuntime();
      status = "Disabled";
    }
    persist();
  }

  public void setLoopZones(boolean on) {
    loopZones.set(on);
    persist();
  }

  public int getSweepLookSpeed() {
    return sweepLookSpeed.get();
  }

  public void setSweepLookSpeed(int value) {
    sweepLookSpeed.set(Mth.clamp(value, 1, 5));
    persist();
  }

  public int getSweepAmount() {
    return sweepAmount.get();
  }

  public void setSweepAmount(int value) {
    sweepAmount.set(Mth.clamp(value, 60, 360));
    persist();
  }

  public void setAutoBuyHoe(boolean on) {
    autoBuyHoe.set(on);
    persist();
  }

  public void setAutoEquipHoe(boolean on) {
    autoEquipHoe.set(on);
    if (on) {
      final Minecraft mc = Minecraft.getInstance();
      inventory.tryEquipHoe(mc, mc.player);
    }
    persist();
  }

  public void setAutoSell(boolean on) {
    autoSell.set(on);
    persist();
  }

  public void setLobbyHopAfterClear(boolean on) {
    lobbyHopAfterClear.set(on);
    persist();
  }

  public int zoneCount() {
    return zoneRoute.zoneCount();
  }

  public int currentZoneNumber() {
    return zoneRoute.currentNumber();
  }

  public String statusText() {
    return enabled.get() ? status : "Disabled";
  }

  public String currentZoneText() {
    return "Zone " + currentZoneNumber() + " / " + zoneCount();
  }

  public int clustersRemaining() {
    return enabled.get() ? harvestController.clustersRemaining() : 0;
  }

  public float clusterProgress() {
    return enabled.get() ? harvestController.clusterProgress() : 0.0f;
  }

  public void sellInventoryThenPauseForLevel7() {
    if (!enabled.get()) {
      enabled.set(true);
    }
    level7PausedAfterSell = false;
    level7PauseAfterSell = true;
    inventoryFullSellRequested = false;
    if (isLobbyHopPhase() && phase != WheatPhase.LOBBY_HOP_RETURN_TO_ZONE) {
      // Mid hop the player may be on the island, where the merchant cannot be reached. Let the hop
      // land back in the Hub first; tickLobbyHopSettle starts the sell from there.
      status = "Level 7: selling after the lobby hop";
      persist();
      return;
    }
    startAutoSell(WheatResumeAction.CURRENT_ZONE);
    status = "Level 7: selling inventory";
    persist();
  }

  public void resumeLevel7PausedRoute() {
    if (!level7PausedAfterSell) {
      setEnabled(true);
      return;
    }
    level7PausedAfterSell = false;
    level7PauseAfterSell = false;
    enabled.set(true);
    pathAttempts.reset();
    merchantActions.resetReopens();
    enterPhase(WheatPhase.PATHING_TO_ZONE);
    status = "Moving to " + currentZoneText();
    persist();
  }

  public void clearLevel7Pause() {
    level7PauseAfterSell = false;
    level7PausedAfterSell = false;
    if (!enabled.get()) {
      resetRuntime();
      status = "Disabled";
      persist();
    }
  }

  public boolean shouldLockMouse() {
    return enabled.get()
        && (gaze.isOwned()
            || phase == WheatPhase.HARVESTING
            || phase == WheatPhase.AUTO_BUY_INTERACT
            || phase == WheatPhase.AUTO_SELL_INTERACT);
  }

  /**
   * True while the walk to the Farm Merchant is watching it. Read by {@code
   * RotationManager.externalOwnsLook}.
   *
   * @return true while the route is watching its target with the head
   */
  public boolean ownsRouteLook() {
    return enabled.get() && gaze.isOwned();
  }

  public void onClientTick(Minecraft mc) {
    trace.log(phase.name(), status);
    if (!enabled.get()) {
      if (harvestController.hasInputHeld()) {
        harvestController.releaseAttack(mc);
        harvestController.releaseWalkKeys(mc.options);
      }
      return;
    }

    final LocalPlayer player = mc.player;
    final ClientLevel level = mc.level;
    if (player == null || level == null) {
      harvestController.stop(true);
      // A lobby hop LOSES the world on purpose - that is what /hub does. Wiping the phase here
      // dropped
      // the hop mid-flight: the macro came back as PAUSED, was promoted straight to
      // PATHING_TO_ZONE,
      // and burned all eight path retries into a hub that had not finished loading (log 2026-08-27
      // 20:58:39). Only an unexpected world loss resets.
      if (!isLobbyHopPhase()) {
        resetRuntime();
        phase = WheatPhase.PAUSED;
      }
      status = "Waiting for world";
      return;
    }

    phaseTicks++;

    if (handleInventoryFullSellRequest(mc, player)) {
      return;
    }

    if (mc.gui.screen() != null && !phaseAllowsScreen()) {
      harvestController.stop(true);
      phase = WheatPhase.PAUSED;
      status = "Paused while a screen is open";
      return;
    }

    if (phase == WheatPhase.PAUSED || phase == WheatPhase.IDLE) {
      phase = WheatPhase.PATHING_TO_ZONE;
      status = phase.label();
    }

    if (PathfindCommand.isNavigationActive()) {
      harvestController.releaseAttack(mc);
      harvestController.releaseWalkKeys(mc.options);
      harvestController.clearAim();
      if (phase == WheatPhase.PATHING_TO_ZONE) {
        if (skipZoneIfBroken(mc)) {
          return;
        }
        if (isPlayerInZone(player)) {
          PathfindCommand.stopNavQuiet();
          beginHarvesting(level);
          return;
        }
        // The route owns the body and the head until the harvest walk can safely finish the trip:
        // feet on the ground (never mid-jump) and a straight, walkable line to the zone.
        if (player.onGround()
            && isPlayerAtZoneHeight(player)
            && shouldBeginPreZoneSweep(player, level)
            && harvestController.hasClearWalk(player, level, zoneRoute.currentCenter())) {
          PathfindCommand.stopNavQuiet();
          beginPreZoneHarvesting(level);
          return;
        }
      } else if (phase == WheatPhase.AUTO_BUY_PATHING || phase == WheatPhase.AUTO_SELL_PATHING) {
        if (WheatMerchantActions.isNearMerchant(player)) {
          PathfindCommand.stopNavQuiet();
          enterPhase(
              phase == WheatPhase.AUTO_BUY_PATHING
                  ? WheatPhase.AUTO_BUY_INTERACT
                  : WheatPhase.AUTO_SELL_INTERACT);
          return;
        }
        status = "Pathing to Farm Merchant";
        return;
      } else if (phase == WheatPhase.LOBBY_HOP_RETURN_TO_ZONE) {
        if (isPlayerInZone(player)) {
          PathfindCommand.stopNavQuiet();
          beginHarvesting(level);
          return;
        }
        status = "Lobby hop: returning to zone 1";
        return;
      } else {
        status = phase.label();
      }
      return;
    }

    switch (phase) {
      case PATHING_TO_ZONE -> tickPathingToZone(mc, player);
      case AUTO_BUY_PATHING ->
          tickMerchantPathing(player, WheatPhase.AUTO_BUY_INTERACT, "Buying Rookie Hoe");
      case AUTO_BUY_INTERACT ->
          handleMerchantInteract(
              mc, player, level, WheatPhase.AUTO_BUY_PURCHASE, "Opening Farm Merchant");
      case AUTO_BUY_PURCHASE -> handleAutoBuyPurchase(mc, player);
      case AUTO_SELL_PATHING ->
          tickMerchantPathing(player, WheatPhase.AUTO_SELL_INTERACT, "Selling wheat/seeds");
      case AUTO_SELL_INTERACT ->
          handleMerchantInteract(
              mc, player, level, WheatPhase.AUTO_SELLING, "Opening Farm Merchant");
      case AUTO_SELLING -> handleAutoSelling(mc, player);
      case LOBBY_HOP_SEND_IS -> tickLobbyHopSendIs(mc);
      case LOBBY_HOP_WAIT_IS -> tickLobbyHopWaitIs(mc);
      case LOBBY_HOP_SEND_HUB -> tickLobbyHopSendHub(mc);
      case LOBBY_HOP_SETTLE -> tickLobbyHopSettle(mc);
      case LOBBY_HOP_RETURN_TO_ZONE -> tickLobbyHopReturn(mc, player);
      case REPATHING -> beginHarvesting(level);
      case HARVESTING -> handleHarvesting(mc, player, level);
      default -> {
        phase = WheatPhase.PATHING_TO_ZONE;
        status = phase.label();
      }
    }
  }

  /** Functional aim on the game tick (fps-independent; works minimized at ~1 fps). */
  public void tickLook() {
    if (!enabled.get() || !shouldLockMouse() || PathfindCommand.isNavigationActive()) {
      return;
    }
    final LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) {
      return;
    }
    harvestController.applyLookFrameTick(
        player,
        sweepLookSpeed.get(),
        sweepTrackingPercent.get() / 100.0,
        phase == WheatPhase.HARVESTING);
  }

  public void onRenderFrame() {
    if (!enabled.get() || !shouldLockMouse() || PathfindCommand.isNavigationActive()) {
      return;
    }
    final LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) {
      return;
    }
    harvestController.applyLookFrameTick(
        player,
        sweepLookSpeed.get(),
        sweepTrackingPercent.get() / 100.0,
        phase == WheatPhase.HARVESTING);
  }

  private void beginSession() {
    resetRuntime();
    final LocalPlayer player = Minecraft.getInstance().player;
    autoBuyDone = inventory.hasHoe(player);
    if (autoEquipHoe.get()) {
      inventory.tryEquipHoe(Minecraft.getInstance(), player);
    }
    if (autoBuyHoe.get() && !autoBuyDone && readyToBuyHoe(Minecraft.getInstance())) {
      startAutoBuy(WheatResumeAction.CURRENT_ZONE);
      return;
    }
    enterPhase(WheatPhase.PATHING_TO_ZONE);
  }

  /** Reads Farming level and purse live, so the hoe is bought as soon as it is affordable. */
  private boolean readyToBuyHoe(Minecraft mc) {
    skills.poll(mc);
    purse.checkNow(mc);
    final PurseSnapshot coins = purse.lastPurse();
    return skills.level(SkillLevelDetector.FARMING) >= HOE_MIN_FARMING_LEVEL
        && coins.known()
        && coins.coins().compareTo(BigDecimal.valueOf(HOE_MIN_COINS)) >= 0;
  }

  /**
   * Skips the current zone the moment its chunks are loaded and it is already down to the "clusters
   * left" threshold, instead of finding out on arrival.
   */
  private boolean skipZoneIfBroken(Minecraft mc) {
    final int live = zoneProbe.poll(mc.level, zoneRoute.currentCenter(), scanRadius.get());
    if (live == WheatZoneProbe.UNKNOWN || live > clusterRemainingThreshold.get()) {
      return false;
    }
    if (PathfindCommand.isNavigationActive()) {
      PathfindCommand.stopNavQuiet();
    }
    zonesSkippedInPass++;
    trace.log("SKIP_ZONE", "zone " + zoneRoute.currentNumber() + " clusters=" + live);
    advanceZone(mc);
    return true;
  }

  private void resetRuntime() {
    phase = WheatPhase.IDLE;
    resumeAction = WheatResumeAction.NONE;
    zoneRoute.reset();
    phaseTicks = 0;
    pathAttempts.reset();
    merchantActions.reset();
    autoBuyDone = false;
    zoneProbe.reset();
    zonesSkippedInPass = 0;
    regrowWaitTicks = 0;
    preZoneSweepBlocked = false;
    inventoryFullSellRequested = false;
    harvestController.reset();
    clearZoneSweep();
  }

  private void clearZoneSweep() {
    harvestController.clearZone();
  }

  private void enterPhase(WheatPhase next) {
    phase = next;
    phaseTicks = 0;
    status = next.label();
    if (next == WheatPhase.AUTO_BUY_INTERACT || next == WheatPhase.AUTO_SELL_INTERACT) {
      harvestController.lookMotion().reset();
      merchantActions.enterInteraction();
    }
    if (next == WheatPhase.AUTO_BUY_PURCHASE || next == WheatPhase.AUTO_SELLING) {
      merchantActions.enterMenu();
    }
  }

  private boolean phaseAllowsScreen() {
    return phase == WheatPhase.AUTO_BUY_INTERACT
        || phase == WheatPhase.AUTO_BUY_PURCHASE
        || phase == WheatPhase.AUTO_SELL_INTERACT
        || phase == WheatPhase.AUTO_SELLING
        || isLobbyHopPhase();
  }

  /** The hop states, which expect the screens and the world loss a server transfer brings. */
  private boolean isLobbyHopPhase() {
    return phase == WheatPhase.LOBBY_HOP_SEND_IS
        || phase == WheatPhase.LOBBY_HOP_WAIT_IS
        || phase == WheatPhase.LOBBY_HOP_SEND_HUB
        || phase == WheatPhase.LOBBY_HOP_SETTLE
        || phase == WheatPhase.LOBBY_HOP_RETURN_TO_ZONE;
  }

  private boolean handleInventoryFullSellRequest(Minecraft mc, LocalPlayer player) {
    if (!inventoryFullSellRequested) {
      return false;
    }
    if (!autoSell.get()) {
      inventoryFullSellRequested = false;
      return false;
    }
    if (phase == WheatPhase.AUTO_SELL_PATHING
        || phase == WheatPhase.AUTO_SELL_INTERACT
        || phase == WheatPhase.AUTO_SELLING) {
      inventoryFullSellRequested = false;
      return false;
    }
    if (isLobbyHopPhase()) {
      inventoryFullSellRequested = false;
      return false;
    }
    if (phase == WheatPhase.AUTO_BUY_PATHING
        || phase == WheatPhase.AUTO_BUY_INTERACT
        || phase == WheatPhase.AUTO_BUY_PURCHASE) {
      return false;
    }
    if (!inventory.isFull(player)) {
      inventoryFullSellRequested = false;
      return false;
    }
    if (!inventory.hasSellableCrops(player)) {
      inventoryFullSellRequested = false;
      status = "Inventory full, no wheat/seeds";
      return false;
    }
    inventoryFullSellRequested = false;
    closeCurrentScreen(mc);
    startAutoSell(WheatResumeAction.CURRENT_ZONE);
    status = "Inventory full, selling wheat/seeds";
    return true;
  }

  private void tickPathingToZone(Minecraft mc, LocalPlayer player) {
    harvestController.releaseAttack(mc);
    harvestController.releaseWalkKeys(mc.options);
    harvestController.clearAim();
    if (regrowWaitTicks > 0) {
      regrowWaitTicks--;
      status = "Every zone is broken, waiting for regrowth";
      return;
    }
    if (skipZoneIfBroken(mc)) {
      return;
    }
    if (isPlayerInZone(player)) {
      beginHarvesting(mc.level);
      return;
    }
    if (pathAttempts.waitForRetry()) {
      status = "Waiting to retry path";
      return;
    }
    if (pathAttempts.exhausted(MAX_PATH_RETRIES)) {
      fail("Could not path to wheat zone");
      return;
    }
    requestPath(zoneRoute.currentCenter(), WheatPhase.PATHING_TO_ZONE);
  }

  private void beginHarvesting(ClientLevel level) {
    if (level == null) {
      return;
    }
    phase = WheatPhase.HARVESTING;
    pathAttempts.reset();
    preZoneSweepBlocked = false;
    harvestController.begin(level, zoneRoute.currentCenter(), harvestSettings());
    status = phase.label();
  }

  private void beginPreZoneHarvesting(ClientLevel level) {
    if (level == null) {
      return;
    }
    phase = WheatPhase.HARVESTING;
    pathAttempts.reset();
    harvestController.beginPreZone(level, zoneRoute.currentCenter(), harvestSettings());
    status = "Pre-zone sweep";
  }

  private void handleHarvesting(Minecraft mc, LocalPlayer player, ClientLevel level) {
    WheatHarvestController.Result result =
        harvestController.tick(
            mc,
            player,
            level,
            zoneRoute.currentCenter(),
            harvestSettings(),
            isPlayerInZone(player),
            isPlayerInLooseZone(player),
            isPlayerNearPreZone(player));
    if (result.action() == WheatHarvestController.Action.REPATH) {
      preZoneSweepBlocked = true;
      requestPath(zoneRoute.currentCenter(), WheatPhase.PATHING_TO_ZONE);
    } else if (result.action() == WheatHarvestController.Action.ADVANCE_ZONE) {
      advanceZone(mc);
    } else if (result.status() != null) {
      status = result.status();
    }
  }

  private WheatHarvestController.Settings harvestSettings() {
    // Farming bare-handed is what earns the level and coins for the hoe, so "require hoe" cannot
    // apply while the purchase is still pending - it would sit waiting for a tool it cannot afford.
    final boolean needsHoe = requireHoe.get() && !(autoBuyHoe.get() && !autoBuyDone);
    return new WheatHarvestController.Settings(
        continuousMovement.get(),
        autoEquipHoe.get(),
        needsHoe,
        scanRadius.get(),
        clusterRemainingThreshold.get(),
        sweepMoveSpeed.get(),
        sweepAmount.get(),
        sweepPitchOffset.get());
  }

  private void requestPath(BlockPos target, WheatPhase nextPhase) {
    final Minecraft mc = Minecraft.getInstance();
    harvestController.releaseAttack(mc);
    harvestController.releaseWalkKeys(mc.options);
    harvestController.clearAim();
    phase = nextPhase;
    phaseTicks = 0;
    status = nextPhase.label();
    pathAttempts.recordRequest(PATH_RETRY_COOLDOWN_TICKS);
    PathfindCommand.startToQuiet(target.getX(), target.getY(), target.getZ());
  }

  private void tickMerchantPathing(
      LocalPlayer player, WheatPhase interactPhase, String nextStatus) {
    final Minecraft mc = Minecraft.getInstance();
    harvestController.releaseAttack(mc);
    harvestController.releaseWalkKeys(mc.options);
    // Watch the merchant on the way in rather than staring down the path and snapping round on
    // arrival.
    final Entity watch = merchantActions.merchant(mc.level);
    if (gaze.shouldWatch(player, watch, harvestController.lookMotion()::reset)) {
      harvestController.lookMotion().setTarget(entityLookPoint(watch));
    } else {
      harvestController.lookMotion().clearTarget();
    }
    status = WheatMerchantActions.isNearMerchant(player) ? nextStatus : "Pathing to Farm Merchant";
    if (WheatMerchantActions.isNearMerchant(player)) {
      PathfindCommand.stopNavQuiet();
      enterPhase(interactPhase);
      status = nextStatus;
      return;
    }
    if (pathAttempts.waitForRetry()) {
      status = "Waiting to retry Farm Merchant path";
      return;
    }
    if (pathAttempts.exhausted(MAX_PATH_RETRIES)) {
      fail("Could not path to Farm Merchant");
      return;
    }
    status = "Moving closer to Farm Merchant";
    requestPath(WheatMerchantActions.POSITION, phase);
    status = "Moving closer to Farm Merchant";
  }

  private void handleMerchantInteract(
      Minecraft mc, LocalPlayer player, ClientLevel level, WheatPhase nextPhase, String label) {
    harvestController.releaseAttack(mc);
    harvestController.releaseWalkKeys(mc.options);
    // A sidestep walk owns the yaw while it runs - aiming at the merchant mid-walk would fight the
    // pathfinder's steering. The timeout still ends it, so a stuck relocate can't stall the phase.
    if (PathfindCommand.isNavigationActive()
        && !WheatMerchantActions.isInteractTimedOut(phaseTicks)) {
      harvestController.lookMotion().clearTarget();
      status = "Moving for a clear view of the Farm Merchant";
      return;
    }
    PathfindCommand.stopNavQuiet();
    status = label;
    switch (merchantActions.tickInteract(
        mc, player, level, harvestController.lookMotion(), phaseTicks)) {
      case OPENED -> enterPhase(nextPhase);
      case NEED_PATH -> {
        status = "Moving closer to Farm Merchant";
        requestPath(
            WheatMerchantActions.POSITION,
            phase == WheatPhase.AUTO_BUY_INTERACT
                ? WheatPhase.AUTO_BUY_PATHING
                : WheatPhase.AUTO_SELL_PATHING);
      }
      case SIDESTEP -> {
        final BlockPos side = merchantActions.sidestepTarget();
        status = "Player blocking the Farm Merchant, moving aside";
        PathfindCommand.startToQuiet(side.getX(), side.getY(), side.getZ());
      }
      case OPEN_TIMEOUT -> fail("Farm Merchant did not open");
      case WAITING -> {}

      default -> {}
    }
  }

  private void handleAutoBuyPurchase(Minecraft mc, LocalPlayer player) {
    harvestController.releaseAttack(mc);
    harvestController.releaseWalkKeys(mc.options);
    PathfindCommand.stopNavQuiet();
    status = "Buying Rookie Hoe";
    switch (merchantActions.tickPurchase(mc, player, phaseTicks)) {
      case RETRY_INTERACT -> enterPhase(WheatPhase.AUTO_BUY_INTERACT);
      case COMPLETE -> finishAutoBuy(mc, player);
      case HOE_NOT_FOUND -> fail("No hoe on sale at the Farm Merchant");
      case PURCHASE_NOT_CONFIRMED -> fail("Rookie Hoe purchase not confirmed");
      case WAITING -> {}

      default -> {}
    }
  }

  private void finishAutoBuy(Minecraft mc, LocalPlayer player) {
    autoBuyDone = true;
    closeCurrentScreen(mc);
    if (autoEquipHoe.get()) {
      inventory.tryEquipHoe(mc, player);
    }
    continueAfterMerchantAction(mc);
  }

  private void handleAutoSelling(Minecraft mc, LocalPlayer player) {
    harvestController.releaseAttack(mc);
    harvestController.releaseWalkKeys(mc.options);
    PathfindCommand.stopNavQuiet();
    status = "Selling wheat/seeds";
    switch (merchantActions.tickSelling(mc, player, phaseTicks)) {
      case REOPEN -> enterPhase(WheatPhase.AUTO_SELL_INTERACT);
      case COMPLETE -> {
        closeCurrentScreen(mc);
        status = "Sell complete";
        continueAfterMerchantAction(mc);
      }
      case GUI_CLOSED -> fail("Farm Merchant GUI closed while selling");
      case TIMED_OUT -> fail("Selling wheat/seeds timed out");
      case WAITING -> {}

      default -> {}
    }
  }

  private void tickLobbyHopSendIs(Minecraft mc) {
    harvestController.releaseAttack(mc);
    harvestController.releaseWalkKeys(mc.options);
    PathfindCommand.stopNavQuiet();
    closeCurrentScreen(mc);
    sendCommand(mc, "is");
    enterPhase(WheatPhase.LOBBY_HOP_WAIT_IS);
    status = "Lobby hop: /is";
  }

  private void tickLobbyHopWaitIs(Minecraft mc) {
    harvestController.releaseAttack(mc);
    harvestController.releaseWalkKeys(mc.options);
    PathfindCommand.stopNavQuiet();
    closeCurrentScreen(mc);
    status = "Lobby hop: waiting";
    if (phaseTicks * 50 >= lobbyHopIslandWaitMs.get()) {
      enterPhase(WheatPhase.LOBBY_HOP_SEND_HUB);
    }
  }

  private void tickLobbyHopSendHub(Minecraft mc) {
    harvestController.releaseAttack(mc);
    harvestController.releaseWalkKeys(mc.options);
    PathfindCommand.stopNavQuiet();
    closeCurrentScreen(mc);
    sendCommand(mc, "hub");
    hubHopSettleTicks = 0;
    enterPhase(WheatPhase.LOBBY_HOP_SETTLE);
    status = "Lobby hop: /hub";
  }

  private void tickLobbyHopSettle(Minecraft mc) {
    harvestController.releaseAttack(mc);
    harvestController.releaseWalkKeys(mc.options);
    PathfindCommand.stopNavQuiet();
    closeCurrentScreen(mc);
    status = "Lobby hop: /hub";
    if (mc.player == null || mc.level == null) {
      return;
    }
    // Hypixel can refuse the warp outright ("Couldn't warp you! ... (PLAYER_TRANSFER_COOLDOWN)",
    // ~60 s)
    // and only says so in chat, so the sole reliable success signal is the area flipping back to
    // the Hub.
    // Waiting a fixed few seconds and assuming it landed strands the macro on the island (log
    // 2026-08-20
    // 15:37:57). Each resend also re-arms the failsafe's world-change grace, which is shorter than
    // the
    // cooldown.
    if (!isInHub()) {
      hubHopSettleTicks = 0;
      if (phaseTicks >= HUB_HOP_GIVE_UP_TICKS) {
        fail("Could not warp back to the Hub");
        return;
      }
      if (phaseTicks > 0 && phaseTicks % HUB_RESEND_TICKS == 0) {
        sendCommand(mc, "hub");
      }
      return;
    }
    // The new ClientLevel exists a couple of seconds before Ryzn's block cache has switched to
    // it.
    if (++hubHopSettleTicks < HUB_SETTLE_TICKS) {
      return;
    }
    resetRouteForLobbyHop();
    if (level7PauseAfterSell) {
      startAutoSell(WheatResumeAction.CURRENT_ZONE);
      status = "Level 7: selling inventory";
      return;
    }
    requestPath(zoneRoute.firstCenter(), WheatPhase.LOBBY_HOP_RETURN_TO_ZONE);
    status = "Lobby hop: returning to zone 1";
  }

  private boolean isInHub() {
    final String area = WorldChangeDetector.currentArea();
    return area != null && area.toLowerCase(Locale.ROOT).contains("hub");
  }

  private void resetRouteForLobbyHop() {
    zoneRoute.reset();
    clearZoneSweep();
    harvestController.clearAim();
    pathAttempts.reset();
  }

  private void tickLobbyHopReturn(Minecraft mc, LocalPlayer player) {
    harvestController.releaseAttack(mc);
    harvestController.releaseWalkKeys(mc.options);
    if (isPlayerInZone(player)) {
      beginHarvesting(mc.level);
      return;
    }
    if (pathAttempts.waitForRetry()) {
      status = "Lobby hop: returning to zone 1";
      return;
    }
    if (pathAttempts.exhausted(MAX_PATH_RETRIES)) {
      fail("Could not return to Zone 1");
      return;
    }
    requestPath(zoneRoute.firstCenter(), WheatPhase.LOBBY_HOP_RETURN_TO_ZONE);
    status = "Lobby hop: returning to zone 1";
  }

  private void advanceZone(Minecraft mc) {
    harvestController.releaseAttack(mc);
    harvestController.releaseWalkKeys(mc.options);
    clearZoneSweep();
    harvestController.clearAim();
    zoneRoute.completeCurrent();

    final LocalPlayer player = mc.player;
    if (autoBuyHoe.get() && !autoBuyDone) {
      if (inventory.hasHoe(player)) {
        autoBuyDone = true;
      } else if (readyToBuyHoe(mc)) {
        startAutoBuy(
            zoneRoute.allCompleted() ? WheatResumeAction.FULL_CLEAR : WheatResumeAction.NEXT_ZONE);
        return;
      }
    }

    if (zoneRoute.allCompleted()) {
      if (autoSell.get() && inventory.isFull(player) && inventory.hasSellableCrops(player)) {
        startAutoSell(WheatResumeAction.FULL_CLEAR);
        return;
      }
      continueAfterFullClear(mc);
      return;
    }

    if (autoSell.get() && inventory.isFull(player) && inventory.hasSellableCrops(player)) {
      startAutoSell(WheatResumeAction.NEXT_ZONE);
      return;
    }
    advanceToNextZone();
  }

  private void advanceToNextZone() {
    zoneRoute.advance();
    zoneProbe.reset();
    phase = WheatPhase.PATHING_TO_ZONE;
    phaseTicks = 0;
    pathAttempts.reset();
    preZoneSweepBlocked = false;
    status = "Moving to " + currentZoneText();
  }

  private void continueAfterFullClear(Minecraft mc) {
    if (lobbyHopAfterClear.get() && !level7PauseAfterSell) {
      startLobbyHop(mc);
      return;
    }
    if (loopZones.get()) {
      if (zonesSkippedInPass >= zoneRoute.zoneCount()) {
        regrowWaitTicks = REGROW_WAIT_TICKS;
      }
      zonesSkippedInPass = 0;
      zoneRoute.reset();
      zoneProbe.reset();
      enterPhase(WheatPhase.PATHING_TO_ZONE);
      status = "Moving to " + currentZoneText();
      return;
    }
    enabled.set(false);
    phase = WheatPhase.IDLE;
    status = "Finished all wheat zones";
    persist();
  }

  private void continueAfterMerchantAction(Minecraft mc) {
    if (level7PauseAfterSell) {
      pauseAfterLevel7Sell(mc);
      return;
    }
    final WheatResumeAction action = resumeAction;
    resumeAction = WheatResumeAction.NONE;
    merchantActions.resetReopens();
    merchantActions.preparePurchase();
    // A sell is usually what lifts the purse past the hoe price, so check again right here.
    if (autoBuyHoe.get() && !autoBuyDone && !inventory.hasHoe(mc.player) && readyToBuyHoe(mc)) {
      startAutoBuy(action);
    } else if (action == WheatResumeAction.FULL_CLEAR) {
      continueAfterFullClear(mc);
    } else if (action == WheatResumeAction.NEXT_ZONE) {
      advanceToNextZone();
    } else {
      enterPhase(WheatPhase.PATHING_TO_ZONE);
      status = "Moving to " + currentZoneText();
    }
  }

  private void startAutoBuy(WheatResumeAction action) {
    harvestController.stop(true);
    resumeAction = action;
    merchantActions.preparePurchase();
    pathAttempts.reset();
    enterPhase(WheatPhase.AUTO_BUY_PATHING);
    requestPath(WheatMerchantActions.POSITION, WheatPhase.AUTO_BUY_PATHING);
    status = "Pathing to Farm Merchant";
  }

  private void startAutoSell(WheatResumeAction action) {
    harvestController.stop(true);
    resumeAction = action;
    merchantActions.resetReopens();
    pathAttempts.reset();
    enterPhase(WheatPhase.AUTO_SELL_PATHING);
    requestPath(WheatMerchantActions.POSITION, WheatPhase.AUTO_SELL_PATHING);
    status = "Pathing to Farm Merchant";
  }

  private void pauseAfterLevel7Sell(Minecraft mc) {
    harvestController.stop(true);
    closeCurrentScreen(mc);
    resumeAction = WheatResumeAction.NONE;
    merchantActions.resetReopens();
    merchantActions.preparePurchase();
    level7PauseAfterSell = false;
    level7PausedAfterSell = true;
    enabled.set(false);
    phase = WheatPhase.PAUSED;
    phaseTicks = 0;
    status = "Level 7: sell complete";
    persist();
  }

  private void startLobbyHop(Minecraft mc) {
    harvestController.stop(true);
    zoneRoute.reset();
    closeCurrentScreen(mc);
    enterPhase(WheatPhase.LOBBY_HOP_SEND_IS);
  }

  private boolean isPlayerInZone(LocalPlayer player) {
    return zoneRoute.isInside(player, ARRIVAL_RADIUS);
  }

  private boolean isPlayerInLooseZone(LocalPlayer player) {
    final double radius = ZONE_RADIUS + FIELD_LEAVE_THRESHOLD + 2.0;
    return zoneRoute.isInside(player, radius);
  }

  private boolean isPlayerNearPreZone(LocalPlayer player) {
    return zoneRoute.isInside(player, PRE_ZONE_SWEEP_RADIUS);
  }

  private boolean isPlayerAtZoneHeight(LocalPlayer player) {
    return zoneRoute.isAtY(player, PRE_ZONE_Y_TOLERANCE);
  }

  private boolean shouldBeginPreZoneSweep(LocalPlayer player, ClientLevel level) {
    if (!isPlayerNearPreZone(player)) {
      preZoneSweepBlocked = false;
      return false;
    }
    return !preZoneSweepBlocked
        && harvestController.shouldBeginPreZoneSweep(
            player, level, zoneRoute.currentCenter(), harvestSettings(), true);
  }

  private void fail(String message) {
    harvestController.stop(true);
    level7PauseAfterSell = false;
    level7PausedAfterSell = false;
    enabled.set(false);
    phase = WheatPhase.IDLE;
    status = message;
    persist();
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
            new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.SET_ENABLED, !isEnabled()));
  }
}
