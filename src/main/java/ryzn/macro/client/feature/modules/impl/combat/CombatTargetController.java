package ryzn.macro.client.feature.modules.impl.combat;

import static ryzn.macro.client.feature.modules.impl.combat.CombatModule.managedKeepsForwardFlow;
import static ryzn.macro.client.feature.modules.impl.combat.CombatModule.managedSuppressesTargets;
import static ryzn.macro.client.feature.modules.impl.combat.CombatModule.managedUsesPatrol;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.modules.impl.combat.CombatModule.ManagedAttackMode;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.feature.pathfinder.path.PathExecutor;

/**
 * @description Target selection, retention, group suppression and target handoffs.
 */
final class CombatTargetController {
  private final CombatModule combat;

  CombatTargetController(CombatModule combat) {
    this.combat = combat;
  }

  /**
   * Extra blocks past scan range before a LOCKED target is dropped, so a mob sitting right at the
   * scan edge does not flicker lost/reacquired as it shuffles across the boundary (each flicker
   * tore the route down).
   */
  private static final double RETAIN_RANGE_BUFFER = 4.0;

  private static final double RECORDED_SWITCH_COST_MIN = 1.5;
  private static final double RECORDED_SWITCH_COST_MAX = 3.0;
  private static final double RECORDED_SWITCH_COST_SHARE = 0.15;

  /**
   * While still APPROACHING, a visible rival must beat the held target's cost by this much to take
   * over (cost is dist*10 + angle*0.5, so 40 is roughly "4 blocks closer"). Big enough that two
   * similar mobs never flip-flop; small enough that a zombie spawning next to the bot interrupts a
   * cross-map walk.
   */
  private static final double UPGRADE_MARGIN = 40.0;

  /**
   * Consecutive failed paths to a single target before it is blacklisted and selection moves on.
   */
  private static final int MAX_PATH_FAILURES = 3;

  /** How long a target stays blacklisted after too many failed paths. */
  private static final long TARGET_BLACKLIST_MS = 10_000L;

  /**
   * A rival already inside attack range and at least this many blocks closer than the held target
   * takes over immediately, even mid-fight, so the bot fights the threat on top of it rather than a
   * farther mob.
   */
  private static final double MELEE_TAKEOVER_MARGIN = 1.5;

  /**
   * Short blacklist for a target abandoned for stalling (well under the unreachable-path {@link
   * #TARGET_BLACKLIST_MS}).
   */
  private static final long STALL_BLACKLIST_MS = 4_000L;

  /**
   * Target commitment: once locked, hold a target for this many ticks (~0.7s) before a cost-based
   * upgrade may switch off it, so the selection does not churn between near-equal zombies. Death /
   * invalid / stall / a hittable melee threat still switch immediately.
   */
  private static final int TARGET_COMMIT_TICKS = 14;

  private static final int HANDOFF_BRIDGE_TICKS = 4;

  private static final double CLUSTER_RANGE_SQ = 4.0 * 4.0;

  private static final int BOL_MAX_TARGETS = 5;

  private static final long BOL_ASSUMED_DEAD_MS = 1_500L;

  private static final int STRIKE_ONCE_REVISIT_SECONDS = 30;

  /** Ticks a strike may take to show on the mob before it counts as a miss and is retried. */
  private static final int STRIKE_CONFIRM_TICKS = 6;

  /**
   * Repeated-stall fallback: stop staring - blacklist this mob briefly, seed the roam memory,
   * release the body and stop the route, and clear the target so {@link #selectTarget} takes the
   * next visible mob next tick.
   */
  void abandonStalledTarget(LocalPlayer player, RyznSettings cfg) {
    DebugLog.trace(
        DebugLog.Category.MACRO,
        "Combat: stuck on %s - picking a different target",
        combat.state.target.getName().getString());
    combat.targeting.blacklist(combat.state.target.getUUID(), STALL_BLACKLIST_MS);
    if (shouldRememberTargets(cfg)) {
      combat.roam.remember(
          combat.state.target.getX(),
          combat.state.target.getY(),
          combat.state.target.getZ(),
          combat.resolved().avoidDanger());
    }
    combat.state.target = null;
    combat.pathFailures.resetCount();
    combat.state.tracking = false;
    combat.state.wasTracking = false;
    combat.resetProgressWatchdog();
    combat.body.stop();
    combat.nav.release();
  }

  static boolean hasLineOfSight(LocalPlayer player, LivingEntity mob) {
    return CombatSight.canSee(player, mob);
  }

  static boolean hasLineOfSight(LivingEntity from, LivingEntity to) {
    return CombatSight.canSee(from, to);
  }

  /**
   * Watch the pathfinder's no-path signal: when a search to the CURRENT target fails {@link
   * #MAX_PATH_FAILURES} times in a row, drop and briefly blacklist it (and seed the search memory
   * with its spot - a reachable sibling is usually nearby), so selection moves on instead of the
   * bot wedging against an unreachable mob.
   */
  void detectPathFailure(RyznSettings cfg) {
    final int seq = WalkingNavigation.getNavFailSeq();
    if (!combat.pathFailures.record(
        seq, combat.state.target != null && combat.nav.isOwned(), MAX_PATH_FAILURES)) {
      return;
    }

    DebugLog.trace(
        DebugLog.Category.MACRO,
        "Combat: cannot get to %s - ignoring it for a while",
        combat.state.target.getName().getString());
    combat.targeting.blacklist(combat.state.target.getUUID(), TARGET_BLACKLIST_MS);
    if (shouldRememberTargets(cfg)) {
      combat.roam.remember(
          combat.state.target.getX(),
          combat.state.target.getY(),
          combat.state.target.getZ(),
          combat.resolved().avoidDanger());
    }
    combat.state.target = null;
    combat.pathFailures.resetCount();
    combat.nav.release();
  }

  boolean hasNearbyManagedTarget() {
    return !managedTargetGroup(combat.state.target).isEmpty();
  }

  private List<LivingEntity> managedTargetGroup(LivingEntity seed) {
    if (!combat.managed
        || seed == null
        || !seed.isAlive()
        || combat.managedSettings.attackMode() != ManagedAttackMode.CLUSTERED_USE_HELD_ITEM
        || combat.targeting.isSuppressed(seed.getUUID())
        || !(seed.level() instanceof ClientLevel level)) {
      return List.of();
    }
    List<LivingEntity> candidates = new ArrayList<>();
    for (Entity entity : level.entitiesForRendering()) {
      if (!(entity instanceof LivingEntity nearby)
          || !nearby.isAlive()
          || combat.targeting.isSuppressed(nearby.getUUID())
          || !combat.targeting.filter.isHostile(nearby)) {
        continue;
      }
      candidates.add(nearby);
    }
    return connectedGroup(
        seed,
        candidates,
        BOL_MAX_TARGETS,
        (left, right) ->
            left.distanceToSqr(right) <= CLUSTER_RANGE_SQ && hasLineOfSight(left, right));
  }

  private LivingEntity closestManagedGroupTarget(
      LocalPlayer player, ClientLevel level, double maxRangeSq) {
    LivingEntity closest = null;
    double closestDistance = Double.POSITIVE_INFINITY;
    for (Entity entity : level.entitiesForRendering()) {
      if (!(entity instanceof LivingEntity candidate)
          || !combat.targeting.accepts(player, candidate, maxRangeSq)
          || !combat.targeting.seen(player, candidate)
          || managedTargetGroup(candidate).isEmpty()
          || !patrolTargetAhead(player, candidate)) {
        continue;
      }
      double distance = player.distanceToSqr(candidate);
      if (distance < closestDistance) {
        closest = candidate;
        closestDistance = distance;
      }
    }
    return closest;
  }

  void suppressManagedTargetGroup() {
    List<LivingEntity> group = managedTargetGroup(combat.state.target);
    for (LivingEntity member : group) {
      combat.targeting.suppress(member.getUUID(), BOL_ASSUMED_DEAD_MS);
    }
    DebugLog.trace(
        DebugLog.Category.MACRO,
        "Combat: Bonzo's Staff hit %d Crypt Ghouls - counting them as dead",
        group.size());
  }

  static <T> List<T> connectedGroup(
      T seed, List<T> candidates, int limit, BiPredicate<T, T> connected) {
    if (seed == null || limit <= 0) {
      return List.of();
    }
    List<T> group = new ArrayList<>();
    group.add(seed);
    for (int index = 0; index < group.size() && group.size() < limit; index++) {
      T current = group.get(index);
      for (T candidate : candidates) {
        if (group.size() >= limit) {
          break;
        }
        if (!group.contains(candidate) && connected.test(current, candidate)) {
          group.add(candidate);
        }
      }
    }
    return List.copyOf(group);
  }

  private boolean patrolTargetAhead(LocalPlayer player, LivingEntity candidate) {
    if (!managedUsesPatrol(combat.managedSettings)) {
      return true;
    }
    Vec3 next = PathExecutor.instance.getCurrentMovementTarget();
    if (next == null || player.distanceToSqr(next) < 0.25) {
      final BlockPos point =
          combat.patrol.lookahead(
              player.getX(), player.getY(), player.getZ(), combat.managedSettings.patrolPoints());
      if (point == null) {
        return true;
      }
      next = new Vec3(point.getX() + 0.5, point.getY(), point.getZ() + 0.5);
    }
    return CombatRoam.Patrol.targetAhead(
        player.getX(), player.getZ(), next.x, next.z, candidate.getX(), candidate.getZ());
  }

  /**
   * Re-evaluate the best target - runs EVERY tick, held target or not, so detection never pauses.
   * Sticky once locked: the target is kept (even through a brief loss of sight, so a mob ducking
   * behind a gravestone is still chased rather than dropped) until it dies / despawns / leaves
   * range / is blacklisted - but the field is still re-scanned each tick and a clearly-better
   * visible mob takes over mid-approach ({@link #maybeUpgradeTarget}). When none is held, prefer
   * the lowest-cost mob in clear sight; if nothing is visible, fall back to the nearest mob in
   * range and APPROACH it (A* routes around cover) so the bot closes in to regain sight instead of
   * standing still.
   */
  void selectTarget(LocalPlayer player, ClientLevel level, RyznSettings cfg) {
    if (combat.state.struck != null) {
      // Only a hit the mob actually shows (hurt flash, or dead) moves on; a whiff swings again.
      final LivingEntity struck = combat.state.target;
      final boolean landed =
          struck == null
              || !combat.state.struck.equals(struck.getUUID())
              || struck.hurtTime > 0
              || struck.isDeadOrDying();
      if (landed) {
        combat.targeting.suppress(combat.state.struck, STRIKE_ONCE_REVISIT_SECONDS * 1_000L);
        combat.state.struck = null;
        combat.state.struckWaitTicks = 0;
      } else if (++combat.state.struckWaitTicks > STRIKE_CONFIRM_TICKS) {
        combat.state.struck = null;
        combat.state.struckWaitTicks = 0;
      }
    }
    if (managedSuppressesTargets(combat.managedSettings)) {
      combat.state.target = null;
      return;
    }
    final int scanRange = combat.resolved().scanRange();
    final double maxRangeSq = (double) scanRange * scanRange;
    final double keepRange = scanRange + RETAIN_RANGE_BUFFER;
    final double keepRangeSq = keepRange * keepRange;

    if (combat.managed
        && combat.managedSettings.attackMode() == ManagedAttackMode.CLUSTERED_USE_HELD_ITEM) {
      if (combat.attack.ability().isPrepared()
          && combat.state.target != null
          && combat.state.target.isAlive()) {
        return;
      }
      boolean currentGroupVisible =
          combat.state.target != null
              && combat.targeting.accepts(player, combat.state.target, maxRangeSq)
              && combat.targeting.seen(player, combat.state.target)
              && !managedTargetGroup(combat.state.target).isEmpty()
              && patrolTargetAhead(player, combat.state.target);
      LivingEntity groupedTarget =
          currentGroupVisible
              ? combat.state.target
              : closestManagedGroupTarget(player, level, maxRangeSq);
      if (groupedTarget == null) {
        combat.state.target = null;
        combat.nav.forgetPlan();
        combat.pathFailures.resetCount();
        return;
      }
      if (groupedTarget != combat.state.target) {
        if (combat.state.target == null) {
          setTarget(player, groupedTarget, CombatTargeting.cost(player, groupedTarget));
        } else {
          replaceTarget(player, cfg, groupedTarget, CombatTargeting.cost(player, groupedTarget));
        }
      }
      return;
    }

    if (combat.usesRecordedMovement()) {
      LivingEntity closest = combat.targetSelector.best(player, level, maxRangeSq, false);
      LivingEntity held = combat.state.target;
      if (closest != null
          && closest != held
          && held != null
          && combat.targeting.accepts(player, held, maxRangeSq)
          && player.distanceTo(held) - player.distanceTo(closest)
              < Math.clamp(
                  player.distanceTo(held) * RECORDED_SWITCH_COST_SHARE,
                  RECORDED_SWITCH_COST_MIN,
                  RECORDED_SWITCH_COST_MAX)) {
        return;
      }
      if (closest != combat.state.target) {
        if (closest == null) {
          LivingEntity lost = combat.state.target;
          combat.state.target = null;
          combat.nav.forgetPlan();
          combat.pathFailures.resetCount();
          combat.pathFailures.consumeIfChanged(WalkingNavigation.getNavFailSeq());
          if (shouldRememberTargets(cfg)) {
            combat.roam.remember(
                lost.getX(), lost.getY(), lost.getZ(), combat.resolved().avoidDanger());
          }
        } else {
          setTarget(player, closest, player.distanceTo(closest) * 10.0);
        }
      }
      return;
    }

    // Sticky targeting: hold the current mob until it is genuinely gone (dead / despawned / out of
    // range /
    // blacklisted). The sightless validity here is deliberate - dropping a target the instant a
    // wall breaks
    // sight is what left the bot idle ("can't detect any entity"); a still-present mob keeps being
    // chased.
    // Retention uses a small range buffer past the scan edge so a boundary mob doesn't flicker.
    // Detection
    // itself never pauses: the held target is re-scored against the field every tick
    // (maybeUpgradeTarget).
    boolean haveLostSpot = false;
    double lostX = 0.0;
    double lostY = 0.0;
    double lostZ = 0.0;
    if (combat.state.target != null) {
      if (combat.targeting.accepts(player, combat.state.target, keepRangeSq)) {
        maybeUpgradeTarget(player, level, cfg, maxRangeSq);
        return;
      }
      DebugLog.trace(
          DebugLog.Category.MACRO,
          "Combat: lost track of %s",
          combat.state.target.getName().getString());
      // Stash where it was (death spot, or where it fled to) but DON'T seed the roam memory yet: if
      // a live
      // mob is reacquired this same tick we keep fighting instead of remembering a spot we never
      // visit.
      lostX = combat.state.target.getX();
      lostY = combat.state.target.getY();
      lostZ = combat.state.target.getZ();
      haveLostSpot = true;
      combat.state.target = null;
      combat.nav.forgetPlan();
      combat.pathFailures.resetCount();
      // Consume any failure of the dead target's still-resolving plan, so it cannot be
      // misattributed to
      // the roam that starts next tick (it was deleting the freshly chosen search spot).
      combat.pathFailures.consumeIfChanged(WalkingNavigation.getNavFailSeq());
    }

    // Instant reacquire: select the next valid mob THIS tick. Prefer the best mob in clear sight;
    // if none is
    // visible, fall back to the nearest mob in range (sightless) and approach it so the bot walks
    // in to regain
    // sight rather than standing still. Only with nothing live to take do we seed the roam memory
    // with the
    // lost spot, so the bot walks back and re-acquires instead of idling (hazard spots are skipped
    // inside).
    LivingEntity best = combat.targetSelector.best(player, level, maxRangeSq, true);
    if (best == null) {
      best = combat.targetSelector.best(player, level, maxRangeSq, false);
    }
    if (best != null) {
      setTarget(player, best, CombatTargeting.cost(player, best));
    } else if (haveLostSpot && shouldRememberTargets(cfg)) {
      combat.roam.remember(lostX, lostY, lostZ, combat.resolved().avoidDanger());
    }
  }

  /**
   * Sticky switching while the held mob is still alive, with one melee exception. Normally the held
   * target is kept through tracking and the direct chase (the aim and close-range body commitment
   * must never snap between mobs), and the only other live-target switch is mid-approach (not
   * tracking, not direct-chasing) when the held mob has dropped out of sight past the grace AND a
   * visible rival is clearly cheaper (beats it by {@link #UPGRADE_MARGIN}) - so the bot doesn't
   * keep marching at a wall while a killable mob stands in the open. The exception: a rival already
   * inside attack range and clearly closer than the held target takes over immediately, even
   * mid-fight, so the bot fights the threat on top of it. Dead-target replacement is instant in
   * {@link #selectTarget}, not here.
   */
  private void maybeUpgradeTarget(
      LocalPlayer player, ClientLevel level, RyznSettings cfg, double maxRangeSq) {
    final LivingEntity rival = combat.targetSelector.best(player, level, maxRangeSq, true);
    if (rival == null || rival == combat.state.target) {
      return;
    }

    // Melee takeover: a much closer threat that has walked into attack range overrides stickiness.
    final double rivalDist = player.distanceTo(rival);
    double attackRange = combat.resolved().attackRange(hasNearbyManagedTarget());
    if (rivalDist <= attackRange
        && rivalDist < player.distanceTo(combat.state.target) - MELEE_TAKEOVER_MARGIN) {
      replaceTarget(player, cfg, rival, CombatTargeting.cost(player, rival));
      return;
    }

    // Stall override: while stuck staring at a mob we cannot close on (tracking but not hittable
    // for a while),
    // allow switching to a clearly better visible rival even though we are "engaged" - that is the
    // recovery.
    final boolean stallSwitch =
        combat.progressWatchdog.isStalled()
            && !CombatAim.isHittable(player, combat.state.target, attackRange);
    if (combat.state.commitTicks > 0 && !stallSwitch) {
      return;
    } // committed: hold this target briefly so selection doesn't churn
    if ((combat.state.tracking || combat.body.isActive()) && !stallSwitch) {
      return;
    }
    if (!stallSwitch) {
      final boolean heldUnseen =
          combat.resolved().requireSight()
              && !combat.sight.isVisibleOrRecent(player, combat.state.target);
      if (!heldUnseen) {
        return;
      } // still see the held mob and not stalled: stay on it
    }
    final double heldCost = CombatTargeting.cost(player, combat.state.target);
    final double rivalCost = CombatTargeting.cost(player, rival);
    final double margin =
        UPGRADE_MARGIN
            + combat.getCombatHysteresisMargin(); // config hysteresis on top of the base margin
    if (rivalCost >= heldCost - margin) {
      return;
    } // rival not clearly better
    replaceTarget(player, cfg, rival, rivalCost);
  }

  /** Seed the roam memory with the outgoing target's spot, then lock onto the replacement. */
  private void replaceTarget(
      LocalPlayer player, RyznSettings cfg, LivingEntity rival, double rivalCost) {
    if (shouldRememberTargets(cfg)) {
      combat.roam.remember(
          combat.state.target.getX(),
          combat.state.target.getY(),
          combat.state.target.getZ(),
          combat.resolved().avoidDanger());
    }
    setTarget(player, rival, rivalCost);
  }

  private void setTarget(LocalPlayer player, LivingEntity newTarget, double cost) {
    combat.state.target = newTarget;
    combat.nav.forgetPlan(); // force a fresh plan
    combat.pathFailures.resetCount();
    combat.sightGrace.reset(); // require a fresh clear sight before the head locks onto the new mob
    // Recorded combat hands a held head lock straight to the next mob. Dropping it until the new
    // mob is in clear sight let the route gaze swing the head toward the path for a few ticks, then
    // swing back once the mob showed - the stutter after a kill.
    if (combat.usesRecordedMovement()
        && (combat.state.tracking || combat.state.lookHandoffTicks > 0)) {
      combat.sightGrace.markVisible(System.nanoTime());
      combat.state.tracking = true;
      combat.state.lookHandoffTicks = 0;
    }
    combat.state.commitTicks =
        TARGET_COMMIT_TICKS; // hold this target briefly so selection does not churn between
    // near-equal mobs
    // Keep forward movement flowing across the handoff: do NOT stop the body (that read as a
    // stutter after every
    // kill). Only force-release S so a stale key can never read as a backstep; the next tick
    // re-derives clean
    // forward keys toward the new target (its face gate rotates first if the mob is off to the
    // side).
    combat.body.clearObstruction();
    combat.state.handoffBridgeTicks = HANDOFF_BRIDGE_TICKS;
    CombatBody.releaseBackpedal();
    combat
        .resetProgressWatchdog(); // fresh engagement: start the stall checkpoint from this target's
    // distance
    RyznSettings settings = RyznSettings.get();
    combat.aim.targetChanged(
        player, newTarget, settings, combat.resolved().attackRange(hasNearbyManagedTarget()));
    combat.roam.onTargetAcquired();
    // The detected entity is a debug detail, not a general announcement - keep it behind /ryzn
    // debug.
    DebugLog.trace(
        DebugLog.Category.MACRO,
        "Combat: going for %s (score %.1f)",
        newTarget.getName().getString(),
        cost);
  }

  private boolean shouldRememberTargets(RyznSettings cfg) {
    return combat.resolved().searchEnabled()
        && !managedKeepsForwardFlow(combat.managedSettings)
        && !managedUsesPatrol(combat.managedSettings);
  }
}
