package ryzn.macro.client.feature.rotation.impl.abcurves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class AbcRotationTest {
  @Test
  void changingPlannerSpeedKeepsTheSameContinuousTimeline() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          AbcRotation.prewarm();
          while (AbcRotation.status().equals("Loading")) {
            Thread.sleep(5);
          }
          assertEquals("Ready", AbcRotation.status());
          var adjustable = new AbcRotation.Session();
          var reference = new AbcRotation.Session();
          var actual = new AbcRotation.Frame(0, 35);
          var expected = actual;
          long referenceNanos = 0;
          for (int tick = 0; tick <= 40; tick++) {
            double speed = tick < 20 ? 2.0 : 0.75;
            if (tick > 0) {
              referenceNanos += (long) (20_000_000L * speed);
            }
            float targetYaw = (float) (50 * Math.sin(tick * Math.PI / 25));
            actual =
                adjustable.step(
                    true,
                    7,
                    tick * 20_000_000L,
                    actual.yaw(),
                    actual.pitch(),
                    targetYaw,
                    35,
                    0.15f,
                    false,
                    AbcRotation.Tuning.DEFAULT,
                    speed);
            expected =
                reference.step(
                    true,
                    7,
                    referenceNanos,
                    expected.yaw(),
                    expected.pitch(),
                    targetYaw,
                    35,
                    0.15f);
            assertEquals(expected, actual);
          }
        });
  }

  @Test
  void fasterPlannerTracksMovingSweepWithLessYawError() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          AbcRotation.prewarm();
          while (AbcRotation.status().equals("Loading")) {
            Thread.sleep(5);
          }
          assertEquals("Ready", AbcRotation.status());
          assertTrue(sweepYawError(1.5) < sweepYawError(1.0));
        });
  }

  private static double sweepYawError(double plannerSpeed) {
    var session = new AbcRotation.Session();
    var frame = new AbcRotation.Frame(0, 35);
    double error = 0;
    for (int tick = 0; tick <= 120; tick++) {
      float targetYaw = (float) (50 * Math.sin(tick * Math.PI / 25));
      frame =
          session.step(
              true,
              7,
              tick * 20_000_000L,
              frame.yaw(),
              frame.pitch(),
              targetYaw,
              35,
              0.15f,
              false,
              AbcRotation.Tuning.DEFAULT,
              plannerSpeed);
      error += Math.abs(targetYaw - frame.yaw());
    }
    return error;
  }

  @Test
  void tunesCameraOutputWithoutChangingPlannerTime() {
    var tuning = new AbcRotation.Tuning(false, 100, 60, 0, 0.5, true, false);
    assertEquals(5, AbcRotation.shapeDelta(90, 90, .05, 100, tuning), 1e-7);
    assertEquals(-3, AbcRotation.shapeDelta(-90, -90, .05, 60, tuning), 1e-7);
    assertEquals(0, AbcRotation.shapeDelta(.4, .4, .05, 100, tuning), 1e-7);
    assertEquals(1, AbcRotation.shapeDelta(10, 1, .05, 100, tuning), 1e-7);
    var damped = new AbcRotation.Tuning(false, 0, 0, 100, 0, false, true);
    assertEquals(10 * -Math.expm1(-.5), AbcRotation.shapeDelta(10, 20, .05, 0, damped), 1e-7);
  }

  @Test
  void directModeWrapsYawAndBoundsPitch() {
    var session = new AbcRotation.Session();
    assertEquals(new AbcRotation.Frame(181, 90), session.step(false, 7, 0, 179, 5, -179, 100, 0));
    assertEquals(
        new AbcRotation.Frame(179, -90), session.step(false, 7, 0, 179, 5, -179, -100, 0, true));
  }

  @Test
  void learnedModeTurnsAcrossTheSeamAndReanchorsAfterTakeover() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          AbcRotation.prewarm();
          while (AbcRotation.status().equals("Loading")) {
            Thread.sleep(5);
          }
          assertEquals("Ready", AbcRotation.status());
          var session = new AbcRotation.Session();
          var frame = new AbcRotation.Frame(165, 0);
          for (int tick = 0; tick <= 40; tick++) {
            var next =
                session.step(
                    true, 7, tick * 50_000_000L, frame.yaw(), frame.pitch(), -176, 5, 0.15f);
            assertTrue(Math.abs(next.yaw() - frame.yaw()) < 20, "yaw jumped across the seam");
            frame = next;
          }
          assertEquals(184, frame.yaw(), 2.0);
          assertEquals(5, frame.pitch(), 2.0);
          var reanchored = session.step(true, 7, 2_050_000_000L, 80, -20, 90, 10, 0.15f);
          assertEquals(new AbcRotation.Frame(80, -20), reanchored);
          frame = reanchored;
          for (int tick = 42; tick <= 75; tick++) {
            frame =
                session.step(
                    true, 7, tick * 50_000_000L, frame.yaw(), frame.pitch(), 90, 10, 0.15f, true);
            assertEquals(80, frame.yaw());
          }
          assertEquals(10, frame.pitch(), 2.0);
        });
  }
}
