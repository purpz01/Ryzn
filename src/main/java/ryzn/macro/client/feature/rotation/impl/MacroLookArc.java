package ryzn.macro.client.feature.rotation.impl;

import java.util.concurrent.ThreadLocalRandom;
import lombok.Getter;
import net.minecraft.util.Mth;

public final class MacroLookArc {

  /**
   * Fraction of turns whose far handle is thrown PAST the target, so the head sails past and
   * settles back.
   */
  private static final float OVERSHOOT_CHANCE = 0.40f;

  /** How far past the target that handle goes, as a fraction of the chord. */
  private static final float OVERSHOOT_MIN = 0.10f;

  private static final float OVERSHOOT_MAX = 0.30f;

  /**
   * Peak sail-past is about this fraction of (handle excess x chord); used to cap the absolute
   * excursion.
   */
  private static final float OVERSHOOT_EXCURSION_RATIO = 0.15f;

  /**
   * Absolute ceiling on the sail-past, so a 180 degree turn does not fling the head 7 degrees
   * clear.
   */
  private static final float MAX_OVERSHOOT_DEGREES = 5.0f;

  /** Effective target width for the difficulty term, in degrees: how close the turn has to land. */
  private static final double FITTS_TARGET_WIDTH_DEGREES = 2.5;

  /** Fitts intercept (ms): launch overhead that does not depend on how far the head has to go. */
  private static final double FITTS_INTERCEPT_MS = 50.0;

  /**
   * Fitts slope (ms per bit). 38 ms/bit is about 26 bits/s - well above a human's 4-12,
   * deliberately, because the mod has to stay quick. The point of the change is the SHAPE: duration
   * is now logarithmic in the turn rather than linear, which is the part a rotation log can
   * actually separate.
   */
  private static final double FITTS_SLOPE_MS_PER_BIT = 38.0;

  private static final int MIN_DURATION_MS = 90;
  private static final int MAX_DURATION_MS = 340;

  private boolean targetSet;
  @Getter private boolean active;
  private long startNanos;
  private long durationNanos;
  private float targetYaw;
  private float targetPitch;
  private float startYaw;
  private float startPitch;
  private float endYaw;
  private float endPitch;
  private float bend;
  private float shape;
  private float overshoot;

  public LookFrame sample(
      long nowNanos,
      float currentYaw,
      float currentPitch,
      float desiredYaw,
      float desiredPitch,
      float maximumBendDegrees,
      int durationMs) {
    desiredPitch = MacroAim.clampPitch(desiredPitch);
    boolean changed =
        !targetSet
            || Math.abs(Mth.wrapDegrees(desiredYaw - targetYaw)) > 0.001f
            || Math.abs(desiredPitch - targetPitch) > 0.001f;
    if (changed) {
      begin(
          nowNanos,
          currentYaw,
          currentPitch,
          desiredYaw,
          desiredPitch,
          maximumBendDegrees,
          durationMs);
    }
    if (!active) {
      return new LookFrame(targetYaw, targetPitch);
    }

    float linear =
        durationNanos <= 0L
            ? 1.0f
            : Math.clamp((nowNanos - startNanos) / (float) durationNanos, 0.0f, 1.0f);
    float progress = linear * linear * (3.0f - 2.0f * linear);
    LookFrame frame =
        curve(startYaw, startPitch, endYaw, endPitch, bend, shape, overshoot, progress);
    if (linear >= 1.0f) {
      active = false;
      return new LookFrame(targetYaw, targetPitch);
    }
    return frame;
  }

  public void reset() {
    targetSet = false;
    active = false;
  }

  /**
   * How long the turn should take, from Fitts's law: {@code MT = a + b*log2(1 + D/W)}.
   *
   * <p>This used to be {@code D / 300 deg-per-sec} clamped into a narrow band, i.e. LINEAR in the
   * turn. A human is logarithmic - each corrective submovement kills a fixed FRACTION of the
   * remaining error rather than a fixed amount, so the count of them scales with log(D/W). Linear
   * timing means a turn twice as long takes twice as long; the log form means it takes only a bit
   * longer, which is what a rotation log actually shows.
   *
   * <p>The constants are chosen to keep the mod's existing speed, not to match a real person: a 360
   * degree turn still lands at about 320 ms, exactly where the old clamp put it. Only the mid-range
   * turns gained time, and that is precisely where the old formula was least defensible.
   *
   * @param currentYaw the yaw the turn starts from
   * @param currentPitch the pitch the turn starts from
   * @param targetYaw the yaw the turn ends at
   * @param targetPitch the pitch the turn ends at
   * @param speedPercent the user's turn speed setting, as a percentage
   * @return the turn duration in milliseconds
   */
  public static int durationMs(
      float currentYaw, float currentPitch, float targetYaw, float targetPitch, int speedPercent) {
    double distance =
        Math.hypot(Mth.wrapDegrees(targetYaw - currentYaw), targetPitch - currentPitch);
    double speed = Math.max(1.0, speedPercent / 100.0);
    double difficulty = Math.log(1.0 + distance / FITTS_TARGET_WIDTH_DEGREES) / Math.log(2.0);
    double milliseconds = (FITTS_INTERCEPT_MS + FITTS_SLOPE_MS_PER_BIT * difficulty) / speed;
    return Math.clamp((int) Math.round(milliseconds), MIN_DURATION_MS, MAX_DURATION_MS);
  }

  private void begin(
      long nowNanos,
      float currentYaw,
      float currentPitch,
      float desiredYaw,
      float desiredPitch,
      float maximumBendDegrees,
      int durationMs) {
    targetSet = true;
    targetYaw = desiredYaw;
    targetPitch = desiredPitch;
    startYaw = currentYaw;
    startPitch = currentPitch;
    endYaw = currentYaw + Mth.wrapDegrees(desiredYaw - currentYaw);
    endPitch = desiredPitch;
    startNanos = nowNanos;
    durationNanos = Math.max(1, durationMs) * 1_000_000L;

    float distance = (float) Math.hypot(endYaw - startYaw, endPitch - startPitch);
    ThreadLocalRandom random = ThreadLocalRandom.current();
    float magnitude =
        Math.min(Math.max(0.0f, maximumBendDegrees), distance * 0.35f)
            * random.nextFloat(0.65f, 1.0f);
    bend = random.nextBoolean() ? magnitude : -magnitude;
    shape = random.nextFloat();
    overshoot = rollOvershoot(distance, random);
    active = distance > 0.001f && (magnitude > 0.0f || overshoot > 0.0f);
  }

  /**
   * How far past the target to throw the far handle, as a fraction of the chord (0 = stop dead on
   * it).
   *
   * <p>Overshoot used to be a bolt-on: run the curve to the target, then start a second phase that
   * coasts past and eases back. It does not need to be. The exit velocity of a cubic is {@code
   * B'(1) = 3(P3 - P2)}, so putting P2 past P3 makes the head already travelling BACK toward the
   * target as it arrives, which is only possible if it sailed past first. Overshoot-and-correct
   * then falls out of the geometry on the fraction of turns that draw it, inside the same duration,
   * costing nothing.
   */
  private static float rollOvershoot(float distance, ThreadLocalRandom random) {
    if (random.nextFloat() >= OVERSHOOT_CHANCE) {
      return 0.0f;
    }
    float excess = random.nextFloat(OVERSHOOT_MIN, OVERSHOOT_MAX);
    float ceiling = MAX_OVERSHOOT_DEGREES / (OVERSHOOT_EXCURSION_RATIO * Math.max(1.0f, distance));
    return Math.min(excess, ceiling);
  }

  static LookFrame curve(
      float startYaw,
      float startPitch,
      float endYaw,
      float endPitch,
      float bend,
      float shape,
      float overshoot,
      float progress) {
    progress = Math.clamp(progress, 0.0f, 1.0f);
    float yawDelta = endYaw - startYaw;
    float pitchDelta = endPitch - startPitch;
    float distance = (float) Math.hypot(yawDelta, pitchDelta);
    float normalYaw = distance < 0.0001f ? 0.0f : -pitchDelta / distance;
    float normalPitch = distance < 0.0001f ? 0.0f : yawDelta / distance;
    float asymmetry = (Math.clamp(shape, 0.0f, 1.0f) * 2.0f - 1.0f) * 0.10f;
    float firstControl = 0.30f + asymmetry;
    // Past 1.0 this handle sits BEYOND the target, which is what turns the arrival into an
    // overshoot. The
    // shape wobble is dropped in that case: it is the same size as the overshoot and would cancel
    // it out.
    float secondControl = overshoot > 0.0f ? 1.0f + overshoot : 0.72f - asymmetry;
    float secondBend = 0.35f + Math.clamp(shape, 0.0f, 1.0f) * 0.30f;

    float yaw =
        cubic(
            startYaw,
            startYaw + yawDelta * firstControl + normalYaw * bend,
            startYaw + yawDelta * secondControl + normalYaw * bend * secondBend,
            endYaw,
            progress);
    float pitch =
        cubic(
            startPitch,
            startPitch + pitchDelta * firstControl + normalPitch * bend,
            startPitch + pitchDelta * secondControl + normalPitch * bend * secondBend,
            endPitch,
            progress);
    return new LookFrame(yaw, MacroAim.clampPitch(pitch));
  }

  private static float cubic(float p0, float p1, float p2, float p3, float progress) {
    float inverse = 1.0f - progress;
    return inverse * inverse * inverse * p0
        + 3.0f * inverse * inverse * progress * p1
        + 3.0f * inverse * progress * progress * p2
        + progress * progress * progress * p3;
  }

  public record LookFrame(float yaw, float pitch) {}
}
