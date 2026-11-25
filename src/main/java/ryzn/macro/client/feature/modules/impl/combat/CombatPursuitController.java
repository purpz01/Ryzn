package ryzn.macro.client.feature.modules.impl.combat;

import static ryzn.macro.client.feature.modules.impl.combat.CombatModule.COMBAT_FORWARD_FOV_DEG;
import static ryzn.macro.client.feature.modules.impl.combat.CombatModule.managedHoldsMeleeInRange;
import static ryzn.macro.client.feature.modules.impl.combat.CombatModule.managedKeepsForwardFlow;
import static ryzn.macro.client.feature.modules.impl.combat.CombatModule.managedSuppressesTargets;
import static ryzn.macro.client.feature.modules.impl.combat.CombatModule.managedUsesPatrol;
import static ryzn.macro.client.feature.modules.impl.combat.CombatModule.retainsNavigationWhileAwaitingTarget;
import static ryzn.macro.client.feature.modules.impl.combat.CombatModule.zealotFlow;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.feature.modules.impl.combat.CombatModule.ManagedAttackMode;
import ryzn.macro.client.feature.pathfinder.path.PathExecutor;
import ryzn.macro.client.feature.pathfinder.path.PathGazeHint;
import ryzn.macro.client.input.MacroKeys;

/**
 * @description Combat approach, patrol movement, tracking and attack orchestration.
 */
final class CombatPursuitController {
  private final CombatModule combat;

  CombatPursuitController(CombatModule combat) {
    this.combat = combat;
  }

  /**
   * Extra distance past attack range the target may drift before head-tracking drops back to the
   * path gaze.
   */
  private static final double ENGAGE_EXIT_BUFFER = 0.75;

  /** Ticks a lost target's head lock waits for the next pick before the route gaze takes over. */
  static final int LOOK_HANDOFF_TICKS = 4;

  /**
   * Blocks beyond attack range at which recorded combat turns the head from the path to the mob.
   */
  private static final double RECORDED_LOOK_BUFFER = 2.5;

  /**
   * Far tracking enters only when the mob is within this many degrees of the current facing (yaw
   * cone).
   */
  private static final double TRACK_FOV_DEG = 60.0;

  /**
   * Visibility grace: hold an existing head-lock this long after the mob ducks behind cover
   * (anti-flicker).
   */
  private static final long LOS_GRACE_MS = 1200L;

  /**
   * ...only while the mob stays within this distance (a far mob that loses sight is genuinely
   * gone).
   */
  private static final double LOS_GRACE_DIST = 6.0;

  /**
   * Beyond this distance (blocks) the A* pathfinder closes the gap; within it the bot walks
   * straight at the mob each tick. A* re-planning to a mob that drifts a block every half second
   * tore the route down (and released the movement keys) each time, so the body stuttered in place
   * - direct walking answers the mob every tick instead. The pathfinder stays the long-range / roam
   * tool. Sized past the default attack range: a mob at ~3.5 blocks used to fall in a dead band
   * (too far to swing, too far to direct-chase) where every half-block of closing went through a
   * full async A* round trip - the bot stood aim-locked doing nothing.
   */
  static final double DIRECT_WALK_RANGE = 4.5;

  /**
   * A mob more than one jump above the player cannot be closed by walking straight at it - A* takes
   * those even inside the direct band (it can route up stairs; straight walking just grinds the
   * wall below).
   */
  static final double DIRECT_CLIMB_MAX = 1.25;

  /**
   * Floor on the move-repath threshold for the FAR A* approach, so a wandering mob doesn't thrash
   * replans.
   */
  static final int FAR_REPATH_MOVE = 2;

  /**
   * Beyond direct-chase range, keep walking straight at a visible, roughly level mob while its A*
   * route is still resolving (this many blocks past {@link #DIRECT_WALK_RANGE}), so a target
   * hand-off does not stall or backstep in the gap before the path goes live. Bounded +
   * line-of-sight gated so it never replaces A*.
   */
  private static final double COMBAT_BRIDGE_EXTRA = 2.0;

  /**
   * Progress watchdog: a tracked-but-not-hittable target whose distance has not improved by {@link
   * #PROGRESS_EPS} for this many ticks (~1.25s) counts as a stall - the bot is staring instead of
   * closing - and triggers recovery (force repath + forward bridge, then a target switch).
   */
  private static final int STALL_TICKS = 25;

  /**
   * Net closing (blocks) that counts as real progress and resets the stall counter / advances the
   * checkpoint.
   */
  private static final double PROGRESS_EPS = 0.25;

  /**
   * After this many consecutive stall windows on one target the recovery escalates to dropping it.
   */
  private static final int STALL_GIVEUP = 2;

  /**
   * Flow direct-chase band (blocks): a visible, roughly level mob within this range is walked at
   * directly (rotate + hold W) instead of stopping to plan an A* route, so an obvious nearby zombie
   * keeps the bot flowing. Wider than {@link #DIRECT_WALK_RANGE}; A* still owns far, vertical,
   * blocked, or off-sight mobs.
   */
  private static final double FLOW_CHASE_RANGE = 24.0;

  /** How far past attack range the walking head starts drifting onto the next mob. */
  private static final double PRE_AIM_RANGE = 24.0;

  /** The shortest Aspect hop; closer than this plus the end buffer no teleport can help. */
  private static final double TRANSMISSION_FIRST_MIN_RANGE = 8.0;

  /**
   * Drive the body toward the mob and, once it is close and in sight, let the assisted aim track
   * its head and swing. A far mob is closed on by the A* pathfinder (executor drives the body,
   * combat owns the head); a near mob is chased by walking straight at it every tick ({@link
   * CombatBody}) - far more responsive than re-planning A* to a mob that moves each tick. Runs
   * after {@link CombatTargetController#selectTarget} so it sees the freshest target.
   */
  void navAndAttackTick(Minecraft mc, LocalPlayer player, RyznSettings cfg) {
    combat.body.recordedMovement = combat.usesRecordedMovement();
    combat.nav.tickCooldown();
    if (combat.state.attackDelay > 0) {
      combat.state.attackDelay--;
    }
    if (combat.state.abilityDelay > 0) {
      combat.state.abilityDelay--;
    }
    if (combat.attack.ability().tick()) {
      combat.attack.ability().restore(player);
    } // cast window over: weapon back
    if (combat.state.commitTicks > 0) {
      combat.state.commitTicks--;
    }
    if (combat.state.handoffBridgeTicks > 0) {
      combat.state.handoffBridgeTicks--;
    }
    if (combat.state.lookHandoffTicks > 0) {
      combat.state.lookHandoffTicks--;
    }

    if (combat.managed
        && combat.managedSettings.attackMode() == ManagedAttackMode.STATIONARY_MELEE) {
      stationaryMeleeTick(mc, player, cfg);
      return;
    }

    if (combat.state.target == null) {
      if (combat.attack.ability().isPrepared()) {
        combat.attack.ability().restore(player);
      }
      if (combat.state.tracking) {
        combat.state.lookHandoffTicks = LOOK_HANDOFF_TICKS;
      }
      stopTracking();
      combat.progressWatchdog.clearStalled();
      combat.state.stallBridge = false;
      combat.body.stop();
      searchTick(cfg, player); // follow a managed patrol, or roam toward remembered mobs
      if (managedKeepsForwardFlow(combat.managedSettings)) {
        CombatBody.creepForward(false);
      }
      detectSearchPathFailure();
      return;
    }

    if (combat.roam.isSearching()) {
      combat.roam.onTargetAcquired();
    }

    final double dist = player.distanceTo(combat.state.target);
    final boolean clustered = combat.targets.hasNearbyManagedTarget();
    final double range = combat.resolved().attackRange(clustered);
    if (managedHoldsMeleeInRange(
        combat.managedSettings.attackMode(),
        dist,
        range,
        CombatTargetController.hasLineOfSight(player, combat.state.target))) {
      holdMeleeInRange(mc, player, cfg, dist, range, clustered);
      return;
    }
    if (managedUsesPatrol(combat.managedSettings)) {
      patrolAndAttackTick(mc, player, cfg, dist, range, clustered);
      return;
    }
    final boolean hittable = CombatAim.isHittable(player, combat.state.target, range);

    // Progress watchdog: if the bot is tracking this mob but not closing in (staring), recover fast
    // instead
    // of waiting. First stall forces an immediate repath and, when the mob is walk-reachable and in
    // front,
    // a direct forward chase even over an active A* route; a repeated stall drops the mob for the
    // next one.
    combat.state.stallBridge = false;
    boolean closeUnreachable =
        (combat.nav.isOwned() || combat.body.isActive())
            && dist <= DIRECT_WALK_RANGE + COMBAT_BRIDGE_EXTRA;
    final CombatProgressWatchdog.Outcome stall =
        combat.progressWatchdog.update(
            combat.state.target.getUUID(),
            dist,
            hittable,
            combat.state.tracking || closeUnreachable,
            PROGRESS_EPS,
            STALL_TICKS,
            STALL_GIVEUP);
    if (stall == CombatProgressWatchdog.Outcome.GIVE_UP) {
      combat.targets.abandonStalledTarget(
          player, cfg); // give up staring: blacklist briefly, reacquire next tick
      return;
    }
    if (stall == CombatProgressWatchdog.Outcome.RECOVER) {
      combat.nav.replanNow(); // clear the stale route + cooldown so approach() replans now
      combat.state.stallBridge =
          canStallBridge(player, dist); // force forward chase if visible, level, in FOV, in range
      DebugLog.trace(
          DebugLog.Category.MACRO,
          "Combat: unstuck from the target, %.2f blocks away (bridged=%b)",
          dist,
          combat.state.stallBridge);
    }

    final double yawError = Math.abs(yawErrorToTarget(player));
    final boolean targetVisible =
        CombatTargetController.hasLineOfSight(player, combat.state.target);
    boolean recovering = combat.state.stallBridge || canHandoffBridge(player, dist);
    CombatApproach.Approach approach =
        chooseApproach(player, dist, targetVisible, yawError, recovering, hittable);
    final boolean turningOntoTarget = approach == CombatApproach.Approach.TURN_FIRST;
    combat.telemetry.observeTarget(yawError, targetVisible, approach.name(), turningOntoTarget);

    // Decide head ownership before the body hand-off. When the approach is going to stop the body
    // because
    // the mob is off-axis, the aim must already be engaged so the yaw can actually close.
    setTracking(shouldTrack(player, dist, turningOntoTarget), cfg, dist, range);

    // Hybrid movement. FAR (or near but perched above a single hop's reach): the A* pathfinder
    // closes the
    // gap (it is built for long routes through real terrain). NEAR: walk straight at the mob every
    // tick
    // instead - re-planning A* to a mob that drifts a block every half second tore the route down
    // and
    // released the keys each time, so the body stuttered in place (the "stands still aim-assisting"
    // bug).
    // Direct walking answers the mob's movement every tick. A stall recovery can also force the
    // direct chase.
    boolean flowing =
        combat.usesRecordedMovement()
            ? CombatModule.strikesOnce(combat.managedSettings)
            : zealotFlow(combat.managedSettings);
    combat.body.stopOnlyToHit = flowing;
    updateGazeHint(player, dist);
    if (approach != CombatApproach.Approach.FOLLOW_ROUTE) {
      // NEAR, or a visible level mob just outside the direct band whose A* route is still
      // resolving: walk
      // straight at it instead of parking the body while the path plans, so a target hand-off does
      // not
      // visibly pause or backstep. The executor takes back over the instant a route goes live.
      combat.nav.release(); // take the body from the executor
      combat.body.chase(
          player,
          combat.state.target,
          range,
          combat.resolved().oneTap() || managedKeepsForwardFlow(combat.managedSettings) || flowing,
          yawErrorToTarget(player),
          targetVisible,
          FLOW_CHASE_RANGE,
          combat.telemetry);
      combat.pathFailures.consumeIfChanged(PathfindCommand.getNavFailSeq());
    } else {
      if (!combat.usesRecordedMovement()
          && transmissionFirst(dist)
          && targetVisible
          && !PathfindCommand.isNavigationActive()) {
        // The next hop route is still planning: keep the body flowing at the mob rather than
        // parking it for those ticks. The executor takes over the instant the route is live.
        combat.body.chase(
            player,
            combat.state.target,
            range,
            true,
            yawErrorToTarget(player),
            true,
            FLOW_CHASE_RANGE,
            combat.telemetry);
      } else {
        combat.body.stop();
      }
      approach(cfg);
      combat.targets.detectPathFailure(cfg);
      if (combat.state.target == null) {
        return;
      } // detectPathFailure may have dropped + blacklisted an unreachable target
    }

    if (dist <= range) {
      combat.pathFailures.resetCount();
    }

    // Track + swing once the mob is visible and in range. While direct-chasing the executor is
    // parked, so
    // the per-frame aim ({@link CombatModule#onRenderFrame}) is the only thing writing the head -
    // it faces the
    // mob while
    // the body walks under it.
    if (combat.state.tracking) {
      combat.attack.swing(
          mc,
          player,
          combat.resolved(),
          combat.state,
          dist,
          range,
          clustered,
          combat.telemetry,
          combat::suppressManagedTargetGroup);
    } else {
      if (combat.attack.ability().isPrepared()) {
        combat.attack.ability().restore(player);
      }
    }
    combat.telemetry.traceMode(
        dist,
        CombatBody.horizontalHitboxGap(player, combat.state.target),
        hittable,
        combat.progressWatchdog.isStalled(),
        combat.state.stallBridge,
        combat.body.isActive(),
        combat.body.isBodyMoving(),
        combat.nav.isOwned(),
        combat.state.tracking,
        combat.progressWatchdog.noProgressTicks(),
        PathfindCommand.isNavigationActive());
  }

  /**
   * Stall recovery may force a direct forward chase even while an A* route is active, but only when
   * the mob is actually walk-reachable from here: within bridge range, roughly level, inside the
   * forward FOV (so it is not a backstep / orbit), and in clear sight.
   */
  private boolean canStallBridge(LocalPlayer player, double dist) {
    if (dist > DIRECT_WALK_RANGE + COMBAT_BRIDGE_EXTRA) {
      return false;
    }
    if (combat.state.target.getY() - player.getY() > DIRECT_CLIMB_MAX) {
      return false;
    }
    if (Math.abs(yawErrorToTarget(player)) > COMBAT_FORWARD_FOV_DEG) {
      return false;
    }
    return CombatTargetController.hasLineOfSight(player, combat.state.target);
  }

  private boolean canHandoffBridge(LocalPlayer player, double distance) {
    if (combat.state.handoffBridgeTicks <= 0 || PathfindCommand.isNavigationActive()) {
      return false;
    }
    if (distance > FLOW_CHASE_RANGE) {
      return false;
    }
    return combat.state.target.getY() - player.getY() <= DIRECT_CLIMB_MAX;
  }

  /**
   * Take (or drop) head ownership for this tick. Every tick handler decides {@code tracking} its
   * own way but the consequences are identical, and they were hand-written five times: a rising
   * edge re-engages the aim ramp, and this tick's answer becomes next tick's edge input. Both live
   * here now.
   */
  private void setTracking(boolean tracking, RyznSettings cfg, double dist, double range) {
    if (tracking && !combat.state.wasTracking) {
      combat.aim.beginTracking(cfg, dist, range);
    }
    combat.state.wasTracking = tracking;
    combat.state.tracking = tracking;
  }

  /**
   * Drop head ownership with no target to measure - no aim ramp to re-engage, so no distance is
   * needed.
   */
  private void stopTracking() {
    combat.state.tracking = false;
    combat.state.wasTracking = false;
  }

  /** Signed yaw error from the player's current facing to the held target's bearing. */
  private double yawErrorToTarget(LocalPlayer player) {
    final double deltaX = combat.state.target.getX() - player.getX();
    final double deltaZ = combat.state.target.getZ() - player.getZ();
    if (deltaX * deltaX + deltaZ * deltaZ < 1.0e-6) {
      return 0.0;
    }
    final float targetYaw = (float) (Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0);
    return Mth.wrapDegrees(targetYaw - player.getYRot());
  }

  private CombatApproach.Approach chooseApproach(
      LocalPlayer player,
      double dist,
      boolean visible,
      double yawAbs,
      boolean recovering,
      boolean hittable) {
    if (transmissionFirst(dist)) {
      return CombatApproach.Approach.FOLLOW_ROUTE;
    }
    if (combat.usesRecordedMovement()) {
      double height = combat.state.target.getY() - player.getY();
      double directRange =
          combat.body.isActive()
              ? RecordedCombatMovement.ENGAGE_EXIT_RANGE
              : RecordedCombatMovement.ENGAGE_RANGE;
      if (dist > directRange
          || height > DIRECT_CLIMB_MAX
          || height < -RecordedCombatMovement.DIRECT_DROP_MAX
          || !visible
          || !player.onGround() && PathfindCommand.isNavigationActive()
          || player.onGround()
              && !(hittable && dist <= RecordedCombatMovement.STOP_RANGE)
              && !PathExecutor.instance.canWalkDirectlyTo(
                  combat.state.target.getX(), combat.state.target.getZ())) {
        return CombatApproach.Approach.FOLLOW_ROUTE;
      }
      return yawAbs > COMBAT_FORWARD_FOV_DEG
          ? CombatApproach.Approach.TURN_FIRST
          : CombatApproach.Approach.WALK_STRAIGHT;
    }
    return CombatApproach.decide(
        dist,
        combat.state.target.getY() - player.getY(),
        yawAbs,
        visible,
        PathfindCommand.isNavigationActive(),
        recovering);
  }

  /**
   * Track the mob (combat owns the head + aim + swing) whenever it is in clear line of sight - near
   * (direct chase) unconditionally, far (executor navigating) once the mob is in the view cone. The
   * executor keeps full strafe navigation while combat owns the head (the old combat strafe-strip
   * that froze the body on a curving route is gone), so the head can stay on the mob through the
   * whole approach, hops included, instead of looking forward midair and only turning at the end.
   * Recorded pursuit retains an acquired head lock through cover; other profiles use sight grace.
   */
  private boolean shouldTrack(LocalPlayer player, double dist, boolean forceEntry) {
    if (transmissionFirst(dist)) {
      return false;
    }
    final double buffer = combat.state.tracking ? ENGAGE_EXIT_BUFFER : 0.0;
    double sightDistance = dist;
    if (combat.usesRecordedMovement()) {
      if (!player.onGround() && !player.isInWater() && !player.onClimbable()) {
        sightDistance = CombatBody.horizontalHitboxGap(player, combat.state.target);
      }
      // The route gaze looks down the path on the approach; the head only turns onto the mob
      // once it is close enough to hit soon.
      double lookRange =
          combat.resolved().attackRange(combat.targets.hasNearbyManagedTarget())
              + RECORDED_LOOK_BUFFER
              + buffer;
      if (sightDistance > lookRange) {
        return false;
      }
      forceEntry = true;
    }
    // Far entry gate: only start tracking a mob already roughly ahead; a mob behind the player is
    // left to
    // the route's own gaze to turn toward. Once tracking, the head is on the mob, so the cone holds
    // itself.
    if (!forceEntry
        && dist > DIRECT_WALK_RANGE + buffer
        && !combat.state.tracking
        && !isWithinFov(player, combat.state.target)) {
      return false;
    }
    if (CombatTargetController.hasLineOfSight(player, combat.state.target)) {
      combat.sightGrace.markVisible(System.nanoTime());
      return true;
    }
    if (combat.usesRecordedMovement()
        && combat.state.tracking
        && combat.sightGrace.hasSeenTarget()
        && dist <= combat.resolved().scanRange()) {
      return true;
    }
    return combat.state.tracking
        && combat.sightGrace.isActive(
            sightDistance,
            combat.usesRecordedMovement() ? combat.resolved().scanRange() : LOS_GRACE_DIST,
            System.nanoTime(),
            LOS_GRACE_MS * 1_000_000L);
  }

  /**
   * True when the bearing to the mob is within {@link #TRACK_FOV_DEG} of the current facing (yaw
   * cone).
   */
  /**
   * A strike-once session with Instant Transmission on leaves the far approach to the pathfinder: a
   * direct chase parks the route and a tracking head blocks the aim, and either one means walking a
   * stretch a hop would have covered. Combat takes over once the mob is inside hop range.
   */
  private boolean transmissionFirst(double dist) {
    if (!combat.managed || !zealotFlow(combat.managedSettings)) {
      return false;
    }
    final var pathfinder = RyznSettings.get().pathfinder();
    final double handover =
        Math.max(
            TRANSMISSION_FIRST_MIN_RANGE + pathfinder.getTransmissionEndBufferBlocks(),
            combat.resolved().attackRange(false) + 2.0);
    return pathfinder.isTransmissionEnabled() && dist > handover;
  }

  /**
   * While a flowing hunt closes in but combat does not hold the head yet, the walking head drifts
   * onto the mob, so the hand-over turn is a small correction rather than a snap onto it.
   */
  private void updateGazeHint(LocalPlayer player, double dist) {
    final boolean leadIn =
        combat.managed
            && !combat.usesRecordedMovement()
            && zealotFlow(combat.managedSettings)
            && combat.state.target != null
            && !combat.state.tracking
            && dist <= PRE_AIM_RANGE + combat.resolved().attackRange(false);
    if (leadIn) {
      PathGazeHint.set(
          combat.state.target.position().add(0.0, combat.state.target.getBbHeight() * 0.6, 0.0));
    } else {
      PathGazeHint.clear();
    }
  }

  private static boolean isWithinFov(LocalPlayer player, LivingEntity mob) {
    final double dx = mob.getX() - player.getX();
    final double dz = mob.getZ() - player.getZ();
    if (dx * dx + dz * dz < 1.0e-6) {
      return true;
    }
    final float toYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    return Math.abs(Mth.wrapDegrees(toYaw - player.getYRot())) <= TRACK_FOV_DEG;
  }

  /**
   * Route the body toward the target's block ({@link CombatNav} decides whether that needs a fresh
   * plan). Consecutive plan failures still hit {@link CombatTargetController#detectPathFailure}'s
   * blacklist, which is what makes the retry loop terminate on a genuinely unreachable mob.
   */
  private void approach(RyznSettings cfg) {
    if (deferAirborneRoute()) {
      return;
    }
    BlockPos tp = combat.state.target.blockPosition();
    if (combat.usesRecordedMovement()) {
      tp = CombatNav.groundDestination(tp);
      if (tp == null) {
        combat.targets.abandonStalledTarget(Minecraft.getInstance().player, cfg);
        return;
      }
    }
    final int moved = Math.max(FAR_REPATH_MOVE, combat.getCombatTargetMoveThreshold());
    combat.nav.planTo(CombatNav.Slot.TARGET, tp, moved, combat.getCombatRepathCooldownTicks());
  }

  /**
   * Follow a managed waypoint patrol when configured; otherwise roam toward a remembered mob
   * cluster using {@link CombatRoam}. Reuses the same pathfinder and repath pacing in both cases.
   */
  private void searchTick(RyznSettings cfg, LocalPlayer player) {
    if (!combat.resolved().searchEnabled()) {
      if (retainsNavigationWhileAwaitingTarget(
          combat.retainNavigationWhileAwaitingTarget, combat.managedSettings)) {
        return;
      }
      combat.nav.release();
      return;
    }
    if (deferAirborneRoute()) {
      return;
    }
    BlockPos dest =
        managedUsesPatrol(combat.managedSettings)
            ? managedPatrolDestination(player)
            : combat.roam.chooseDestination(
                player, combat.resolved().avoidDanger(), combat.resolved().bounds());
    if (dest == null) {
      combat.nav.release();
      return;
    }
    if (combat.usesRecordedMovement() && !managedUsesPatrol(combat.managedSettings)) {
      dest = CombatNav.groundDestination(dest);
      if (dest == null) {
        combat.roam.onSearchPathFailed();
        combat.nav.release();
        return;
      }
    }
    final int moved = Math.max(FAR_REPATH_MOVE, combat.getCombatTargetMoveThreshold());
    combat.nav.planTo(CombatNav.Slot.ROAM, dest, moved, combat.getCombatRepathCooldownTicks());
  }

  private boolean deferAirborneRoute() {
    LocalPlayer player = Minecraft.getInstance().player;
    return combat.usesRecordedMovement()
        && player != null
        && !player.onGround()
        && !player.isInWater()
        && !player.onClimbable();
  }

  /**
   * Walk the managed waypoint loop, parking on the point when the session is waiting there for a
   * spawn.
   */
  private BlockPos managedPatrolDestination(LocalPlayer player) {
    final List<BlockPos> points = combat.managedSettings.patrolPoints();
    final BlockPos destination =
        combat.patrol.waypoint(player.getX(), player.getY(), player.getZ(), points);
    if (destination == null) {
      return null;
    }
    if (!CombatRoam.Patrol.reached(player.getX(), player.getY(), player.getZ(), destination)) {
      return destination;
    }
    if (managedSuppressesTargets(combat.managedSettings)) {
      return null;
    }
    return advanceManagedPatrol();
  }

  /** Step the patrol cursor and drop the plan that pointed at the waypoint we just left. */
  private BlockPos advanceManagedPatrol() {
    final BlockPos next = combat.patrol.advance(combat.managedSettings.patrolPoints());
    combat.nav.forgetPlan();
    return next;
  }

  private void detectSearchPathFailure() {
    final int seq = PathfindCommand.getNavFailSeq();
    if (combat.pathFailures.consumeIfChanged(seq)) {
      if (managedUsesPatrol(combat.managedSettings)) {
        advanceManagedPatrol();
      } else {
        // Drop an unreachable patrol hop immediately so the next tick can chain a different route.
        combat.roam.onSearchPathFailed();
      }
    }
  }

  private void patrolAndAttackTick(
      Minecraft mc,
      LocalPlayer player,
      RyznSettings cfg,
      double dist,
      double range,
      boolean clustered) {
    combat.progressWatchdog.clearStalled();
    combat.state.stallBridge = false;
    combat.body.stop();
    boolean visible = shouldTrack(player, dist, false);
    boolean abilityAimWindow =
        combat.managedSettings.attackMode() != ManagedAttackMode.CLUSTERED_USE_HELD_ITEM
            || CombatAbility.shouldAim(
                combat.state.abilityDelay,
                combat.attack.ability().isPrepared(),
                combat.attack.ability().usesSwap(combat.managedSettings));
    setTracking(visible && abilityAimWindow, cfg, dist, range);

    searchTick(cfg, player);
    detectSearchPathFailure();
    if (combat.state.tracking || combat.attack.ability().isPrepared()) {
      combat.attack.swing(
          mc,
          player,
          combat.resolved(),
          combat.state,
          dist,
          range,
          clustered,
          combat.telemetry,
          combat::suppressManagedTargetGroup);
    } else {
    }
  }

  private void holdMeleeInRange(
      Minecraft mc,
      LocalPlayer player,
      RyznSettings cfg,
      double dist,
      double range,
      boolean clustered) {
    combat.resetProgressWatchdog();
    combat.body.stop();
    combat.nav.release();
    if (mc.options != null) {
      MacroInput.releaseMovementKeys(mc.options);
    }
    setTracking(combat.state.target != null && combat.state.target.isAlive(), cfg, dist, range);
    if (combat.state.tracking) {
      combat.attack.swing(
          mc,
          player,
          combat.resolved(),
          combat.state,
          dist,
          range,
          clustered,
          combat.telemetry,
          combat::suppressManagedTargetGroup);
    }
  }

  private void stationaryMeleeTick(Minecraft mc, LocalPlayer player, RyznSettings cfg) {
    combat.resetProgressWatchdog();

    if (combat.state.target == null) {
      combat.body.stop();
      combat.nav.release();
      if (mc.options != null) {
        MacroInput.releaseMovementKeys(mc.options);
      }
      combat.state.bossInMeleeBand = false;
      combat.body.resetBossFootwork();
      stopTracking();
      return;
    }

    final double dist = player.distanceTo(combat.state.target);
    final double range = combat.managedSettings.attackRange();
    final boolean visible = CombatTargetController.hasLineOfSight(player, combat.state.target);
    final boolean inMeleeBand = dist <= range && visible;

    if (!inMeleeBand) {
      if (combat.state.bossInMeleeBand && mc.options != null) {
        MacroInput.releaseMovementKeys(mc.options);
      }
      combat.state.bossInMeleeBand = false;
      combat.body.resetBossFootwork();
      if (mc.options != null) {
        MacroKeys.set(mc.options.keyShift, false);
      }

      final boolean directApproach =
          visible
              && dist <= FLOW_CHASE_RANGE
              && combat.state.target.getY() - player.getY() <= DIRECT_CLIMB_MAX;
      setTracking(shouldTrack(player, dist, directApproach), cfg, dist, range);
      if (directApproach) {
        combat.nav.release();
        combat.body.chase(
            player,
            combat.state.target,
            range,
            false,
            yawErrorToTarget(player),
            true,
            FLOW_CHASE_RANGE,
            combat.telemetry);
      } else {
        combat.body.stop();
        approach(cfg);
      }
      return;
    }

    combat.state.bossInMeleeBand = true;
    combat.body.stop();
    combat.nav.release();
    setTracking(combat.state.target.isAlive(), cfg, dist, range);
    if (combat.state.tracking) {
      combat.body.bossStep(mc, player, dist, range);
      combat.attack.swing(
          mc,
          player,
          combat.resolved(),
          combat.state,
          dist,
          range,
          false,
          combat.telemetry,
          combat::suppressManagedTargetGroup);
    }
  }
}
