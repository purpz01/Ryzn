package ryzn.macro.client.feature.macros.impl.wheat;

import lombok.AccessLevel;
import lombok.Setter;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.feature.rotation.impl.MacroAim;

final class WheatLookMotion {

  private static final float AIM_DEADZONE = 0.35f;
  private static final double ACCEL_NEAR_DISTANCE = 2.0;
  private static final double ACCEL_FAR_DISTANCE = 8.0;
  private static final float YAW_STEP_CEILING = 9.0f;
  private static final float PITCH_STEP_CEILING = 4.5f;

  private final float[] desiredAim = new float[2];

  @Setter(AccessLevel.PACKAGE)
  private Vec3 target;

  private boolean initialized;
  private float smoothYaw;
  private float smoothPitch;
  private long lastNanos;

  Vec3 target() {
    return target;
  }

  boolean hasTarget() {
    return target != null;
  }

  void clearTarget() {
    target = null;
  }

  void reset() {
    target = null;
    initialized = false;
  }

  /**
   * Legacy per-frame ease (one step). Kept verbatim for the unit tests + callers not yet on the
   * tick path.
   */
  LookFrame advance(
      Vec3 eye, float currentYaw, float currentPitch, int speedSetting, int accelerationPercent) {
    if (target == null) {
      return new LookFrame(false, currentYaw, currentPitch);
    }
    if (!initialized) {
      smoothYaw = currentYaw;
      smoothPitch = currentPitch;
      initialized = true;
    }
    boolean moved = easeOnce(eye, speedSetting, accelerationPercent);
    return new LookFrame(moved, smoothYaw, smoothPitch);
  }

  private static float blend(float dt, float tau) {
    return 1f - (float) Math.exp(-dt / tau);
  }

  private boolean easeOnce(Vec3 eye, int speedSetting, int accelerationPercent) {
    MacroAim.desiredYawPitch(eye, target, desiredAim);
    float yawError = Mth.wrapDegrees(desiredAim[0] - smoothYaw);
    float pitchError = desiredAim[1] - smoothPitch;
    float acceleration =
        acceleration(
            horizontalDistanceSqr(eye, target),
            Math.max(Math.abs(yawError), Math.abs(pitchError)),
            accelerationPercent);
    boolean moved = false;
    if (Math.abs(yawError) > AIM_DEADZONE) {
      float maxStep = Math.min(yawMaxStep(speedSetting) * acceleration, YAW_STEP_CEILING);
      smoothYaw += Mth.clamp(yawError * yawEase(speedSetting) * acceleration, -maxStep, maxStep);
      moved = true;
    }
    if (Math.abs(pitchError) > AIM_DEADZONE) {
      float maxStep = Math.min(pitchMaxStep(speedSetting) * acceleration, PITCH_STEP_CEILING);
      smoothPitch =
          Mth.clamp(
              smoothPitch
                  + Mth.clamp(
                      pitchError * pitchEase(speedSetting) * acceleration, -maxStep, maxStep),
              -90f,
              90f);
      moved = true;
    }
    return moved;
  }

  private static double horizontalDistanceSqr(Vec3 eye, Vec3 target) {
    double dx = target.x - eye.x;
    double dz = target.z - eye.z;
    return dx * dx + dz * dz;
  }

  private static float acceleration(double horizontalSqr, float angleGap, int accelerationPercent) {
    double distance = Math.sqrt(Math.max(0.0, horizontalSqr));
    float distanceFactor =
        (float)
            Mth.clamp(
                (distance - ACCEL_NEAR_DISTANCE) / (ACCEL_FAR_DISTANCE - ACCEL_NEAR_DISTANCE),
                0.0,
                1.0);
    float angleFactor = Mth.clamp(angleGap / 50.0f, 0.0f, 1.0f);
    return 1.0f + (accelerationPercent / 100.0f) * distanceFactor * (0.35f + 0.65f * angleFactor);
  }

  private static float yawEase(int speedSetting) {
    return switch (speedSetting) {
      case 1 -> 0.025f;
      case 2 -> 0.04f;
      case 3 -> 0.06f;
      case 4 -> 0.08f;
      default -> 0.10f;
    };
  }

  private static float pitchEase(int speedSetting) {
    return switch (speedSetting) {
      case 1 -> 0.02f;
      case 2 -> 0.032f;
      case 3 -> 0.046f;
      case 4 -> 0.062f;
      default -> 0.08f;
    };
  }

  private static float yawMaxStep(int speedSetting) {
    return switch (speedSetting) {
      case 1 -> 0.75f;
      case 2 -> 1.15f;
      case 3 -> 1.65f;
      case 4 -> 2.25f;
      default -> 3.0f;
    };
  }

  private static float pitchMaxStep(int speedSetting) {
    return switch (speedSetting) {
      case 1 -> 0.35f;
      case 2 -> 0.55f;
      case 3 -> 0.8f;
      case 4 -> 1.1f;
      default -> 1.5f;
    };
  }

  record LookFrame(boolean moved, float yaw, float pitch) {}
}
