package ryzn.macro.client.feature.rotation.impl.abcurves;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;

/** Converts application yaw and pitch into the persistent ABCurves planner's angular frame. */
public final class AbcRotation {
  private static final double COUNTS_PER_DEGREE = Math.PI / 180 / 0.0003509487083280618;
  private static final long MAX_GAP_NS = 250_000_000;
  private static volatile CompletableFuture<AbcModels> loading;

  public record Frame(float yaw, float pitch) {}

  public record Tuning(
      boolean randomSeed,
      int yawCap,
      int pitchCap,
      int dampingMillis,
      double deadzone,
      boolean limitOvershoot,
      boolean quantizeOutput) {
    static final Tuning DEFAULT = new Tuning(false, 0, 0, 0, 0, false, true);
  }

  public static synchronized void prewarm() {
    if (loading != null) {
      return;
    }
    loading =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                var models = new AbcModels();
                try {
                  var probe = new AbcStream(new AbcPolicy(models, 7));
                  probe.updateTarget(500, 120, 0);
                  probe.advance(640_000);
                  return models;
                } catch (RuntimeException failure) {
                  models.close();
                  throw failure;
                }
              } catch (Exception | LinkageError failure) {
                throw new CompletionException(failure);
              }
            });
  }

  public static String status() {
    var future = loading;
    if (future == null || !future.isDone()) {
      return "Loading";
    }
    try {
      future.join();
      return "Ready";
    } catch (CompletionException exception) {
      Throwable cause = exception.getCause();
      return "Unavailable (" + cause.getClass().getSimpleName() + ")";
    }
  }

  public static final class Session {
    private AbcStream stream;
    private double plannerNanos;
    private long lastTime;
    private float lastYaw;
    private float lastPitch;
    private double anchorYaw;
    private double goalYaw;
    private double goalPitch;
    private double wantedYaw;
    private double wantedPitch;
    private double shapedYaw;
    private double shapedPitch;
    private boolean failed;
    private boolean heldYaw;

    public void reset() {
      stream = null;
      failed = false;
    }

    public Frame step(
        boolean enabled,
        long seed,
        long now,
        float yaw,
        float pitch,
        float targetYaw,
        float targetPitch,
        float quantum) {
      return step(enabled, seed, now, yaw, pitch, targetYaw, targetPitch, quantum, false);
    }

    public Frame step(
        boolean enabled,
        long seed,
        long now,
        float yaw,
        float pitch,
        float targetYaw,
        float targetPitch,
        float quantum,
        boolean holdYaw) {
      return step(
          enabled, seed, now, yaw, pitch, targetYaw, targetPitch, quantum, holdYaw, Tuning.DEFAULT);
    }

    public Frame step(
        boolean enabled,
        long seed,
        long now,
        float yaw,
        float pitch,
        float targetYaw,
        float targetPitch,
        float quantum,
        boolean holdYaw,
        Tuning tuning) {
      return step(
          enabled, seed, now, yaw, pitch, targetYaw, targetPitch, quantum, holdYaw, tuning, 1.0);
    }

    public Frame step(
        boolean enabled,
        long seed,
        long now,
        float yaw,
        float pitch,
        float targetYaw,
        float targetPitch,
        float quantum,
        boolean holdYaw,
        Tuning tuning,
        double plannerSpeed) {
      if (holdYaw) {
        targetYaw = yaw;
      }
      if (!Float.isFinite(targetYaw) || !Float.isFinite(targetPitch)) {
        return new Frame(yaw, pitch);
      }
      float boundedPitch = Math.clamp(targetPitch, -90f, 90f);
      Frame direct = new Frame((float) (yaw + wrap(targetYaw - yaw)), boundedPitch);
      if (!enabled) {
        reset();
        return direct;
      }
      var future = loading;
      if (future == null) {
        prewarm();
        return new Frame(yaw, pitch);
      }
      if (!future.isDone()) {
        return new Frame(yaw, pitch);
      }
      if (failed || future.isCompletedExceptionally()) {
        return direct;
      }
      try {
        if (stream == null
            || heldYaw != holdYaw
            || now < lastTime
            || now - lastTime > MAX_GAP_NS
            || Math.abs(wrap(yaw - lastYaw)) > 0.01
            || Math.abs(pitch - lastPitch) > 0.01) {
          long sessionSeed = tuning.randomSeed() ? ThreadLocalRandom.current().nextLong() : seed;
          stream = new AbcStream(new AbcPolicy(future.join(), sessionSeed));
          plannerNanos = 0;
          lastTime = now;
          heldYaw = holdYaw;
          anchorYaw = yaw;
          goalYaw = yaw;
          goalPitch = Double.NaN;
          wantedYaw = yaw;
          wantedPitch = pitch;
          shapedYaw = yaw;
          shapedPitch = pitch;
          stream.reset(0, -pitch * COUNTS_PER_DEGREE);
        }
        long elapsedNanos = now - lastTime;
        double elapsedSeconds = elapsedNanos / 1e9;
        plannerNanos += elapsedNanos * plannerSpeed;
        lastTime = now;
        long time = (long) (plannerNanos / 1000);
        double nextYaw = goalYaw + wrap(targetYaw - goalYaw);
        if (nextYaw != goalYaw || boundedPitch != goalPitch) {
          goalYaw = nextYaw;
          goalPitch = boundedPitch;
          stream.updateTarget(
              (goalYaw - anchorYaw) * COUNTS_PER_DEGREE, -goalPitch * COUNTS_PER_DEGREE, time);
        }
        var samples = stream.advance(time);
        if (!samples.isEmpty()) {
          AbcStream.Sample intent = samples.getLast();
          wantedYaw = anchorYaw + intent.x() / COUNTS_PER_DEGREE;
          wantedPitch = Math.clamp(-intent.y() / COUNTS_PER_DEGREE, -90, 90);
        }
        shapedYaw =
            holdYaw
                ? yaw
                : shapedYaw
                    + shapeDelta(
                        wrap(wantedYaw - shapedYaw),
                        wrap(goalYaw - shapedYaw),
                        elapsedSeconds,
                        tuning.yawCap(),
                        tuning);
        shapedPitch =
            Math.clamp(
                shapedPitch
                    + shapeDelta(
                        wantedPitch - shapedPitch,
                        goalPitch - shapedPitch,
                        elapsedSeconds,
                        tuning.pitchCap(),
                        tuning),
                -90,
                90);
        float step = tuning.quantizeOutput() ? quantum : 0;
        lastYaw = (float) (yaw + quantize(wrap(shapedYaw - yaw), step));
        lastPitch = (float) Math.clamp(pitch + quantize(shapedPitch - pitch, step), -90, 90);
        return new Frame(lastYaw, lastPitch);
      } catch (RuntimeException | LinkageError failure) {
        System.getLogger(AbcRotation.class.getName())
            .log(System.Logger.Level.WARNING, "ABCurves rotation failed", failure);
        failed = true;
        return direct;
      }
    }
  }

  static double shapeDelta(
      double delta, double error, double elapsedSeconds, int cap, Tuning tuning) {
    if (Math.abs(error) <= tuning.deadzone() && tuning.deadzone() > 0) {
      return 0;
    }
    if (tuning.limitOvershoot() && (delta * error <= 0 || Math.abs(delta) > Math.abs(error))) {
      delta = delta * error <= 0 ? 0 : error;
    }
    if (tuning.dampingMillis() > 0) {
      delta *= -Math.expm1(-elapsedSeconds * 1000 / tuning.dampingMillis());
    }
    if (cap > 0) {
      double limit = cap * elapsedSeconds;
      delta = Math.clamp(delta, -limit, limit);
    }
    return delta;
  }

  static double wrap(double degrees) {
    return degrees - 360 * Math.floor((degrees + 180) / 360);
  }

  private static double quantize(double value, float quantum) {
    return quantum > 0 ? Math.rint(value / quantum) * quantum : value;
  }

  private AbcRotation() {}
}
