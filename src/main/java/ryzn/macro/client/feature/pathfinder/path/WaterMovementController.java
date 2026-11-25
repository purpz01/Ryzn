package ryzn.macro.client.feature.pathfinder.path;

/**
 * Small state machine that turns measured water/player state into ordinary vanilla input policy.
 */
final class WaterMovementController {
  private static final int SWIM_ARM_TICKS = 4;
  private static final int EXIT_JUMP_CARRY_TICKS = 8;
  private static final int STALLED_RECOVERY_TICKS = 24;
  private static final double MOVING_EPSILON = 0.015;

  enum State {
    DRY,
    ENTERING,
    WADING,
    ARMING_SWIM,
    CRUISING,
    EXITING,
    SURFACING_RECOVERY
  }

  record Input(
      boolean inWater,
      boolean underwater,
      boolean vanillaSwimming,
      boolean plannedWater,
      boolean plannedSwim,
      boolean upcomingLand,
      boolean externalLook,
      boolean lowAir,
      double horizontalSpeed,
      double verticalSpeed) {}

  record Decision(
      State state,
      boolean controlled,
      boolean holdForward,
      boolean holdJump,
      boolean requestSprint,
      boolean suppressStrafe,
      boolean steerByKeys,
      float pitch) {}

  private State state = State.DRY;
  private int stateTicks;
  private int exitCarryTicks;
  private int stalledTicks;

  Decision tick(Input input) {
    if (!input.inWater()) {
      // The carry covers ANY water state, not EXITING alone. A body bobbing at a bank leaves the
      // fluid
      // for a tick or two every cycle while it is still ENTERING or WADING, and those states fell
      // straight through to reset() - which zeroes stalledTicks and hands keys and head back to the
      // ground rules. So SURFACING_RECOVERY, the one state that can swim a stuck body out, could
      // never
      // reach STALLED_RECOVERY_TICKS, and the executor spent two ticks in three running land logic
      // on
      // a floating body. Generalising the carry is what lets a bob mature into a rescue.
      if (state != State.DRY && exitCarryTicks < EXIT_JUMP_CARRY_TICKS) {
        // Stall ticks deliberately keep counting through the carry, for the same reason: a bob is
        // still a stall, and only real horizontal movement should clear it.
        exitCarryTicks++;
        if (input.horizontalSpeed() >= MOVING_EPSILON) {
          stalledTicks = 0;
        }
        transition(State.EXITING);
        return new Decision(state, true, true, true, false, false, true, 0.0f);
      }
      reset();
      return dryDecision();
    }

    exitCarryTicks = 0;
    boolean enteredNow = state == State.DRY;
    if (enteredNow) {
      transition(State.ENTERING);
    }
    stalledTicks = input.horizontalSpeed() < MOVING_EPSILON ? stalledTicks + 1 : 0;
    if (input.lowAir() || stalledTicks >= STALLED_RECOVERY_TICKS) {
      transition(State.SURFACING_RECOVERY);
    } else if (input.upcomingLand()) {
      transition(State.EXITING);
    } else if (enteredNow) {
      // Give vanilla a few ticks to establish immersion before deciding between wading and swim
      // pose.
    } else if (!input.plannedWater()) {
      transition(State.ENTERING);
    } else if (!input.plannedSwim() || input.externalLook()) {
      transition(State.WADING);
    } else if (input.vanillaSwimming()) {
      transition(State.CRUISING);
    } else if (state == State.ENTERING && stateTicks < SWIM_ARM_TICKS) {
      stateTicks++;
    } else {
      transition(State.ARMING_SWIM);
    }

    return switch (state) {
      // Upright in water the body moves on W/A/S/D exactly as it does on land, so it is steered at
      // the node with every movement key. Forcing forward walked it wherever the head pointed: the
      // gaze looks along the route, not at the node, and a body knocked into a pond then pushed
      // against the bank beside the node instead of stepping onto it.
      case ENTERING ->
          new Decision(state, true, true, input.underwater(), false, false, true, 0.0f);
      case WADING ->
          new Decision(
              state,
              true,
              true,
              input.plannedSwim() || input.underwater(),
              false,
              false,
              true,
              0.0f);
      case ARMING_SWIM ->
          new Decision(state, true, true, true, input.underwater(), true, false, -4.0f);
      case CRUISING -> new Decision(state, true, true, true, true, false, false, -2.0f);
      // Leaving water is steered by keys for the same reason; only the swim pose, which really does
      // travel along the look vector, stays forward-only.
      case EXITING -> new Decision(state, true, true, true, false, false, true, -6.0f);
      case SURFACING_RECOVERY -> new Decision(state, true, true, true, false, true, false, -28.0f);
      case DRY -> dryDecision();

      default -> throw new MatchException(null, null);
    };
  }

  State state() {
    return state;
  }

  void reset() {
    state = State.DRY;
    stateTicks = 0;
    exitCarryTicks = 0;
    stalledTicks = 0;
  }

  private void transition(State next) {
    if (state == next) {
      stateTicks++;
      return;
    }
    state = next;
    stateTicks = 0;
  }

  private static Decision dryDecision() {
    return new Decision(State.DRY, false, false, false, false, false, false, 0.0f);
  }
}
