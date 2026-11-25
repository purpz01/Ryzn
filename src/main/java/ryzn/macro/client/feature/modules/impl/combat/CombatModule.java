package ryzn.macro.client.feature.modules.impl.combat;

import static ryzn.macro.client.feature.modules.impl.combat.CombatPursuitController.DIRECT_CLIMB_MAX;
import static ryzn.macro.client.feature.modules.impl.combat.CombatPursuitController.DIRECT_WALK_RANGE;
import static ryzn.macro.client.feature.modules.impl.combat.CombatPursuitController.FAR_REPATH_MOVE;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.settings.ConfigSection;
import ryzn.macro.client.config.settings.Setting;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.CombatSessionEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.feature.modules.api.Module;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.feature.pathfinder.path.PathGazeHint;
import ryzn.macro.client.input.MacroKeys;

/**
 * Combat pathfinding module - selects the best nearby hostile mob each tick and (in later phases)
 * walks the existing pathfinder to it and attacks it.
 *
 * <p><b>Selection:</b> every tick it scans loaded entities for valid combat targets, picks the
 * lowest-cost one ({@link CombatTargeting#cost} flow-first distance scoring), and holds that target
 * with a hysteresis margin so it doesn't flip-flop between two near-equal mobs. A target is dropped
 * the moment it dies / despawns / leaves range, then the next best is selected.
 *
 * <p><b>Approach (nav):</b> the route to the target's block is kept live <i>at all times</i> (via
 * {@link WalkingNavigation}), re-planning when the target drifts past the configured move threshold.
 * Keeping it live is the point: a knockback on either side just moves the goal and the path
 * re-routes - the pathfinder is never parked mid-fight.
 *
 * <p><b>Track + attack (assisted aim):</b> once the target is inside attack range, or in front and
 * in clear line of sight (anywhere out to the scan range), {@link CombatAim} eases the head onto
 * the inside of the hitbox each render frame and the vanilla attack key swings whenever the
 * crosshair is on the target and the cooldown has recovered. The path executor reads {@link
 * #ownsLook()} and yields ONLY the head while still driving the body along the live path (its own
 * strafe net + auto-jump), so the bot pathfinds and tracks the mob simultaneously. The instant a
 * wall breaks the sightline, tracking drops and the head returns to the path gaze. All motion is
 * vanilla yaw + keybind simulation (no crafted packets) per the anti-detection rule.
 */
public final class CombatModule extends Module implements ConfigSection {

  /**
   * Shared combat forward-start FOV (deg). Once the target / route bearing is inside this cone the
   * body commits forward (holds W) even before the head finishes rotating, so it starts moving
   * earlier after a handoff or corner turn. Outside the cone the body holds and lets the aim rotate
   * on first, no strafe substitute (that sideways shuffle is the backstepping). Read by {@link
   * CombatBody} and the path executor's combat forward policy so direct chase and combat A* share
   * one gate.
   */
  public static final double COMBAT_FORWARD_FOV_DEG = 100.0;

  static final double MANAGED_ATTACK_RANGE = 3.0;
  private static final int MANAGED_SCAN_RANGE = 32;

  @Getter private boolean enabled = false;
  @Getter boolean managed;
  ManagedSettings managedSettings = ManagedSettings.defaults();
  boolean retainNavigationWhileAwaitingTarget;
  private final CombatHazards hazards = new CombatHazards();
  final CombatSight sight = new CombatSight();
  final CombatTargeting targeting = new CombatTargeting(hazards, sight);
  final CombatRoam roam = new CombatRoam(hazards);
  final CombatAim aim = new CombatAim();
  private final CombatManaged session = new CombatManaged(targeting);

  /** Everything about the fight in progress. Replaced wholesale by {@link #reset()}. */
  CombatState state = new CombatState();

  final CombatRoam.Patrol patrol = new CombatRoam.Patrol();
  // Visibility grace + path-blacklist + search state.
  final CombatSight.Grace sightGrace = new CombatSight.Grace();
  final CombatPathFailures pathFailures = new CombatPathFailures();
  final CombatNav nav = new CombatNav();
  final CombatBody body = new CombatBody();
  final CombatTelemetry telemetry = new CombatTelemetry();

  // Progress watchdog: catch "tracking a mob but not closing in" (staring) and recover fast.
  final CombatProgressWatchdog progressWatchdog = new CombatProgressWatchdog();

  final CombatAttack attack = new CombatAttack(aim);
  final CombatTargetSelector targetSelector = new CombatTargetSelector(targeting);

  /** Blocks a target must move before the route is replanned. */
  private final Setting<Integer> combatTargetMoveThreshold =
      addSetting(
          Setting.integer("combat_target_move_threshold", "Target move threshold", 3, 1, 16));

  /** Score margin a new target needs before the current one is dropped. */
  private final Setting<Integer> combatHysteresisMargin =
      addSetting(Setting.integer("combat_hysteresis_margin", "Hysteresis margin", 3, 0, 16));

  /** Ticks between route replans. */
  private final Setting<Integer> combatRepathCooldownTicks =
      addSetting(Setting.integer("combat_repath_cooldown_ticks", "Repath cooldown", 1, 1, 100));

  /** How firmly the head tracks the target, in percent. */
  private final Setting<Integer> combatAimStrength =
      addSetting(Setting.integer("combat_aim_strength", "Aim strength", 85, 0, 100));

  /** Head turn acceleration, in percent. */
  private final Setting<Integer> combatRotationAcceleration =
      addSetting(
          Setting.integer("combat_rotation_acceleration", "Rotation acceleration", 85, 0, 100));

  /** Share of aim corrections that are allowed to lag, in percent. */
  private final Setting<Integer> combatAimLazinessPercent =
      addSetting(Setting.integer("combat_aim_laziness_percent", "Aim laziness", 0, 0, 100));

  /** Whether debug overlays hide themselves for a clean recording. */
  private final Setting<Boolean> combatRecordingClean =
      addSetting(Setting.bool("combat_recording_clean", "Recording clean", false));

  final CombatTargetController targets = new CombatTargetController(this);
  private final CombatPursuitController pursuit = new CombatPursuitController(this);

  /**
   * @description Creates the process-lifetime combat engine that macros borrow as a managed
   *     session, with its saved head-movement and replanning options.
   */
  public CombatModule() {
    super(
        "combat",
        "Combat",
        "Fights for macros that borrow it as a managed session; not started on its own.");
  }

  /**
   * @description Returns the hazard zones this module scans, for the hazard overlay.
   * @return the module's hazard tracker
   */
  public CombatHazards hazards() {
    return hazards;
  }

  /**
   * @description Returns the target rules this module applies, for the target ESP.
   * @return the module's targeting state
   */
  public CombatTargeting targeting() {
    return targeting;
  }

  /**
   * @description Loads the saved options under their original keys, clamping each to its range.
   * @param object the section JSON; a missing or mistyped value falls back to its default
   */
  @Override
  public void read(JsonObject object) {
    readSettings(object);
  }

  /**
   * @description Writes the saved options in their original key order.
   * @return a new section object for the settings file
   */
  @Override
  public JsonObject write() {
    return writeSettings();
  }

  /**
   * @description Returns the saved value: blocks a target must move before the route is replanned.
   * @return the current value, between 1 and 16
   */
  public int getCombatTargetMoveThreshold() {
    return combatTargetMoveThreshold.get();
  }

  /**
   * @description Returns the saved value: score margin a new target needs before the current one is
   *     dropped.
   * @return the current value, between 0 and 16
   */
  public int getCombatHysteresisMargin() {
    return combatHysteresisMargin.get();
  }

  /**
   * @description Returns the saved value: ticks between route replans.
   * @return the current value, between 1 and 100
   */
  public int getCombatRepathCooldownTicks() {
    return combatRepathCooldownTicks.get();
  }

  /**
   * @description Returns the saved value: share of aim corrections that are allowed to lag, in
   *     percent.
   * @return the current value, between 0 and 100
   */
  public int getCombatAimLazinessPercent() {
    return combatAimLazinessPercent.get();
  }

  /**
   * @description Reports whether debug overlays hide themselves for a clean recording.
   * @return the saved switch
   */
  public boolean isCombatRecordingClean() {
    return combatRecordingClean.get();
  }

  /**
   * @description Applies a managed-session request from a macro or the failsafe and records whether
   *     a reconfigure or patrol resume was accepted.
   * @param combatsessionevent the requested session action and its payload
   */
  @Subscribe
  public void onCombatSessionEvent(CombatSessionEvent combatsessionevent) {
    switch (combatsessionevent.action()) {
      case START -> startManaged(combatsessionevent.settings(), combatsessionevent.callback());
      case STOP -> stopManaged();
      case RECONFIGURE ->
          combatsessionevent.setAccepted(
              reconfigureManaged(
                  combatsessionevent.settings(), combatsessionevent.retainNavigation()));
      case RESUME_PATROL_AT ->
          combatsessionevent.setAccepted(
              resumeManagedPatrolAt(combatsessionevent.settings(), combatsessionevent.point()));
      case HOLD_LOOK -> holdManagedLook(combatsessionevent.millis());
      default -> throw new IllegalArgumentException("Unknown combat session action");
    }
  }

  /**
   * The user's settings with this session's overrides applied. One line, so the fork is never
   * re-derived.
   */
  CombatConfig resolved() {
    return CombatConfig.of(managedSettings);
  }

  public boolean isManagingAbilitySwap() {
    return managed && attack.ability().isSwapping();
  }

  public void holdManagedLook(long millis) {
    if (!managed || millis <= 0) {
      return;
    }
    state.managedLookHandoffUntilNanos = System.nanoTime() + millis * 1_000_000L;
  }

  /**
   * Change a borrowed combat profile without tearing down its current pathfinder route.
   *
   * @param settings the replacement profile
   * @param retainNavigationWhileAwaitingTarget whether the route survives until a target shows up
   * @return true when a managed session was running and took the new profile
   */
  public boolean reconfigureManaged(
      ManagedSettings settings, boolean retainNavigationWhileAwaitingTarget) {
    if (!managed) {
      return false;
    }
    final ManagedSettings active = settings == null ? ManagedSettings.defaults() : settings;
    attack.reset(Minecraft.getInstance().player);
    body.stop();
    state = new CombatState();
    sightGrace.reset();
    pathFailures.resetCount();
    resetProgressWatchdog();
    aim.reset();
    roam.clearSearch();
    telemetry.reset();
    managedSettings = active;
    this.retainNavigationWhileAwaitingTarget = retainNavigationWhileAwaitingTarget;
    session.applyFilters(active);
    nav.replanNow();
    return true;
  }

  /**
   * Restore a managed patrol profile and hot-swap the current route to its requested point.
   *
   * @param settings the patrol profile to restore
   * @param point the route point to resume from
   * @return true when a managed session was running and resumed there
   */
  public boolean resumeManagedPatrolAt(ManagedSettings settings, BlockPos point) {
    if (!managed || point == null || settings == null || !settings.patrolPoints().contains(point)) {
      return false;
    }
    if (!reconfigureManaged(settings, false)) {
      return false;
    }
    if (!patrol.resumeAt(point, managedSettings.patrolPoints())) {
      return false;
    }
    nav.replanNow();
    final RyznSettings cfg = RyznSettings.get();
    nav.planTo(
        CombatNav.Slot.ROAM,
        point,
        Math.max(FAR_REPATH_MOVE, getCombatTargetMoveThreshold()),
        getCombatRepathCooldownTicks());
    return true;
  }

  public void startManaged(ManagedSettings settings, Consumer<ManagedResult> callback) {
    if (managed) {
      stopManaged();
    }
    ManagedSettings active = settings == null ? ManagedSettings.defaults() : settings;
    managed = true;
    managedSettings = active;
    session.begin(active.noTargetSeconds(), callback);
    reset();
    enabled = true;
    session.applyFilters(active);
    // A forward-flow or patrol session walks its own route; remembered mob spots would fight it.
    if (managedKeepsForwardFlow(active) || managedUsesPatrol(active)) {
      roam.reset();
    }
  }

  public void stopManaged() {
    if (managed) {
      finishManaged(ManagedResult.STOPPED);
    }
  }

  public LivingEntity getTarget() {
    return state.target;
  }

  /**
   * True while the combat assist should own the player's head: once tracking (mob in attack range,
   * or close and in clear line of sight). The path executor reads this and yields ONLY the head -
   * it keeps driving the body along the live path - so the bot pathfinds and tracks the mob at the
   * same time. Out of sight this is false and the executor gazes the path normally.
   *
   * @return true while combat tracks a mob with the head
   */
  public boolean ownsLook() {
    boolean activeTarget = state.tracking && state.target != null && state.target.isAlive();
    return enabled
        && lookOwnershipActive(activeTarget, System.nanoTime(), state.managedLookHandoffUntilNanos)
        && resolved().aimAssist();
  }

  /**
   * True while the combat module owns the active pathfinder route (its A* approach / search). The
   * path executor reads this to apply a forward-only movement policy: combat aims the head at the
   * mob, not down the path, so a route node behind the current facing would otherwise press S / a
   * lone strafe and read as backstepping. False during close-range direct chase (combat drives the
   * body itself) and when idle, so normal {@code /pathfind} is never affected.
   *
   * @return true while combat drives the active route
   */
  /**
   * True while a flowing hunt drives the route: the walker keeps walking forward through a turn
   * instead of stopping or stepping back while the head comes round.
   *
   * @return true while a flowing hunt owns the route
   */
  public boolean prefersForwardArc() {
    return isCombatNavActive() && managed && zealotFlow(managedSettings);
  }

  public boolean isCombatNavActive() {
    return enabled && nav.isOwned();
  }

  public boolean usesRecordedMovement() {
    return enabled && managedSettings != null && managedSettings.recordedMovement();
  }

  /**
   * Reports the RESOLVED config, so a macro-driven session shows its own overrides, not the saved
   * settings.
   *
   * @return a one-line summary of the resolved configuration and state
   */
  public String status() {
    if (!enabled) {
      return "§7[combat] disabled";
    }
    final CombatConfig cfg = resolved();
    final String opts =
        "§8[sight:"
            + onOff(cfg.requireSight())
            + " §8search:"
            + onOff(cfg.searchEnabled())
            + " §8danger:"
            + onOff(cfg.avoidDanger())
            + (cfg.bounds().isBlank() ? "" : " §8bounds:§aset")
            + "§8]";
    if (state.target == null) {
      return "§7[combat] enabled - "
          + (roam.isSearching() ? "§7searching" : "§7no target")
          + " "
          + opts;
    }
    final LocalPlayer p = Minecraft.getInstance().player;
    final String dist = p != null ? String.format(" §7(%.1fm)", p.distanceTo(state.target)) : "";
    return "§b[combat] target: " + state.target.getName().getString() + dist + " " + opts;
  }

  private static String onOff(boolean v) {
    return v ? "§aon" : "§coff";
  }

  /**
   * End the fight. Order matters: the side effects have to be undone while the state that describes
   * them is still readable (the held item goes back BEFORE the swap slots are dropped), and only
   * then is the fight state replaced - so nothing here has to remember to name each field.
   */
  private void reset() {
    PathGazeHint.clear();
    retainNavigationWhileAwaitingTarget = false;
    attack.reset(Minecraft.getInstance().player); // weapon back FIRST, then release Shift
    CombatBody.releaseKeys(); // release any held chase keys
    nav.reset(); // stop our route and drop the plan
    state = new CombatState();

    sightGrace.reset();
    pathFailures.resetCount();
    patrol.reset();
    body.reset();
    telemetry.reset();
    resetProgressWatchdog();
    aim.reset(); // forget the drift, smoother and cached readiness
    roam.clearSearch(); // drop the active roam; remembered spots survive a brief area exit
    hazards.reset(); // hazard zones are area-specific; the per-tick scan rebuilds them
    sight.clear(); // forget recent-sight records; rebuilt as mobs come back into view
  }

  public void onClientTick(Minecraft mc) {
    if (!enabled) {
      return;
    }
    final LocalPlayer player = mc.player;
    final ClientLevel level = mc.level;
    if (player == null || level == null) {
      reset();
      return;
    }
    if (managed && mc.gui.screen() != null) {
      reset();
      return;
    }
    final RyznSettings cfg = RyznSettings.get();
    targeting.setRecordedMovement(usesRecordedMovement());
    targeting.expireBlacklist();
    sight.prune();
    if (resolved().avoidDanger()) {
      hazards.scan(player, level);
    }
    targets.selectTarget(player, level, cfg);
    pursuit.navAndAttackTick(mc, player, cfg);
    if (usesRecordedMovement()) {
      telemetry.traceMotion(
          mc, state.tracking, nav.isOwned(), state.target == null ? -1 : state.target.getId());
      telemetry.traceAim(
          mc, state.tracking, state.target == null ? -1 : state.target.getId(), aim.getAimSample());
    }
    if (managed) {
      if (!managedSettings.sprint() && mc.options != null) {
        MacroKeys.set(mc.options.keySprint, false);
      }
      if (session.outOfTargets(state.target != null)) {
        finishManaged(ManagedResult.NO_TARGETS);
      }
    }
  }

  void resetProgressWatchdog() {
    progressWatchdog.reset();
    state.stallBridge = false;
  }

  /**
   * True when the mob can be closed on WITHOUT a path: inside the direct-chase band and not perched
   * more than one jump above the player (walking reaches anything level or below - dropping off an
   * edge is just walking forward - but a higher perch needs A* to find the way up). Shared with
   * {@link CombatTargeting#accepts}: the path-failure blacklist is only waived for mobs this
   * predicate accepts, so a near-but-elevated unreachable mob cannot ping-pong between blacklist
   * and re-selection.
   */
  static boolean directlyChaseable(LocalPlayer self, Entity e) {
    if (self.distanceToSqr(e) > DIRECT_WALK_RANGE * DIRECT_WALK_RANGE) {
      return false;
    }
    return e.getY() - self.getY() <= DIRECT_CLIMB_MAX;
  }

  /**
   * A flowing hunt (strike-once, or a verified-type session such as the Zealot Macro with any
   * weapon): the body never brakes except to land a hit, and far legs are left to the teleports.
   */
  static boolean zealotFlow(ManagedSettings settings) {
    return strikesOnce(settings) || settings != null && settings.verifiedType() != null;
  }

  static boolean strikesOnce(ManagedSettings settings) {
    return settings != null && settings.attackMode() == ManagedAttackMode.STRIKE_ONCE;
  }

  static boolean managedHoldsMeleeInRange(
      ManagedAttackMode mode, double distance, double range, boolean visible) {
    return mode == ManagedAttackMode.HOLD_IN_RANGE_MELEE && visible && distance <= range;
  }

  void suppressManagedTargetGroup() {
    targets.suppressManagedTargetGroup();
  }

  static <T> List<T> connectedGroup(
      T seed, List<T> candidates, int limit, BiPredicate<T, T> connected) {
    return CombatTargetController.connectedGroup(seed, candidates, limit, connected);
  }

  /**
   * /** Per render frame: ease the head (yaw + pitch together) toward the inside of the target
   * hitbox while tracking. The executor keeps the body on the live path; this only redirects the
   * gaze onto the mob - a smooth, capped hand-off from the path gaze rather than a snap.
   */
  /**
   * Combat aim driven by wall clock (CombatAim is dt-based), so the tick keeps tracking at any fps
   * and the render frame can run the same assist again for free smoothness. Also keeps
   * attack-readiness fresh.
   */
  public void tickLook() {
    final LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) {
      return;
    }
    final RyznSettings cfg = RyznSettings.get();
    aim.recordedMovement = usesRecordedMovement();
    if (aim.recordedMovement) {
      // Target gaps are part of this session, not a new camera session on every acquisition.
      RyznBootstrap.rotationManager().markCamera(player, state.tracking && ownsLook());
    }
    aim.update(
        enabled,
        state.tracking,
        state.target,
        cfg,
        resolved().aimAssist(),
        resolved().attackRange(targets.hasNearbyManagedTarget()));
    if (aim.recordedMovement) {
      RyznBootstrap.rotationManager().markCamera(player, state.tracking && ownsLook());
    } else if (ownsLook()) {
      // Keep the smoother primed EVERY tick we drive the head, even when locked on target and the
      // aim
      // holds still this tick - else stampFresh lapses and the camera drops to raw 20 Hz (jitter),
      // the
      // same trap treechop hit. aim.assist early-returns inside its deadzone, so the head not
      // moving does not mean we stopped owning the look.
      RyznBootstrap.rotationManager().markCamera(player);
    }
  }

  /**
   * {@link CombatAim} eases by wall clock, so the render frame just drives the same assist again.
   */
  public void onRenderFrame() {
    tickLook();
    LocalPlayer player = Minecraft.getInstance().player;
    if (player != null && usesRecordedMovement()) {
      telemetry.captureFrame(player);
    }
  }

  /** Hand the session back: drop it, restore the user's own settings, THEN tell whoever asked. */
  private void finishManaged(ManagedResult result) {
    final Consumer<ManagedResult> callback = session.end();
    managed = false;
    managedSettings = ManagedSettings.defaults();
    reset();
    enabled = false;
    roam.reset();
    targeting.clearBlacklist();
    if (callback != null) {
      callback.accept(result);
    }
  }

  static boolean managedKeepsForwardFlow(ManagedSettings settings) {
    return settings != null
        && settings.attackMode() == ManagedAttackMode.CLUSTERED_USE_HELD_ITEM
        && !managedUsesPatrol(settings);
  }

  static boolean managedUsesPatrol(ManagedSettings settings) {
    return settings != null && !settings.patrolPoints().isEmpty();
  }

  /** The Maddox interceptor alone may keep the prior route while a pinned target is pending. */
  static boolean retainsNavigationWhileAwaitingTarget(
      boolean routeInterceptor, ManagedSettings settings) {
    return routeInterceptor
        && settings != null
        && settings.requiredTargetId() != null
        && !settings.search();
  }

  static boolean managedSuppressesTargets(ManagedSettings settings) {
    return settings != null && settings.attackMode() == ManagedAttackMode.PREPARE_SPAWN;
  }

  static boolean lookOwnershipActive(boolean activeTarget, long nowNanos, long handoffUntilNanos) {
    return activeTarget || nowNanos < handoffUntilNanos;
  }

  public record ManagedSettings(
      List<String> targetNames,
      boolean sprint,
      int noTargetSeconds,
      boolean search,
      double attackRange,
      int scanRange,
      ManagedAttackMode attackMode,
      int attackIntervalTicks,
      String bounds,
      UUID requiredTargetId,
      String abilityItemName,
      String restoreItemName,
      List<BlockPos> patrolPoints,
      VerifiedType verifiedType,
      boolean recordedMovement) {
    public ManagedSettings {
      targetNames = targetNames == null ? List.of() : List.copyOf(targetNames);
      noTargetSeconds = Math.clamp(noTargetSeconds, 0, 30);
      attackRange = Math.clamp(attackRange, 2.0, 96.0);
      scanRange = Math.clamp(scanRange, 8, 96);
      attackMode = attackMode == null ? ManagedAttackMode.MELEE : attackMode;
      attackIntervalTicks = Math.clamp(attackIntervalTicks, 1, 100);
      bounds = bounds == null ? "" : bounds;
      abilityItemName = abilityItemName == null ? "" : abilityItemName;
      restoreItemName = restoreItemName == null ? "" : restoreItemName;
      patrolPoints = patrolPoints == null ? List.of() : List.copyOf(patrolPoints);
    }

    public ManagedSettings(
        List<String> targetNames,
        boolean sprint,
        int noTargetSeconds,
        boolean search,
        double attackRange,
        int scanRange,
        ManagedAttackMode attackMode,
        int attackIntervalTicks,
        String bounds,
        UUID requiredTargetId,
        String abilityItemName,
        String restoreItemName,
        List<BlockPos> patrolPoints,
        VerifiedType verifiedType) {
      this(
          targetNames,
          sprint,
          noTargetSeconds,
          search,
          attackRange,
          scanRange,
          attackMode,
          attackIntervalTicks,
          bounds,
          requiredTargetId,
          abilityItemName,
          restoreItemName,
          patrolPoints,
          verifiedType,
          false);
    }

    public ManagedSettings(
        List<String> targetNames,
        boolean sprint,
        int noTargetSeconds,
        boolean search,
        double attackRange,
        int scanRange,
        ManagedAttackMode attackMode,
        int attackIntervalTicks,
        String bounds,
        UUID requiredTargetId,
        String abilityItemName,
        String restoreItemName,
        List<BlockPos> patrolPoints) {
      this(
          targetNames,
          sprint,
          noTargetSeconds,
          search,
          attackRange,
          scanRange,
          attackMode,
          attackIntervalTicks,
          bounds,
          requiredTargetId,
          abilityItemName,
          restoreItemName,
          patrolPoints,
          null);
    }

    public ManagedSettings(
        List<String> targetNames,
        boolean sprint,
        int noTargetSeconds,
        boolean search,
        double attackRange,
        int scanRange,
        ManagedAttackMode attackMode,
        int attackIntervalTicks,
        String bounds,
        UUID requiredTargetId,
        String abilityItemName,
        String restoreItemName) {
      this(
          targetNames,
          sprint,
          noTargetSeconds,
          search,
          attackRange,
          scanRange,
          attackMode,
          attackIntervalTicks,
          bounds,
          requiredTargetId,
          abilityItemName,
          restoreItemName,
          List.of());
    }

    public ManagedSettings(
        List<String> targetNames,
        boolean sprint,
        int noTargetSeconds,
        boolean search,
        double attackRange,
        int scanRange,
        ManagedAttackMode attackMode,
        int attackIntervalTicks,
        String bounds,
        UUID requiredTargetId) {
      this(
          targetNames,
          sprint,
          noTargetSeconds,
          search,
          attackRange,
          scanRange,
          attackMode,
          attackIntervalTicks,
          bounds,
          requiredTargetId,
          "",
          "");
    }

    public ManagedSettings(
        List<String> targetNames,
        boolean sprint,
        int noTargetSeconds,
        boolean search,
        double attackRange,
        int scanRange,
        ManagedAttackMode attackMode,
        int attackIntervalTicks,
        String bounds) {
      this(
          targetNames,
          sprint,
          noTargetSeconds,
          search,
          attackRange,
          scanRange,
          attackMode,
          attackIntervalTicks,
          bounds,
          null);
    }

    public ManagedSettings(List<String> targetNames, boolean sprint, int noTargetSeconds) {
      this(
          targetNames,
          sprint,
          noTargetSeconds,
          false,
          MANAGED_ATTACK_RANGE,
          MANAGED_SCAN_RANGE,
          ManagedAttackMode.MELEE,
          CombatAttack.BASE_ATTACK_INTERVAL,
          "");
    }

    public static ManagedSettings defaults() {
      return new ManagedSettings(List.of(), true, 10);
    }
  }

  /**
   * Targets every mob of {@code type} while its nametag is out of render range, and only the ones
   * whose nametag matches the session's names once within {@code verifyRange}.
   *
   * @param type the entity type tracked beyond nametag range
   * @param verifyRange blocks within which the nametag must confirm the name
   */
  public record VerifiedType(EntityType<?> type, double verifyRange) {}

  public enum ManagedAttackMode {
    MELEE,
    USE_HELD_ITEM,
    CLUSTERED_USE_HELD_ITEM,
    HOLD_IN_RANGE_MELEE,
    STATIONARY_MELEE,
    PREPARE_SPAWN,
    /** One hit per mob, then straight on to the next without stopping. */
    STRIKE_ONCE
  }

  public enum ManagedResult {
    NO_TARGETS,
    STOPPED
  }
}
