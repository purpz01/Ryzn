package ryzn.macro.client.feature.pathfinder.path;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.core.BlockPos;

/** Latches one obstructed-rim escape target until safe ground or a hard failure. */
@Accessors(fluent = true)
final class RimRecoveryState {

  static final int MAX_NO_PROGRESS_TICKS = 12;
  private static final int MAX_AIRBORNE_TICKS = 2;
  private static final int MAX_ESCAPE_TICKS = 60;
  private static final double PROGRESS_DISTANCE_SQUARED = 0.0025;

  enum TickDecision {
    MOVE,
    PAUSE_AIRBORNE,
    ABORT
  }

  private enum Phase {
    IDLE,
    ESCAPING,
    WAITING_FOR_ROUTE
  }

  private Phase phase = Phase.IDLE;

  @Getter(AccessLevel.PACKAGE)
  private BlockPos target;

  @Getter(AccessLevel.PACKAGE)
  private RimRecovery.MovementIntent intent;

  private double progressDistanceSquared;
  private int noProgressTicks;
  private int airborneTicks;
  private int escapeTicks;

  boolean begin(BlockPos target, double playerX, double playerZ) {
    if (phase != Phase.IDLE || target == null) {
      return false;
    }
    this.target = target;
    progressDistanceSquared = distanceSquaredToTarget(playerX, playerZ);
    noProgressTicks = 0;
    airborneTicks = 0;
    escapeTicks = 0;
    phase = Phase.ESCAPING;
    return true;
  }

  void updateIntent(RimRecovery.MovementIntent intent) {
    if (phase == Phase.ESCAPING) {
      this.intent = intent;
    }
  }

  TickDecision observe(double playerX, double playerZ, boolean onGround) {
    if (phase != Phase.ESCAPING || ++escapeTicks > MAX_ESCAPE_TICKS) {
      return TickDecision.ABORT;
    }
    if (!onGround) {
      return ++airborneTicks > MAX_AIRBORNE_TICKS
          ? TickDecision.ABORT
          : TickDecision.PAUSE_AIRBORNE;
    }

    airborneTicks = 0;
    double distanceSquared = distanceSquaredToTarget(playerX, playerZ);
    if (progressDistanceSquared - distanceSquared >= PROGRESS_DISTANCE_SQUARED) {
      progressDistanceSquared = distanceSquared;
      noProgressTicks = 0;
    } else if (++noProgressTicks >= MAX_NO_PROGRESS_TICKS) {
      return TickDecision.ABORT;
    }
    return TickDecision.MOVE;
  }

  boolean isEscaping() {
    return phase == Phase.ESCAPING;
  }

  boolean isWaitingForRoute() {
    return phase == Phase.WAITING_FOR_ROUTE;
  }

  void markWaitingForRoute() {
    if (phase != Phase.ESCAPING) {
      return;
    }
    target = null;
    intent = null;
    phase = Phase.WAITING_FOR_ROUTE;
  }

  void clear() {
    phase = Phase.IDLE;
    target = null;
    intent = null;
    progressDistanceSquared = 0.0;
    noProgressTicks = 0;
    airborneTicks = 0;
    escapeTicks = 0;
  }

  private double distanceSquaredToTarget(double playerX, double playerZ) {
    double dx = target.getX() + 0.5 - playerX;
    double dz = target.getZ() + 0.5 - playerZ;
    return dx * dx + dz * dz;
  }
}
