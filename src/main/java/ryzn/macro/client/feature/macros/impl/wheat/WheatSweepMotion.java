package ryzn.macro.client.feature.macros.impl.wheat;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.util.Mth;

/**
 * Generates harvest aim targets for a side-to-side mouse swing around a slowly turning heading, the
 * way a player clears crops by dragging the crosshair across the row while walking. Wheat breaks
 * instantly, so whatever the held attack passes over goes; nothing is aimed at block by block.
 *
 * <p>Every swing is a cosine ease from one edge to the other (slow at the ends, fastest through the
 * middle) with its own width, duration and end pause, and the pitch drifts a little around the crop
 * line. All of it is sampled from wall-clock time, so the game tick and the render frame can both
 * sample it without speeding it up.
 */
final class WheatSweepMotion {

  /** How fast the heading follows the walk direction; this is what rounds off corners. */
  private static final float HEADING_TAU_SECONDS = 0.32f;

  private static final float HEADING_MAX_RATE = 220.0f;
  private static final float PITCH_TAU_SECONDS = 0.28f;

  /** Widest half-swing, whatever the sweep setting says; past this the walk would stall. */
  private static final float MAX_HALF_ARC = 75.0f;

  private static final float MIN_HALF_ARC = 20.0f;
  private static final float REFERENCE_ARC = 90.0f;
  private static final float MIN_SWING_SECONDS = 0.16f;
  private static final float PITCH_DRIFT = 2.5f;

  private boolean initialized;
  private boolean goalSet;
  private long lastNanos;
  private float heading;
  private float pitch;
  private float targetHeading;
  private float targetPitch;
  private float halfArc = 60.0f;
  private int speed = 3;

  private float swingFrom;
  private float swingTo;
  private long swingStartNanos;
  private long swingDurationNanos;
  private long swingDwellNanos;
  private int direction = 1;

  private float pitchDrift;
  private float pitchDriftTarget;
  private long pitchDriftUntilNanos;

  boolean isActive() {
    return initialized;
  }

  void reset() {
    initialized = false;
    goalSet = false;
  }

  /**
   * Updates where the swing is centred and how wide it is. Called once per game tick.
   *
   * @param headingYaw the direction the body is walking or facing
   * @param cropPitch the pitch that puts the crosshair on the crops ahead
   * @param sweepDegrees the full arc from the settings
   */
  void setGoal(float headingYaw, float cropPitch, int sweepDegrees) {
    targetHeading = headingYaw;
    targetPitch = cropPitch;
    goalSet = true;
    halfArc = Mth.clamp(sweepDegrees * 0.5f, MIN_HALF_ARC, MAX_HALF_ARC);
  }

  /** The smoothed walking heading; movement keys are chosen from this, not the swinging view. */
  float heading(float fallback) {
    return initialized ? heading : fallback;
  }

  /**
   * Samples the current sweep target, starting from the current view.
   *
   * @param speedSetting 1-5 look speed
   * @return target yaw and pitch
   */
  float[] sampleTarget(float currentYaw, float currentPitch, int speedSetting) {
    speed = Mth.clamp(speedSetting, 1, 5);
    final long now = System.nanoTime();
    if (!initialized) {
      initialized = true;
      if (!goalSet) {
        targetHeading = currentYaw;
        targetPitch = currentPitch;
      }
      lastNanos = now;
      heading = currentYaw;
      pitch = currentPitch;
      swingFrom = 0.0f;
      direction = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
      startSwing(now);
      pitchDrift = 0.0f;
      pitchDriftTarget = 0.0f;
      pitchDriftUntilNanos = now;
    }
    final float dt = Math.min(0.25f, (now - lastNanos) / 1_000_000_000f);
    if (dt > 0f) {
      lastNanos = now;
      final float headingError = Mth.wrapDegrees(targetHeading - heading);
      final float maxTurn = HEADING_MAX_RATE * dt;
      heading += Mth.clamp(headingError * blend(dt, HEADING_TAU_SECONDS), -maxTurn, maxTurn);
      heading = Mth.wrapDegrees(heading);
      updatePitchDrift(now, dt);
      pitch += (targetPitch + pitchDrift - pitch) * blend(dt, PITCH_TAU_SECONDS);
    }
    return new float[] {
      Mth.wrapDegrees(heading + swingOffset(now)), Mth.clamp(pitch, -90.0f, 90.0f)
    };
  }

  private float swingOffset(long now) {
    long elapsed = now - swingStartNanos;
    if (elapsed >= swingDurationNanos + swingDwellNanos) {
      swingFrom = swingTo;
      startSwing(now);
      elapsed = 0L;
    }
    final double t = Math.min(1.0, elapsed / (double) swingDurationNanos);
    final double eased = 0.5 - 0.5 * Math.cos(Math.PI * t);
    // The arc can change width mid-swing (new zone, new setting); never swing past the new edge.
    return Mth.clamp((float) (swingFrom + (swingTo - swingFrom) * eased), -halfArc, halfArc);
  }

  private void startSwing(long now) {
    final ThreadLocalRandom random = ThreadLocalRandom.current();
    swingFrom = Mth.clamp(swingFrom, -halfArc, halfArc);
    swingTo = direction * halfArc * (float) random.nextDouble(0.72, 1.0);
    direction = -direction;
    final float distance = Math.abs(swingTo - swingFrom);
    final double seconds =
        Math.max(
            MIN_SWING_SECONDS,
            baseSwingSeconds(speed)
                * Math.pow(distance / REFERENCE_ARC, 0.6)
                * random.nextDouble(0.85, 1.18));
    swingStartNanos = now;
    swingDurationNanos = (long) (seconds * 1_000_000_000L);
    // A short hold at the edge, now and then a longer one - a hand re-gripping the mouse.
    final double dwell =
        random.nextDouble() < 0.08 ? random.nextDouble(0.12, 0.28) : random.nextDouble(0.0, 0.05);
    swingDwellNanos = (long) (dwell * 1_000_000_000L);
  }

  private void updatePitchDrift(long now, float dt) {
    if (now >= pitchDriftUntilNanos) {
      final ThreadLocalRandom random = ThreadLocalRandom.current();
      pitchDriftTarget = (float) random.nextDouble(-PITCH_DRIFT, PITCH_DRIFT);
      pitchDriftUntilNanos = now + (long) (random.nextDouble(0.7, 1.8) * 1_000_000_000L);
    }
    pitchDrift += (pitchDriftTarget - pitchDrift) * blend(dt, 0.6f);
  }

  private static double baseSwingSeconds(int speedSetting) {
    return switch (speedSetting) {
      case 1 -> 0.78;
      case 2 -> 0.62;
      case 3 -> 0.5;
      case 4 -> 0.41;
      default -> 0.34;
    };
  }

  private static float blend(float dt, float tau) {
    return 1f - (float) Math.exp(-dt / tau);
  }
}
