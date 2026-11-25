package ryzn.macro.client.feature.pathfinder.path;

import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.CRAWL_SPEED_FLOOR;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.DRIFT_PATIENCE_TICKS;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.FREE_TICKS_TO_CLEAR;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.HOP_COOLDOWN_TICKS;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.HOP_FAIL_MIN_TRIES;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.HOP_FAIL_TIMEOUT_MS;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.HOP_LANDED_RISE;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.HOP_LANDED_TRAVEL;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.OFF_PATH_CAP;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.PROGRESS_MIN_BLOCKS;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.STUCK_AVOID_PENALTY;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.STUCK_AVOID_RADIUS;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.STUCK_AVOID_TTL_SEARCHES;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.STUCK_REPLANS_BEFORE_AVOID;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.VERT_PIN_REPLAN_TICKS;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.WATER_PROGRESS_MIN_BLOCKS;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.WATER_PROGRESS_STALL_MS;
import static ryzn.macro.client.feature.pathfinder.path.PathExecutor.WEDGE_TIMEOUT_MS;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.groups.WalkingSettings.Control;
import ryzn.pathfinding.PathManager;

/**
 * @description Runs the existing movement-stall, hop and drift watches against the executor state.
 */
final class PathStallWatch {

  private final PathExecutor executor;

  PathStallWatch(PathExecutor executor) {
    this.executor = executor;
  }

  void tickWedgeWatch(boolean isTryingToMove, boolean waterControlled) {
    // Water legitimately reads sub-crawl-speed (high drag + slow swim), so the speed wedge would
    // false-fire mid-crossing and replan on top of a good route. The swim-jump above carries the
    // body
    // across, so stand the wedge watch down while the water controller is driving.
    //
    // Keyed on the CONTROLLER, not on player.isInWater(). The raw flag flickers - a body bobbing at
    // a
    // bank leaves the fluid for a tick roughly one tick in three - and every flicker reset the
    // clock, so
    // the watch armed and disarmed forever without ever maturing on a stall that lasted 40+
    // seconds. The
    // controller has hysteresis (see WaterMovementController's exit carry); the raw flag has none.
    if (!isTryingToMove || waterControlled) {
      if (executor.wedgeClock.isScheduled()) {
        executor.wedgeClock.reset();
      }
      executor.movingStreak = 0;
      return;
    }

    double speedNow =
        Math.hypot(
            executor.client.player.getDeltaMovement().x,
            executor.client.player.getDeltaMovement().z);

    if (speedNow < CRAWL_SPEED_FLOOR) {
      executor.movingStreak = 0;

      if (Math.abs(executor.client.player.getDeltaMovement().y) > 0.1) {
        if (executor.wedgeClock.isScheduled()) {
          executor.wedgeClock.reset();
        }
        return;
      }

      if (executor.gazePlanner.trailClimbsAhead(executor.trail, executor.trailCursor)) {
        if (executor.wedgeClock.isScheduled()) {
          executor.wedgeClock.reset();
        }
        return;
      }

      boolean hasBlockNearby = false;
      try {
        if (executor.client.level != null) {
          int hpx = (int) Math.floor(executor.client.player.getX());
          int py = (int) Math.floor(executor.client.player.getY());
          int hpz = (int) Math.floor(executor.client.player.getZ());
          int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
          outer:
          for (int offsetY = 0; offsetY <= 1; offsetY++) {
            for (int[] off : offsets) {
              BlockPos probePos = new BlockPos(hpx + off[0], py + offsetY, hpz + off[1]);
              BlockState phase = executor.client.level.getBlockState(probePos);
              if (!phase.getCollisionShape(executor.client.level, probePos).isEmpty()) {
                hasBlockNearby = true;
                break outer;
              }
            }
          }
        }
      } catch (Exception ignored) {
        // Without a confirmed nearby obstacle, do not arm wedge recovery from a failed query.
      }

      if (!hasBlockNearby) {
        if (executor.wedgeClock.isScheduled()) {
          executor.wedgeClock.reset();
        }
        return;
      }

      if (!executor.wedgeClock.isScheduled()) {
        executor.wedgeClock.schedule(WEDGE_TIMEOUT_MS);
        PathExecutor.detail("wedge watch armed (spd=%.4f < %.4f)", speedNow, CRAWL_SPEED_FLOOR);
      }
    } else {
      executor.movingStreak++;
      if (executor.movingStreak >= FREE_TICKS_TO_CLEAR) {
        if (executor.wedgeClock.isScheduled()) {
          executor.wedgeClock.reset();
        }
        executor.movingStreak = 0;
      }
    }
  }

  /**
   * Vertical-divergence watch. The two gates in {@link PathExecutor#stepCursorForward} pin the
   * cursor while a step is still being made, but they have no opinion about a body that has left
   * the route's height for good. Auto-step can carry the bot UP onto a slab the route meant to walk
   * past: the live node then reads as a ledge drop that will never be taken, and because the ledge
   * aim extension shares LEDGE_DROP_MIN_Y with the pin, a body hovering on that threshold flips its
   * heading between the node and the extension every tick and shuffles in place. Seen in-game at
   * ~1.5s per occurrence, ended only by the flat progress watch. There is no local repair for "I am
   * not at my route's height" - the route is simply stale - so ask for a fresh one from where the
   * body actually is.
   *
   * <p>Accelerator only: on an exhausted replan budget this returns false and leaves the give-up
   * decision to {@link #tickProgressWatch}, so it can never turn a recoverable stall into a bail.
   * ponytail: one counter, no Y bookkeeping - a gate held this long is stuck whatever the reason.
   */
  boolean tickVerticalPinWatch(boolean isTryingToMove) {
    if (!isTryingToMove || executor.vertPinTicks < VERT_PIN_REPLAN_TICKS) {
      return false;
    }
    executor.vertPinTicks = 0;
    return executor.askReplan(
        String.format(
            "off route vertically (%s pin, no closing for %d ticks, dY=%.2f gap=%.2f)",
            executor.dropPinned ? "drop" : "climb",
            VERT_PIN_REPLAN_TICKS,
            executor.client.player.getY()
                - executor.trailFootY(Math.min(executor.trailCursor, executor.trail.size() - 1)),
            executor.vertPinGap),
        "falling behind the route");
  }

  /**
   * Net-displacement stuck watch (jump-boost-proof). Replans when the body fails to translate
   * {@link PathExecutor#PROGRESS_MIN_BLOCKS} in XZ within {@link PathExecutor#PROGRESS_STALL_MS} of
   * the last progress anchor. Unlike the speed wedge it ignores instantaneous velocity, so it still
   * fires when a high server jump-boost has the player bouncing in place against a wall (large
   * vertical speed, zero forward progress) - the case where the executor "thinks it's moving" while
   * just hugging geometry it can't get past. The anchor jumps forward every time the player covers
   * the threshold, so steady movement keeps restarting the window and never trips it. Returns true
   * if it suspended/stopped us (the caller returns).
   */
  boolean tickProgressWatch(boolean isTryingToMove, boolean waterControlled) {
    // Deliberately NOT water-exempt, unlike tickWedgeWatch. The wedge keys off instantaneous speed,
    // which
    // water drags below the floor even mid-successful-swim, so it MUST stand down in water. This
    // watch keys
    // off NET translation: a real swim clears the 2-block threshold well inside the window and
    // re-anchors,
    // while a flatlined bob at a bank does not - which is exactly the water stall we WANT to
    // rescue. So the
    // asymmetry is correct; do not add an inWater stand-down here.
    if (!isTryingToMove) {
      executor.progressClock.reset();
      return false;
    }
    double minimumProgress = waterControlled ? WATER_PROGRESS_MIN_BLOCKS : PROGRESS_MIN_BLOCKS;
    long stallWindow =
        waterControlled
            ? WATER_PROGRESS_STALL_MS
            : RyznSettings.get().pathfinder().walking().get(Control.STALL_TIME);
    if (!executor.progressClock.isScheduled()) {
      anchorProgress(stallWindow);
      return false;
    }
    double moved =
        PathExecutor.flatDist(
            executor.client.player.getX(),
            executor.client.player.getZ(),
            executor.progressAnchorX,
            executor.progressAnchorZ);
    if (moved >= minimumProgress) {
      anchorProgress(stallWindow); // advanced past the threshold - restart the window from here
      clearStuckReplans(); // we are moving again; the deadlock streak is over
      return false;
    }
    if (executor.progressClock.passed()) {
      // Pull a fresh route from here; only give up for real once the replan budget is gone, so an
      // unreachable goal cannot loop forever (askReplan / stop both clear progressClock).
      if (noteStuckReplan()) {
        executor.error(
            "Stuck at %s through %d reroutes - no safe way on from here, stopping",
            executor.client.player.blockPosition().toShortString(), executor.stuckReplansHere);
        executor.bailed = true;
        executor.won = false;
        executor.stop();
        return true;
      }
      if (executor.askReplan(
          String.format("no XZ progress (%.1f<%.1f in %dms)", moved, minimumProgress, stallWindow),
          "no forward progress")) {
        return true;
      }
      executor.error(
          "Not getting anywhere and out of retries - stopping (moved %.1f blocks)", moved);
      executor.bailed = true;
      executor.won = false;
      executor.stop();
      return true;
    }
    return false;
  }

  /** Snap the progress anchor to the current XZ and (re)arm the stall window. */
  void anchorProgress(long timeoutMs) {
    executor.progressAnchorX = executor.client.player.getX();
    executor.progressAnchorZ = executor.client.player.getZ();
    executor.progressClock.schedule(timeoutMs);
  }

  /**
   * Give a repeated no-progress replan something new to say.
   *
   * <p>A replan re-runs A* from where the body is. If the body cannot leave that spot, the world
   * has not changed and the search answers with the route it just gave - so the loop is silent,
   * costs the whole replan budget for nothing, and the bot stands there (logged at a flooded bank:
   * 78/121 -> 79/122 -> 78/121 for 40+ seconds). Counting them per CELL is the tell: a replan that
   * moved us is progress and resets, a second one from the same block is a deadlock. At that point
   * poison the node we are failing to reach, so the next search has to route around whatever is
   * holding us rather than re-proposing it.
   *
   * <p>Deliberately the NODE, not the body's own cell: avoiding where we stand would just make the
   * search refuse to start from here. TTL is in searches, so the penalty ages out on its own once
   * we are moving.
   */
  boolean noteStuckReplan() {
    BlockPos here = executor.client.player.blockPosition();
    if (executor.stuckReplanCell == null || here.distSqr(executor.stuckReplanCell) > 4) {
      executor.stuckReplanCell = here;
      executor.stuckReplans = 0;
      executor.stuckReplansHere = 0;
    }
    // Poisoning the failing node already made the search route differently several times; more of
    // the same from one spot only spends the trip's whole replan budget standing still.
    if (++executor.stuckReplansHere
        >= RyznSettings.get().pathfinder().walking().get(Control.REPLAN_LIMIT)) {
      return true;
    }
    if (++executor.stuckReplans < STUCK_REPLANS_BEFORE_AVOID) {
      return false;
    }
    executor.stuckReplans = 0;
    if (executor.trailCursor < 0 || executor.trailCursor >= executor.trail.size()) {
      return false;
    }
    Vec3 blocked = executor.trail.get(executor.trailCursor);
    int bx = (int) Math.floor(blocked.x);
    int by = (int) Math.floor(executor.trailFootY(executor.trailCursor));
    int bz = (int) Math.floor(blocked.z);
    if (here.getX() == bx && here.getZ() == bz) {
      return false;
    } // never poison the cell we are standing in
    executor.log(
        "stuck at %s - avoiding unreachable node (%d,%d,%d) for the next %d searches",
        here.toShortString(), bx, by, bz, STUCK_AVOID_TTL_SEARCHES);
    PathManager.addTransientAvoidPoint(
        bx, by, bz, STUCK_AVOID_RADIUS, STUCK_AVOID_PENALTY, STUCK_AVOID_TTL_SEARCHES);
    return false;
  }

  void clearStuckReplans() {
    executor.stuckReplans = 0;
    executor.stuckReplansHere = 0;
    executor.stuckReplanCell = null;
  }

  void tickHopFailsafe(boolean shouldJump, boolean onGround) {
    if (shouldJump) {
      if (!executor.hopClock.isScheduled()) {
        executor.hopClock.schedule(HOP_FAIL_TIMEOUT_MS);
        executor.hopBaseY = executor.client.player.getY();
        executor.hopBaseX = executor.client.player.getX();
        executor.hopBaseZ = executor.client.player.getZ();
        executor.hopTries = 0;
        PathExecutor.detail("hop watch armed (baseY=%.2f)", executor.hopBaseY);
      }
      // Success is a landing that got somewhere: higher ground, or real travel. Height at the top
      // of the arc alone is also what a jump straight into a wall produces, and counting that reset
      // the tries every hop so the failsafe never fired.
      double travelled =
          PathExecutor.flatDist(
              executor.client.player.getX(),
              executor.client.player.getZ(),
              executor.hopBaseX,
              executor.hopBaseZ);
      if (onGround
          && (executor.client.player.getY() > executor.hopBaseY + HOP_LANDED_RISE
              || travelled > HOP_LANDED_TRAVEL)) {
        PathExecutor.detail(
            "hop landed (yc=%.2f, baseY=%.2f, travelled=%.2f)",
            executor.client.player.getY(), executor.hopBaseY, travelled);
        clearHopFailsafe();
        executor.hopCooldown = HOP_COOLDOWN_TICKS;
      }
    } else {
      clearHopFailsafe();
    }

    if (shouldJump && onGround) {
      executor.hopTries++;
    }

    if (executor.hopClock.isScheduled()
        && executor.hopClock.passed()
        && executor.hopTries >= HOP_FAIL_MIN_TRIES
        && executor.client.player.getY() <= executor.hopBaseY + 0.35) {
      // Try a fresh route before quitting; only abort when the replan budget is gone.
      if (executor.askReplan(
          String.format("hop stuck (%d tries)", executor.hopTries), "a jump it could not clear")) {
        return;
      }
      executor.error(
          "Jump never got off the ground - %d tries in %dms, y=%.2f (started %.2f)",
          executor.hopTries, HOP_FAIL_TIMEOUT_MS, executor.client.player.getY(), executor.hopBaseY);
      executor.bailed = true;
      executor.won = false;
      executor.stop();
    }
  }

  void clearHopFailsafe() {
    if (executor.hopClock.isScheduled()) {
      executor.hopClock.reset();
    }
    executor.hopTries = 0;
    executor.hopBaseY = 0.0;
  }

  /**
   * Off-path replan: when the player sits more than {@link PathExecutor#OFF_PATH_CAP} blocks (true
   * 3D) from the nearest point on the planned route for {@link PathExecutor#DRIFT_PATIENCE_TICKS}
   * straight grounded ticks, steering has lost the path - pull a fresh route from here. Measured
   * against the whole trail with segments clamped to their ends, so it catches lateral drift,
   * overshoot / fall-behind ALONG the route, AND a drop to another level - all of which the old
   * perpendicular-to-the-active-segment check missed (a body displaced along the line, or dropped a
   * level, still read as "on the line"). Held off in the air (a hop arc rightly leaves the path).
   * Returns true if it suspended/stopped us (the caller returns).
   */
  boolean tickDriftWatch() {
    if (executor.holdStrafeOff() || executor.trail.size() < 2) {
      executor.driftStreak = 0;
      return false;
    }
    double offPath = executor.nearestPathDistance();
    if (offPath > OFF_PATH_CAP) {
      if (++executor.driftStreak > DRIFT_PATIENCE_TICKS) {
        executor.driftStreak = 0;
        if (executor.askReplan(
            String.format("%.1f off the path", offPath), "drifting off the path")) {
          return true;
        }
        executor.error("Drifted off the route and out of retries - stopping");
        executor.bailed = true;
        executor.won = false;
        executor.stop();
        return true;
      }
    } else {
      executor.driftStreak = 0;
    }
    return false;
  }

  /**
   * Give every stall watcher a fresh window. Used when a replan is asked for, so the watchers do
   * not immediately re-fire on the stall that triggered it while the new route is still being
   * searched.
   */
  void clearStallWatches() {
    executor.wedgeClock.reset();
    executor.progressClock.reset();
    executor.driftStreak = 0;
    executor.vertPinTicks = 0;
    executor.vertPinGap = Double.MAX_VALUE;
    executor.dropPinned = false;
    clearHopFailsafe();
  }
}
