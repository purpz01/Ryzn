package ryzn.macro.client.feature.pathfinder.path;

/** Sprint input and arrival geometry, shared with route regression tests. */
final class WalkingPolicy {
  static boolean sprint(boolean enabled, boolean sneak) {
    return enabled && !sneak;
  }

  static boolean carryStrafeForward(boolean forwardWasHeld, float bearing) {
    return forwardWasHeld && Math.abs(bearing) < 90.0f;
  }

  static final class DropBrake {
    private enum Phase {
      APPROACH,
      COAST,
      STEP_OFF
    }

    private int waypoint = -1;
    private Phase phase = Phase.APPROACH;

    void reset() {
      waypoint = -1;
      phase = Phase.APPROACH;
    }

    boolean isCoasting() {
      return phase == Phase.COAST;
    }

    boolean holdForwardOff(int dropWaypoint, boolean inBrakeRange, boolean aboveSpeedFloor) {
      if (dropWaypoint < 0) {
        reset();
        return false;
      }
      if (dropWaypoint != waypoint) {
        waypoint = dropWaypoint;
        phase = Phase.APPROACH;
      }
      if (phase == Phase.APPROACH && inBrakeRange && aboveSpeedFloor) {
        phase = Phase.COAST;
      } else if (phase == Phase.COAST && !aboveSpeedFloor) {
        phase = Phase.STEP_OFF;
      }
      return isCoasting();
    }
  }

  static boolean coastReaches(
      double dx, double dz, double vx, double vz, double glide, double radius) {
    double speed = Math.hypot(vx, vz);
    if (speed == 0) {
      return false;
    }
    double forward = (dx * vx + dz * vz) / speed;
    if (forward <= 0) {
      return false;
    }
    double travel = Math.min(forward, glide);
    return Math.hypot(dx - travel * vx / speed, dz - travel * vz / speed) <= radius;
  }
}
