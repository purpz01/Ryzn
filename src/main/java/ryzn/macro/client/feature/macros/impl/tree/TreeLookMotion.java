package ryzn.macro.client.feature.macros.impl.tree;

import net.minecraft.util.Mth;

/** Frame-time based smoothing and acceleration state for tree-chop camera motion. */
final class TreeLookMotion {

  private static final float STEP_CEILING_DEGREES = 30.0f;
  private static final float MIN_YAW_DEGREES_PER_SECOND = 170.0f;
  private static final float MAX_YAW_DEGREES_PER_SECOND = 850.0f;
  private static final float MIN_PITCH_DEGREES_PER_SECOND = 75.0f;
  private static final float MAX_PITCH_DEGREES_PER_SECOND = 375.0f;

  /**
   * Proportional gain on the closing turn, split by how far the target moved.
   *
   * <p>The sweep between adjacent logs mid-break is gentle on purpose and stays at the tuned 8.
   * Acquisition - the first log of a tree, and the tree watched on the walk in - is a 30 to 120
   * degree turn, and at gain 8 it commands distance*8 deg/s, i.e. it decays to a crawl exactly
   * where the turn should be finishing. That long tail, on top of the target filter below, is the
   * slow head turn on the way to a tree.
   */
  private static final float ARRIVAL_GAIN_PER_SECOND = 8.0f;

  private static final float TRAVEL_ARRIVAL_GAIN_PER_SECOND = 16.0f;
  private static final float ACCELERATION_DEGREES_PER_SECOND_SQUARED = 900.0f;
  private static final float NEAR_TARGET_SMOOTH_SECONDS = 0.09f;
  // Far targets are re-aims, not sweeps: the acceleration limiter below already keeps the start
  // civil, so a
  // second 0.16s lag in front of it only delayed the turn. The near value still shapes the break
  // sweep.
  private static final float FAR_TARGET_SMOOTH_SECONDS = 0.05f;

  private volatile float rawTargetYaw;
  private volatile float rawTargetPitch;
  private volatile boolean hasTarget;

  private volatile float targetSmoothSeconds = FAR_TARGET_SMOOTH_SECONDS;
  private volatile float arrivalGain = TRAVEL_ARRIVAL_GAIN_PER_SECOND;
  private float smoothTargetYaw;
  private float smoothTargetPitch;
  private float yawVelocity;
  private float pitchVelocity;
  private long lastFrameNs;
  private float overshootYaw;
  private float overshootPitch;
  private boolean correctingAim;

  void setTarget(
      float yaw,
      float pitch,
      float currentYaw,
      float currentPitch,
      boolean targetChanged,
      boolean adjacentTarget) {
    if (!hasTarget) {
      smoothTargetYaw = currentYaw;
      smoothTargetPitch = currentPitch;
      pause();
    }
    if (targetChanged) {
      correctingAim = false;
      cancelOvershoot();
      targetSmoothSeconds = adjacentTarget ? NEAR_TARGET_SMOOTH_SECONDS : FAR_TARGET_SMOOTH_SECONDS;
      arrivalGain = adjacentTarget ? ARRIVAL_GAIN_PER_SECOND : TRAVEL_ARRIVAL_GAIN_PER_SECOND;
    }
    rawTargetYaw = yaw;
    rawTargetPitch = pitch;
    hasTarget = true;
  }

  /**
   * Overrides the ease for the current target until the next target change: a slow drift while a
   * tree is only being watched, or a gentler first turn onto a log after watching.
   */
  void soften(float smoothSeconds, float gainPerSecond) {
    targetSmoothSeconds = smoothSeconds;
    arrivalGain = gainPerSecond;
  }

  void overshoot(float currentYaw, float currentPitch, float degrees) {
    float yaw = Mth.wrapDegrees(rawTargetYaw - currentYaw);
    float pitch = rawTargetPitch - currentPitch;
    float distance = (float) Math.hypot(yaw, pitch);
    if (degrees <= 0f || distance < 5f) {
      return;
    }
    overshootYaw = yaw / distance * degrees;
    overshootPitch = pitch / distance * degrees;
  }

  void cancelOvershoot() {
    overshootYaw = 0f;
    overshootPitch = 0f;
  }

  void correctAim() {
    cancelOvershoot();
    correctingAim = true;
  }

  boolean hasTarget() {
    return hasTarget;
  }

  void clear() {
    hasTarget = false;
    correctingAim = false;
    cancelOvershoot();
    pause();
  }

  void pause() {
    yawVelocity = 0f;
    pitchVelocity = 0f;
    lastFrameNs = 0L;
  }

  LookFrame advance(
      float currentYaw,
      float currentPitch,
      long nowNs,
      int rotationSpeed,
      float lookAcceleration,
      float deadzone) {
    return advance(
        currentYaw, currentPitch, nowNs, rotationSpeed, 1.0f, lookAcceleration, deadzone);
  }

  LookFrame advance(
      float currentYaw,
      float currentPitch,
      long nowNs,
      int rotationSpeed,
      float rotationGain,
      float lookAcceleration,
      float deadzone) {
    if (!hasTarget) {
      return new LookFrame(false, currentYaw, currentPitch);
    }

    float deltaSeconds = lastFrameNs == 0L ? 1.0f / 60.0f : (nowNs - lastFrameNs) / 1.0E9f;
    lastFrameNs = nowNs;
    deltaSeconds = Mth.clamp(deltaSeconds, 1.0f / 300.0f, 1.0f / 20.0f);

    if (Math.hypot(
            Mth.wrapDegrees(rawTargetYaw + overshootYaw - currentYaw),
            Mth.clamp(rawTargetPitch + overshootPitch, -90f, 90f) - currentPitch)
        < 0.8f) {
      cancelOvershoot();
    }
    float blend =
        1.0f
            - (float)
                Math.exp(
                    -deltaSeconds / Math.max(0.02f, correctingAim ? 0.02f : targetSmoothSeconds));
    smoothTargetYaw += Mth.wrapDegrees(rawTargetYaw + overshootYaw - smoothTargetYaw) * blend;
    smoothTargetPitch += (rawTargetPitch + overshootPitch - smoothTargetPitch) * blend;

    float yawRemaining = Mth.wrapDegrees(rawTargetYaw + overshootYaw - currentYaw);
    float pitchRemaining = Mth.clamp(rawTargetPitch + overshootPitch, -90f, 90f) - currentPitch;
    float yawDifference = towardTarget(Mth.wrapDegrees(smoothTargetYaw - currentYaw), yawRemaining);
    float pitchDifference = towardTarget(smoothTargetPitch - currentPitch, pitchRemaining);
    smoothTargetYaw = currentYaw + yawDifference;
    smoothTargetPitch = currentPitch + pitchDifference;
    float distance =
        (float) Math.sqrt(yawDifference * yawDifference + pitchDifference * pitchDifference);
    if (distance < deadzone) {
      yawVelocity *= 0.6f;
      pitchVelocity *= 0.6f;
      return new LookFrame(false, currentYaw, currentPitch);
    }

    float speedFraction = (rotationSpeed - 1) / 4.0f;
    float yawSpeed =
        MIN_YAW_DEGREES_PER_SECOND
            + speedFraction * (MAX_YAW_DEGREES_PER_SECOND - MIN_YAW_DEGREES_PER_SECOND);
    float pitchSpeed =
        MIN_PITCH_DEGREES_PER_SECOND
            + speedFraction * (MAX_PITCH_DEGREES_PER_SECOND - MIN_PITCH_DEGREES_PER_SECOND);
    yawSpeed *= rotationGain;
    pitchSpeed *= rotationGain;
    float unitYaw = yawDifference / distance;
    float unitPitch = pitchDifference / distance;
    float speedCap = Float.MAX_VALUE;
    if (Math.abs(unitYaw) > 1.0E-4f) {
      speedCap = Math.min(speedCap, yawSpeed / Math.abs(unitYaw));
    }
    if (Math.abs(unitPitch) > 1.0E-4f) {
      speedCap = Math.min(speedCap, pitchSpeed / Math.abs(unitPitch));
    }
    float desiredSpeed =
        Math.min(
            speedCap,
            distance
                * (correctingAim ? TRAVEL_ARRIVAL_GAIN_PER_SECOND : arrivalGain)
                * rotationGain);

    float maximumVelocityChange =
        ACCELERATION_DEGREES_PER_SECOND_SQUARED
            * (0.6f + lookAcceleration)
            * (1.0f + Math.max(0, rotationSpeed - 5) * 0.25f)
            * rotationGain
            * deltaSeconds;
    yawVelocity +=
        Mth.clamp(
            unitYaw * desiredSpeed - yawVelocity, -maximumVelocityChange, maximumVelocityChange);
    pitchVelocity +=
        Mth.clamp(
            unitPitch * desiredSpeed - pitchVelocity,
            -maximumVelocityChange,
            maximumVelocityChange);

    float yawStep = yawVelocity * deltaSeconds;
    float pitchStep = pitchVelocity * deltaSeconds;
    float stepLength = (float) Math.sqrt(yawStep * yawStep + pitchStep * pitchStep);
    float stepCeiling =
        Math.min(
            (STEP_CEILING_DEGREES + Math.max(0, rotationSpeed - 5) * 6.0f) * rotationGain,
            distance);
    if (stepLength > stepCeiling && stepLength > 1.0E-4f) {
      float scale = stepCeiling / stepLength;
      yawStep *= scale;
      pitchStep *= scale;
      yawVelocity = yawStep / deltaSeconds;
      pitchVelocity = pitchStep / deltaSeconds;
    }

    // Each axis must stop at its own target, even while the other axis is still moving.
    yawStep = towardTarget(yawStep, yawDifference);
    pitchStep = towardTarget(pitchStep, pitchDifference);
    yawVelocity = yawStep / deltaSeconds;
    pitchVelocity = pitchStep / deltaSeconds;
    return new LookFrame(
        true, currentYaw + yawStep, Mth.clamp(currentPitch + pitchStep, -90f, 90f));
  }

  private static float towardTarget(float step, float remaining) {
    return Mth.clamp(step, Math.min(0f, remaining), Math.max(0f, remaining));
  }

  record LookFrame(boolean moved, float yaw, float pitch) {}
}
