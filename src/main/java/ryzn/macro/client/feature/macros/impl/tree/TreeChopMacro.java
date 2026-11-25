package ryzn.macro.client.feature.macros.impl.tree;

import static ryzn.macro.client.MacroInput.jitter;
import static ryzn.macro.client.MacroInput.releaseAllKeys;
import static ryzn.macro.client.MacroInput.setAttackHeld;
import static ryzn.macro.client.util.text.MacroText.stripFormatting;

import com.google.gson.JsonObject;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import ryzn.macro.client.config.settings.ConfigSection;
import ryzn.macro.client.config.settings.Setting;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.core.events.api.TreeChopActionEvent;
import ryzn.macro.client.feature.api.MenuToggle;
import ryzn.macro.client.feature.macros.api.Macro;
import ryzn.macro.client.feature.pathfinder.PathRenderer;
import ryzn.macro.client.feature.pathfinder.path.PathExecutor;
import ryzn.macro.client.input.MacroKeys;
import ryzn.macro.client.util.text.RyznChat;
import ryzn.pathfinding.PathManager;

/**
 * Tree-chop macro: walks the fixed oak-farm perimeter via the native pathfinder + {@link
 * PathExecutor}. It picks one dense oak cluster, paths to a stand spot, stops movement, breaks one
 * reachable log from that cluster, marks that cluster handled for the current pass, then moves to
 * the next cluster.
 *
 * <p>Client-side and packet-neutral: movement is the executor's keybind simulation, mining is the
 * held attack key, and "is it broken yet?" is read from the client's own block state. While the
 * macro is steering the head at a log it sets {@link #isControllingLook()} so mouse and pathfinder
 * look control yield cleanly; when no log is targeted it releases look control back to the
 * pathfinder.
 */
public final class TreeChopMacro extends Macro implements ConfigSection, MenuToggle {

  enum State {
    FIND_CLUSTER,
    PATH_TO_CLUSTER,
    CONTACT_TREE,
    ARRIVE_AT_CLUSTER,
    BREAK_ONE_LOG,
    MARK_CLUSTER_DONE,
    MOVE_TO_NEXT,
    SCAN_COVERAGE_AREA
  }

  static final int SCAN_COOLDOWN_TICKS = 10;

  // Handoff between trees. The recording shows ~0.6-1.0s from the last log breaking to the feet
  // moving,
  // and that second is not idle - the camera is coming down off the canopy and swinging onto the
  // next
  // trunk, with the walk starting partway through the turn. So the handoff is a deliberate,
  // jittered
  // pause spent turning, NOT the old freeze (a 160k-block rescan plus a flat cooldown, camera
  // parked).
  // Starting to walk on the same tick the log pops would lurch the body ahead of the camera, which
  // reads
  // worse than the freeze it replaces.
  final TreeChopSelection treeSelection = new TreeChopSelection(this);
  final TreeChopApproach approachActions = new TreeChopApproach(this);
  final TreeChopBreaking chopActions = new TreeChopBreaking(this);
  final TreeChopProgress progressWatchdog = new TreeChopProgress(this);

  /**
   * How long a tree that reported "regenerating" is left alone. Re-arms if it is still regrowing.
   */
  static final long REGEN_SKIP_MS = 45_000L;

  // One short step per tree, taken ONLY once nothing is reachable from where we stand. Any looser
  // and it
  // becomes the shuffling that planting the feet just removed.

  // long enough for a full smooth rotation

  // low logs must stay gone this long before looking up
  // arrived but no reachable log this long -> unreachable
  // walk-onto-trunk retries before repathing around a tree
  // Backstop for a stand we cannot close on. Eight seconds of it was the whole freeze: the branch
  // below did
  // not actually walk, so the bot stood motionless for the full count before repathing. Three is a
  // beat.

  // Frame-time based look motion: speeds are degrees per second derived from the "Rotation speed"
  // setting
  // (1..5), acceleration-limited so turns ramp in and out, and capped per frame so a large target
  // change
  // can never flick. The desired angle itself is low-passed so target switches glide instead of
  // stepping.

  @Getter private volatile boolean running = false;
  boolean collectionMode = false;
  State state = State.FIND_CLUSTER;
  private final TreeChopAvoidance avoidance = new TreeChopAvoidance();
  private final TreeTrunkColumns trunkColumns = new TreeTrunkColumns();
  final TreeChopPhase chopPhase = new TreeChopPhase();
  final TreeChopContactProgress contactProgress = new TreeChopContactProgress();
  final TreeChopExclusions exclusions = new TreeChopExclusions();
  final TreeStandFinder standFinder = new TreeStandFinder(avoidance, exclusions);
  final TreeLogTargeting targeting =
      new TreeLogTargeting(avoidance, exclusions, chopPhase, this::chopLogType);
  final TreeChopMotion motion = new TreeChopMotion(chopPhase);
  final TreeClusterScanner clusterScanner =
      new TreeClusterScanner(avoidance, trunkColumns, targeting);
  final TreeIndex index = new TreeIndex(trunkColumns, avoidance);
  private final TreeToolSelector toolSelector = new TreeToolSelector();
  final TreeNavigationController navigation = new TreeNavigationController();
  private final TreeChopUi ui = new TreeChopUi();
  final TreeChopGaze gaze = new TreeChopGaze();

  int scanCooldown = 0;
  int handoffTicks = 0;
  BlockPos preparedNextTree;
  BlockPos nudgeTarget = null;
  int nudgeTicks = 0;
  int nudgesUsedThisTree = 0;
  boolean stoppedEarlyThisTree = false;
  int chopped = 0;

  // Per-tree progress watchdog; see checkTreeProgress.
  BlockPos watchedTree;
  int watchedChopped;
  int watchedLogsLeft;
  int ticksOnTreeWithoutLog;
  int ticksAtTreeWithoutLog;
  String activity = "idle";

  /** Consecutive ticks another body has been overlapping ours while trying to chop. */
  int stackedTicks;

  final TreeClusterTarget clusterTarget = new TreeClusterTarget();

  final TreeChopRoute route = new TreeChopRoute();

  final TreeChopTargetState targetState = new TreeChopTargetState();

  /** Whether both perimeter corners are set. */
  private final Setting<Boolean> chopHasPerimeter =
      addSetting(Setting.bool("chop_has_perimeter", "Has perimeter", false));

  /** Lower perimeter X. */
  private final Setting<Integer> chopMinX =
      addSetting(Setting.integer("chop_min_x", "Min X", 0, Integer.MIN_VALUE, Integer.MAX_VALUE));

  /** Upper perimeter X. */
  private final Setting<Integer> chopMaxX =
      addSetting(Setting.integer("chop_max_x", "Max X", 0, Integer.MIN_VALUE, Integer.MAX_VALUE));

  /** Lower perimeter Z. */
  private final Setting<Integer> chopMinZ =
      addSetting(Setting.integer("chop_min_z", "Min Z", 0, Integer.MIN_VALUE, Integer.MAX_VALUE));

  /** Upper perimeter Z. */
  private final Setting<Integer> chopMaxZ =
      addSetting(Setting.integer("chop_max_z", "Max Z", 0, Integer.MIN_VALUE, Integer.MAX_VALUE));

  /** Whether a start position is set. */
  private final Setting<Boolean> chopHasStart =
      addSetting(Setting.bool("chop_has_start", "Has start", false));

  /** Start position X. */
  private final Setting<Integer> chopStartX =
      addSetting(
          Setting.integer("chop_start_x", "Start X", 0, Integer.MIN_VALUE, Integer.MAX_VALUE));

  /** Start position Y. */
  private final Setting<Integer> chopStartY =
      addSetting(
          Setting.integer("chop_start_y", "Start Y", 0, Integer.MIN_VALUE, Integer.MAX_VALUE));

  /** Start position Z. */
  private final Setting<Integer> chopStartZ =
      addSetting(
          Setting.integer("chop_start_z", "Start Z", 0, Integer.MIN_VALUE, Integer.MAX_VALUE));

  /** Fewest logs a cluster needs to be worth walking to. */
  private final Setting<Integer> chopMinDensity =
      addSetting(Setting.integer("chop_min_density", "Min density", 12, 1, 4096));

  /** Vertical scan reach, in blocks. */
  private final Setting<Integer> chopScanVerticalRadius =
      addSetting(Setting.integer("chop_scan_y_radius", "Scan height", 40, 4, 192));

  /** Name fragment of the axe to hold; blank restores "treecap". */
  private final Setting<String> chopToolKeyword =
      addSetting(Setting.rawText("chop_tool_keyword", "Tool keyword", "treecap"));

  /** Search field of view, in degrees. */
  private final Setting<Integer> chopFovDegrees =
      addSetting(Setting.integer("chop_fov_degrees", "Search FOV", 180, 0, 180));

  /** Field of view for picking the next log to break, in degrees. */
  private final Setting<Integer> chopBreakFovDegrees =
      addSetting(Setting.integer("chop_break_fov_degrees", "Break FOV", 45, 0, 180));

  /** Head turn acceleration, in percent. */
  private final Setting<Integer> chopLookAccelPercent =
      addSetting(Setting.integer("chop_look_accel_percent", "Look acceleration", 20, 0, 200));

  /** Whether qualifying clusters are highlighted. */
  private final Setting<Boolean> chopHighlight =
      addSetting(Setting.bool("chop_highlight", "Highlight", true));

  /** One log per tree with a Treecapitator when on, regular chopping when off. */
  private final Setting<Boolean> chopTreecapMode =
      addSetting(Setting.bool("chop_treecap_mode", "Treecap mode", false));

  /** Persisted id of the log type to chop. */
  private final Setting<String> chopLogTypeId =
      addSetting(Setting.rawText("chop_log_type", "Log type", "oak"));

  /** Head turn speed step. */
  private final Setting<Integer> chopRotationSpeed =
      addSetting(Setting.integer("chop_rotation_speed", "Rotation speed", 3, 1, 10));

  /** Chance of a humanlike overshoot, in percent. */
  private final Setting<Integer> chopOvershootChance =
      addSetting(Setting.integer("chop_overshoot_chance", "Overshoot chance", 0, 0, 100));

  /** Size of an overshoot, in degrees. */
  private final Setting<Integer> chopOvershootDegrees =
      addSetting(Setting.integer("chop_overshoot_degrees", "Overshoot", 2, 0, 10));

  /** Ticks spent correcting after an overshoot. */
  private final Setting<Integer> chopCorrectionTicks =
      addSetting(Setting.integer("chop_correction_ticks", "Correction ticks", 4, 1, 20));

  /** Ticks between breaking a log and turning to the next. */
  private final Setting<Integer> chopHandoffTicks =
      addSetting(Setting.integer("chop_handoff_ticks", "Handoff ticks", 12, 0, 40));

  /** Ticks allowed to break one log before giving up on it. */
  final Setting<Integer> chopBreakTimeoutTicks =
      addSetting(Setting.integer("chop_break_timeout_ticks", "Break timeout", 120, 20, 400));

  /**
   * @description Creates the process-lifetime tree chopper with its saved area, motion, and tool
   *     options.
   */
  public TreeChopMacro() {
    super(
        "tree_chop",
        "Tree Chopper",
        "Pathfinds to dense log clusters and chops them: whole trees in Treecap mode, or low logs"
            + " with any axe in Multi-log mode.");
  }

  /**
   * @description Returns the saved value: whether both perimeter corners are set.
   * @return the current value
   */
  public boolean chopHasPerimeter() {
    return chopHasPerimeter.get();
  }

  /**
   * @description Returns the saved value: lower perimeter X.
   * @return the current value
   */
  public int chopMinX() {
    return chopMinX.get();
  }

  /**
   * @description Returns the saved value: upper perimeter X.
   * @return the current value
   */
  public int chopMaxX() {
    return chopMaxX.get();
  }

  /**
   * @description Returns the saved value: lower perimeter Z.
   * @return the current value
   */
  public int chopMinZ() {
    return chopMinZ.get();
  }

  /**
   * @description Returns the saved value: upper perimeter Z.
   * @return the current value
   */
  public int chopMaxZ() {
    return chopMaxZ.get();
  }

  /**
   * @description Returns the saved value: whether a start position is set.
   * @return the current value
   */
  public boolean chopHasStart() {
    return chopHasStart.get();
  }

  /**
   * @description Returns the saved value: start position X.
   * @return the current value
   */
  public int chopStartX() {
    return chopStartX.get();
  }

  /**
   * @description Returns the saved value: start position Y.
   * @return the current value
   */
  public int chopStartY() {
    return chopStartY.get();
  }

  /**
   * @description Returns the saved value: start position Z.
   * @return the current value
   */
  public int chopStartZ() {
    return chopStartZ.get();
  }

  /**
   * @description Returns the saved value: fewest logs a cluster needs to be worth walking to.
   * @return the current value
   */
  public int chopMinDensity() {
    return chopMinDensity.get();
  }

  /**
   * @description Returns the saved value: vertical scan reach, in blocks.
   * @return the current value
   */
  public int chopScanVerticalRadius() {
    return chopScanVerticalRadius.get();
  }

  /**
   * @description Returns the saved value: search field of view, in degrees.
   * @return the current value
   */
  public int chopFovDegrees() {
    return chopFovDegrees.get();
  }

  /**
   * @description Returns the saved value: field of view for picking the next log to break, in
   *     degrees.
   * @return the current value
   */
  public int chopBreakFovDegrees() {
    return chopBreakFovDegrees.get();
  }

  /**
   * @description Returns the saved value: head turn acceleration, in percent.
   * @return the current value
   */
  public int chopLookAccelPercent() {
    return chopLookAccelPercent.get();
  }

  /**
   * @description Returns the saved value: whether qualifying clusters are highlighted.
   * @return the current value
   */
  public boolean chopHighlight() {
    return chopHighlight.get();
  }

  /**
   * @description Returns the saved value: whole trees with a Treecapitator when on, single low logs
   *     when off.
   * @return the current value
   */
  public boolean chopTreecapMode() {
    return chopTreecapMode.get();
  }

  /**
   * @description Returns the saved value: persisted id of the log type to chop.
   * @return the current value
   */
  public String chopLogTypeId() {
    return chopLogTypeId.get();
  }

  /**
   * @description Returns the saved value: head turn speed step.
   * @return the current value
   */
  public int chopRotationSpeed() {
    return chopRotationSpeed.get();
  }

  /**
   * @description Returns the saved value: chance of a humanlike overshoot, in percent.
   * @return the current value
   */
  public int chopOvershootChance() {
    return chopOvershootChance.get();
  }

  /**
   * @description Returns the saved value: size of an overshoot, in degrees.
   * @return the current value
   */
  public int chopOvershootDegrees() {
    return chopOvershootDegrees.get();
  }

  /**
   * @description Returns the saved value: ticks spent correcting after an overshoot.
   * @return the current value
   */
  public int chopCorrectionTicks() {
    return chopCorrectionTicks.get();
  }

  /**
   * @description Returns the saved value: ticks between breaking a log and turning to the next.
   * @return the current value
   */
  public int chopHandoffTicks() {
    return chopHandoffTicks.get();
  }

  /**
   * @description Returns the saved value: ticks allowed to break one log before giving up on it.
   * @return the current value
   */
  public int chopBreakTimeoutTicks() {
    return chopBreakTimeoutTicks.get();
  }

  /**
   * @description Returns the axe name fragment to hold.
   * @return the saved keyword, or "treecap" when blank
   */
  public String chopToolKeyword() {
    final String keyword = chopToolKeyword.get();
    return keyword.isBlank() ? "treecap" : keyword;
  }

  /**
   * @description Sorts the perimeter so the minimum corner is below the maximum on both axes.
   */
  private void orderPerimeter() {
    final int lowX = Math.min(chopMinX.get(), chopMaxX.get());
    final int highX = Math.max(chopMinX.get(), chopMaxX.get());
    chopMinX.set(lowX);
    chopMaxX.set(highX);
    final int lowZ = Math.min(chopMinZ.get(), chopMaxZ.get());
    final int highZ = Math.max(chopMinZ.get(), chopMaxZ.get());
    chopMinZ.set(lowZ);
    chopMaxZ.set(highZ);
  }

  /**
   * @description Converts the saved look acceleration to a factor.
   * @return the acceleration as a fraction, 0 to 2
   */
  public float chopLookAccel() {
    return chopLookAccelPercent.get() / 100.0f;
  }

  /**
   * @description Returns the log type to chop.
   * @return the saved type, or the default for an unknown id
   */
  public TreeLogType chopLogType() {
    return TreeLogType.fromPersistedId(chopLogTypeId.get());
  }

  /**
   * @description Changes the log type to chop; the caller saves the settings file.
   * @param type the new type, where null restores oak
   */
  public void setChopLogType(TreeLogType type) {
    chopLogTypeId.set(type == null ? "oak" : type.persistedId());
  }

  /**
   * @description Maps the rotation speed step to the settle deadzone.
   * @return the deadzone in degrees for the saved speed
   */
  public float chopLookDeadzone() {
    return switch (chopRotationSpeed()) {
      case 1 -> 0.55f;
      case 2 -> 0.45f;
      case 3 -> 0.40f;
      case 4 -> 0.35f;
      default -> 0.30f;
    };
  }

  /**
   * @description Loads every saved option under its original key, clamping the numbers and ordering
   *     a set perimeter.
   * @param object the section JSON; a missing or mistyped value falls back to its default
   */
  @Override
  public void read(JsonObject object) {
    readSettings(object);
    normalizeSettings();
  }

  /**
   * @description Writes every saved option in its original key order, normalizing first exactly as
   *     a load would.
   * @return a new section object for the settings file
   */
  @Override
  public JsonObject write() {
    normalizeSettings();
    return writeSettings();
  }

  /**
   * @description Restores a blank tool keyword and orders a set perimeter.
   */
  private void normalizeSettings() {
    if (chopToolKeyword.get().isBlank()) {
      chopToolKeyword.set("treecap");
    }
    if (chopHasPerimeter.get()) {
      orderPerimeter();
    }
  }

  /**
   * @description Feeds non-overlay chat to the chopper.
   * @param gamemessageevent the received message; overlays are ignored
   */
  public void onGameMessageEvent(GameMessageEvent gamemessageevent) {
    if (gamemessageevent.overlay() || gamemessageevent.message() == null) {
      return;
    }
    onChatMessage(gamemessageevent.message());
  }

  /**
   * @description Applies a start, stop, or log-type request from the command, menu, failsafe, or
   *     another macro.
   * @param treechopactionevent the requested action and, for a log-type change, the new type
   */
  @Subscribe
  public void onTreeChopActionEvent(TreeChopActionEvent treechopactionevent) {
    switch (treechopactionevent.action()) {
      case START -> start();
      case START_FOR_COLLECTION -> startForCollection();
      case STOP -> stop();
      case SET_LOG_TYPE -> setChopLogType(treechopactionevent.logType());
      case LOG_TYPE_CHANGED -> onLogTypeChanged();
      case RESET_LOST_TREES -> resetLostTrees();
      default -> throw new IllegalArgumentException("Unknown Tree Chopper action");
    }
  }

  /**
   * True while the macro is steering the camera at a log (mouse-look + executor look both yield).
   *
   * @return true while the macro aims at a log
   */
  public boolean isControllingLook() {
    return motion.isControllingLook();
  }

  /**
   * True while the macro is watching the tree it is walking to. Read by {@code
   * RotationManager.externalOwnsLook}, which makes the path executor yield ONLY the head and keep
   * driving the body along the route.
   *
   * @return true while the macro watches the tree on its route
   */
  public boolean ownsRouteLook() {
    return running && state == State.PATH_TO_CLUSTER && motion.ownsRouteLook();
  }

  public synchronized void start() {
    start(false);
  }

  private synchronized void start(boolean collectionMode) {
    final Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) {
      say(mc, "§bTree Chop§7: §cjoin a world first.");
      return;
    }
    running = true;
    this.collectionMode = collectionMode;
    chopped = 0;
    navigation.reset();
    scanCooldown = 0;
    handoffTicks = 0;
    preparedNextTree = null;
    // One blocking sweep here only. The player is stationary at start anyway, and an empty index
    // would
    // otherwise mean a couple of seconds with no target at all; every later refresh is incremental.
    index.seed(mc.level, chopLogType(), clusterScanner.boundsFor(mc.player, mc.level));
    toolSelector.resetWarning();
    activity = "searching for " + chopLogType().statusName() + " logs";
    contactProgress.reset();
    exclusions.reset();
    clusterTarget.clear();
    targeting.clearCluster();
    chopPhase.reset();
    route.reset();
    targetState.clearCommit();

    state = State.FIND_CLUSTER;
    announceStart(mc);
  }

  public synchronized void startForCollection() {
    start(true);
  }

  public synchronized void stop() {
    running = false;
    collectionMode = false;
    handoffTicks = 0;
    preparedNextTree = null;
    nudgeTarget = null;
    nudgeTicks = 0;
    nudgesUsedThisTree = 0;
    stoppedEarlyThisTree = false;
    gaze.release();
    index.clear();
    motion.release(Minecraft.getInstance().options);
    clusterTarget.clear();
    targeting.clearCluster();
    exclusions.clearAvoidLogs();
    exclusions.clearTargetBlocks();
    exclusions.clearTemporarySkips();
    contactProgress.resetContact();
    activity = "idle";
    chopPhase.reset();
    route.reset();
    targetState.clearCommit();
    final Minecraft mc = Minecraft.getInstance();
    PathExecutor.instance.stop();
    PathManager.cancelSearch();
    TreeChopRenderer.clear();
    releaseAllKeys(mc.options);
    say(
        mc,
        "§bTree Chop§7: §fstopped §7- "
            + chopped
            + " "
            + choppedUnit()
            + (chopped == 1 ? "" : "s")
            + " cut this run.");
  }

  public synchronized void onLogTypeChanged() {
    targetState.clearCommit();
    clusterTarget.clear();
    targeting.clearCluster();
    exclusions.reset();
    contactProgress.resetContact();
    chopPhase.reset();
    route.reset();
    activity = "searching for " + chopLogType().statusName() + " logs";
    if (running) {
      abortNav();
      releaseChop(Minecraft.getInstance().options);
      scanCooldown = 0;
      state = State.FIND_CLUSTER;
    }
    TreeChopRenderer.clear();
  }

  private void announceStart(Minecraft mc) {
    final TreeLogType type = chopLogType();
    say(
        mc,
        "§bTree Chop§7: §astarted §7- working through the "
            + type.statusName()
            + " trunks, nearest first. Press §fEscape§7 to stop.");
  }

  public String status() {
    return ui.status(
        new TreeChopUi.Status(
            running, state.name(), route.handledCount(), chopped, choppedUnit(), activity));
  }

  public int choppedCount() {
    return chopped;
  }

  int consecutiveLostTrees;

  public int consecutiveLostTrees() {
    return consecutiveLostTrees;
  }

  public void resetLostTrees() {
    consecutiveLostTrees = 0;
  }

  // -----------------------------------------------------------------------------------------------

  /**
   * Registered on END_CLIENT_TICK. Drives the whole macro.
   *
   * @param mc the client whose player and level are driven
   */
  public synchronized void onClientTick(Minecraft mc) {
    if (!running) {
      return;
    }
    final LocalPlayer player = mc.player;
    final ClientLevel level = mc.level;
    final Options opts = mc.options;
    if (player == null || level == null || opts == null) {
      stop();
      return;
    }

    // Keep the trunk index warm every tick, including while chopping, so the next tree is already
    // known
    // the moment this one falls. This is the work that used to happen all at once in FIND_CLUSTER.
    index.tick(level, chopLogType(), clusterScanner.boundsFor(player, level));
    if (state == State.CONTACT_TREE
        || state == State.ARRIVE_AT_CLUSTER
        || state == State.BREAK_ONE_LOG) {
      preparedNextTree = treeSelection.nearestUnhandledRouteTrunk(player, level, route.current());
    }

    // The gaze handover only exists for the walk to a tree; every other state either owns the head
    // outright
    // or has none to hold, so releasing here saves clearing it on each of the exits out of
    // PATH_TO_CLUSTER.
    if (state != State.PATH_TO_CLUSTER) {
      gaze.release();
      motion.setRouteGaze(false);
    }

    if (progressWatchdog.checkTreeProgress(mc, level, opts)) {
      return;
    }

    motion.beginMovementTick();
    switch (state) {
      case FIND_CLUSTER -> treeSelection.tickFindCluster(mc, player, level);
      case PATH_TO_CLUSTER -> approachActions.tickPathToCluster(mc, player, level, opts);
      case CONTACT_TREE -> approachActions.tickContactTree(player, level, opts);
      case ARRIVE_AT_CLUSTER -> approachActions.tickArriveAtCluster(mc, player, level, opts);
      case BREAK_ONE_LOG -> chopActions.tickBreakOneLog(mc, player, level, opts);
      case MARK_CLUSTER_DONE -> treeSelection.tickMarkClusterDone(mc, player, level, opts);
      case MOVE_TO_NEXT -> treeSelection.tickMoveToNext(player, level, opts);
      case SCAN_COVERAGE_AREA -> treeSelection.tickScanCoverageArea(mc, player, opts);

      default -> {}
    }

    // Hold attack through log transitions; correct the aim without pulsing the button.
    if (clusterTarget.base() != null
        && (state == State.CONTACT_TREE
            || state == State.ARRIVE_AT_CLUSTER
            || state == State.BREAK_ONE_LOG)) {
      // Equip HERE, on arrival, not at commit time. equipTool used to run only after
      // canStartBreakNow had
      // already passed, so anything that stopped the crosshair validating - a body in front of the
      // log,
      // most often - meant the axe was never equipped at all, while left click was already held on
      // whatever happened to be in hand.
      final boolean armed = equipTool(mc, player);
      // And never swing into a body: with an NPC between us and the trunk the crosshair rests on
      // them, so
      // holding attack there is both wasted and the loudest tell the macro has.
      BlockPos obstructed =
          !motion.isRepositioning() && targeting.blockedByCrosshair(mc)
              ? motion.aimedLog(player)
              : null;
      if (obstructed != null) {
        motion.correctBlockedAim(player);
      }
      if (targetState.obstructionTooLong(obstructed, chopCorrectionTicks())) {
        exclusions.blockLog(obstructed.asLong());
        targetState.clearCommit();
        state =
            chopPhase.isClearingTrunkEntry() && !chopActions.plantedAfterCut()
                ? State.CONTACT_TREE
                : State.ARRIVE_AT_CLUSTER;
        activity = "Obstructed log, correcting target";
      }
      BlockPos crosshairLog = targeting.crosshairSelectedClusterLog(mc, level);
      setAttackHeld(
          opts,
          armed
              && !motion.isRepositioning()
              && crosshairLog != null
              && targeting.canStartBreakNow(mc, player, level, crosshairLog));
    }
  }

  private synchronized void onChatMessage(Component message) {
    if (!running || message == null) {
      return;
    }
    final String clean = stripFormatting(message.getString()).trim();
    if (!clean.equalsIgnoreCase("You cannot damage a tree while it is regenerating!")) {
      return;
    }
    final Minecraft mc = Minecraft.getInstance();
    // The server repeats this line for as long as the axe keeps swinging, so drop attack on the
    // FIRST one
    // and ignore the rest - the recording shows three copies land while a human is still reacting.
    if (state == State.MOVE_TO_NEXT) {
      return;
    }
    if (mc.options != null) {
      releaseChop(mc.options);
    }
    targetState.clearCommit();
    final BlockPos regenerating = clusterTarget.base();
    if (regenerating != null) {
      exclusions.skipRegeneratingTree(
          regenerating.asLong(), System.currentTimeMillis() + REGEN_SKIP_MS);
      index.forget(regenerating);
    }
    treeSelection.advanceRoute();
    abortNav();
    activity = "Tree regenerating, moving on";
    scanCooldown = 0;
    handoffTicks = jitter(chopHandoffTicks());
    state = State.MOVE_TO_NEXT;
  }

  /** Move to FIND_CLUSTER, dropping the current target and any held inputs. */
  void toScan(int cooldown) {
    preparedNextTree = null;
    nudgeTarget = null;
    nudgeTicks = 0;
    nudgesUsedThisTree = 0;
    stoppedEarlyThisTree = false;
    gaze.release();
    motion.setRouteGaze(false);
    releaseChop(Minecraft.getInstance().options);
    clusterTarget.clear();
    targeting.clearCluster();
    exclusions.clearTargetBlocks();
    targetState.clearAimCandidate();
    contactProgress.reset();
    chopPhase.reset();
    scanCooldown = cooldown;
    state = State.FIND_CLUSTER;
  }

  // -----------------------------------------------------------------------------------------------

  boolean isAvoidRoute(TreeLogType type, LocalPlayer player, BlockPos target) {
    if (player == null || target == null) {
      return false;
    }
    return avoidance.blocksRoute(type, player.getX(), player.getZ(), target);
  }

  static String avoidLabel(TreeLogType type) {
    return type == TreeLogType.SPRUCE ? "spruce" : "dark oak";
  }

  void debugAvoidSkip(Minecraft mc, TreeLogType type, String reason, BlockPos pos) {
    if (!PathRenderer.isEnabled() || pos == null) {
      return;
    }
    final long key = pos.asLong();
    if (!exclusions.firstAvoidLog(key)) {
      return;
    }
    final String label = avoidLabel(type);
    say(
        mc,
        "§bTree Chop§7: §8"
            + label
            + " avoid - "
            + reason
            + " at "
            + pos.getX()
            + " "
            + pos.getY()
            + " "
            + pos.getZ());
  }

  // Debug throttle for the "Repositioning for trunk entry" no-progress loop (acacia). Only fires
  // with the
  // debug overlay on; ~1.5s between lines so the per-tick loop doesn't flood chat.
  private int trunkEntryTraceCooldown;

  void traceTrunkEntryBlocked(LocalPlayer player, Level level) {
    if (!PathRenderer.isEnabled()) {
      return;
    }
    if (trunkEntryTraceCooldown > 0) {
      trunkEntryTraceCooldown--;
      return;
    }
    trunkEntryTraceCooldown = 30;
    say(
        Minecraft.getInstance(),
        "§bTree Chop§7: §8cannot step into the trunk - "
            + targeting.describeTrunkEntry(player, level));
  }

  boolean isTreecapMode() {
    return chopTreecapMode();
  }

  private String choppedUnit() {
    return isTreecapMode() ? "tree" : "log";
  }

  void releaseChop(Options opts) {
    motion.release(opts);
  }

  void releaseChopOnly(Options opts) {
    motion.releaseAttack(opts);
  }

  /** Functional aim on the game tick (registered after onClientTick so the target is fresh). */
  public void tickLook() {
    motion.tickLook();
  }

  public void onRenderFrame() {
    motion.tickLook();
  }

  boolean equipTool(Minecraft mc, LocalPlayer player) {
    return toolSelector.equip(player);
  }

  // -----------------------------------------------------------------------------------------------

  void abortNav() {
    navigation.stop();
  }

  void issueNav(int x, int y, int z) {
    final Minecraft mc = Minecraft.getInstance();
    final LocalPlayer player = mc.player;
    final BlockPos target = new BlockPos(x, y, z);
    final TreeLogType type = chopLogType();
    if (player != null
        && (avoidance.isAvoidTarget(type, target) || isAvoidRoute(type, player, target))) {
      // Say so on the HUD. This branch returns WITHOUT starting a route and without changing state,
      // so
      // the bot just stands there looking like the pathfinder died - and until now only the spruce
      // box
      // named itself, and only with the debug overlay on (log 2026-08-27 15:46:14: dark oak
      // announced
      // its tree at -371 104 -88, then went silent for over a minute).
      activity = "Avoiding the " + avoidLabel(type) + " box, cannot path there";
      debugAvoidSkip(mc, type, "refusing path through avoid box", target);
      navigation.reset();
      return;
    }
    if (mc.options != null) {
      MacroKeys.set(mc.options.keyShift, false);
    }
    navigation.start(x, y, z);
  }

  boolean navFinished() {
    return navigation.isFinished();
  }

  static void say(Minecraft mc, String msg) {
    RyznChat.send(msg);
  }

  @Override
  public boolean menuEnabled() {
    return isRunning();
  }

  @Override
  public void menuToggle() {
    if (isRunning()) {
      RyznBootstrap.eventBus().post(new TreeChopActionEvent(TreeChopActionEvent.Action.STOP));
    } else {
      RyznBootstrap.eventBus().post(new TreeChopActionEvent(TreeChopActionEvent.Action.START));
    }
  }
}
