package ryzn.macro.client.feature.rotation.impl;

import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.util.Mth;

/** Stateful critically damped smoothing for a yaw and pitch pair. */
@Accessors(fluent = true)
public final class AngleSmoother {

  @Getter private float yaw;
  @Getter private float pitch;
  private float yawVelocity;
  private float pitchVelocity;
  private boolean primed;
  private float nextVelocity;

  /**
   * @description Creates an unprimed angle smoother for one rotation session.
   */
  public AngleSmoother() {}

  /**
   * @description Reports whether a current angle has been supplied for smoothing.
   * @return true after prime and before reset
   */
  public boolean isPrimed() {
    return primed;
  }

  /**
   * @description Seeds the smoother with a wrapped yaw and bounded pitch and clears velocity.
   * @param yaw the starting yaw in degrees
   * @param pitch the starting pitch in degrees
   */
  public void prime(float yaw, float pitch) {
    this.yaw = Mth.wrapDegrees(yaw);
    this.pitch = Mth.clamp(pitch, -90f, 90f);
    yawVelocity = 0f;
    pitchVelocity = 0f;
    primed = true;
  }

  /**
   * @description Discards the current seed and velocity before a new rotation session.
   */
  public void reset() {
    primed = false;
    yawVelocity = 0f;
    pitchVelocity = 0f;
  }

  /**
   * @description Advances both axes toward their targets without exceeding the speed cap.
   * @param targetYaw the desired yaw in degrees
   * @param targetPitch the desired pitch in degrees
   * @param smoothTime the damping time in seconds
   * @param maxSpeed the maximum angular speed in degrees per second
   * @param deltaSeconds elapsed time since the previous step
   */
  public void step(
      float targetYaw, float targetPitch, float smoothTime, float maxSpeed, float deltaSeconds) {
    if (!primed || deltaSeconds <= 0f) {
      return;
    }

    float unwrappedYaw = yaw + Mth.wrapDegrees(targetYaw - yaw);
    yaw =
        Mth.wrapDegrees(
            smoothDamp(yaw, unwrappedYaw, yawVelocity, smoothTime, maxSpeed, deltaSeconds));
    yawVelocity = nextVelocity;

    float clampedPitch = Mth.clamp(targetPitch, -90f, 90f);
    pitch =
        Mth.clamp(
            smoothDamp(pitch, clampedPitch, pitchVelocity, smoothTime, maxSpeed, deltaSeconds),
            -90f,
            90f);
    pitchVelocity = nextVelocity;
  }

  /**
   * @description Applies one critically damped axis step and stores its next velocity.
   * @param current the current angle component
   * @param target the target angle component
   * @param velocity the prior angular velocity
   * @param smoothTime the requested damping time in seconds
   * @param maxSpeed the maximum angular speed
   * @param deltaSeconds positive elapsed time
   * @return the bounded next angle component
   */
  private float smoothDamp(
      float current,
      float target,
      float velocity,
      float smoothTime,
      float maxSpeed,
      float deltaSeconds) {
    float safeTime = Math.max(1.0e-4f, smoothTime);
    float omega = 2f / safeTime;
    float x = omega * deltaSeconds;
    float decay = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x);

    float originalTarget = target;
    float change = Mth.clamp(current - target, -maxSpeed * safeTime, maxSpeed * safeTime);
    float movedTarget = current - change;
    float temporary = (velocity + omega * change) * deltaSeconds;
    float outputVelocity = (velocity - omega * temporary) * decay;
    float output = movedTarget + (change + temporary) * decay;

    if ((originalTarget - current > 0f) == (output > originalTarget)) {
      output = originalTarget;
      outputVelocity = (output - movedTarget) / deltaSeconds;
    }
    nextVelocity = outputVelocity;
    return output;
  }
}
