package ryzn.macro.client.feature.pathfinder.path;

import ryzn.macro.client.config.groups.PathfinderSettings;

/** Runtime steering values derived from configured mode and measured horizontal speed. */
record PathSpeedProfile(
    float yawFloor,
    float yawCeil,
    float amountMinimum,
    float amountMaximum,
    double lookAhead,
    double reachRadius,
    boolean sprint) {

  static PathSpeedProfile from(PathfinderSettings settings, double measuredSpeed) {
    float yawCeil;
    double lookAhead;
    double reachRadius;
    switch (settings.getPathSpeedMode()) {
      case PathfinderSettings.SPEED_NORMAL -> {
        yawCeil = 40f;
        lookAhead = 3.5;
        reachRadius = 0.7;
      }
      case PathfinderSettings.SPEED_FAST -> {
        yawCeil = 55f;
        lookAhead = 4.5;
        reachRadius = 1.1;
      }
      case PathfinderSettings.SPEED_EXTREME -> {
        yawCeil = 75f;
        lookAhead = 5.5;
        reachRadius = 1.6;
      }
      case PathfinderSettings.SPEED_AUTO -> {
        double boost = Math.max(0.0, measuredSpeed - 0.28);
        yawCeil = (float) Math.clamp(40.0 + boost * 130.0, 40.0, 90.0);
        lookAhead = Math.clamp(3.5 + boost * 9.0, 3.5, 7.0);
        reachRadius = Math.clamp(0.7 + boost * 3.2, 0.7, 2.2);
      }
      default -> {
        double custom = Math.clamp((settings.getPathCustomSpeed() - 1) / 99.0, 0.0, 1.0);
        yawCeil = (float) (40.0 + custom * 50.0);
        lookAhead = 3.5 + custom * 3.5;
        reachRadius = 0.7 + custom * 1.5;
      }
    }

    float yawFloor = settings.getRotationMinDegPerTick();
    // String-pull strength: scale down the steering ceiling so the head eases onto the path line
    // more gently;
    // a looser pull leaves the body off the line longer, so the strafe net (A/D) carries more of
    // the correction.
    yawCeil *= settings.stringPullFactor();
    yawCeil =
        (float)
            Math.clamp(
                yawCeil, settings.getRotationMinDegPerTick(), settings.getRotationMaxDegPerTick());
    return new PathSpeedProfile(
        yawFloor,
        yawCeil,
        settings.getRotationAmountMin(),
        settings.getRotationAmountMax(),
        lookAhead,
        reachRadius,
        settings.isSprint());
  }
}
