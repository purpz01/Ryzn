package ryzn.macro.client.feature.rotation.impl.abcurves;

import java.util.Arrays;

final class AbcFeatures {
  private static final double VELOCITY = 1.1982087601807259;
  private static final double POSITION = 81.73350722609987;
  private static final double[] SCALES = {
    10, .1, .1, .1, 1, .1, .1, .1, .1, .1, .01, 10, 128, 1, 1, 1, 128, 10, 10, .1, 1, 10
  };

  record Motor(float[][][] coarse, float[][] fine, float[][] dynamics) {}

  record Event(float[][] context, Vec direction, double distance) {}

  record Vec(double x, double y) {
    static final Vec ZERO = new Vec(0, 0);

    Vec plus(Vec other) {
      return new Vec(x + other.x, y + other.y);
    }

    Vec minus(Vec other) {
      return new Vec(x - other.x, y - other.y);
    }

    Vec scale(double scale) {
      return new Vec(x * scale, y * scale);
    }

    double norm() {
      return Math.sqrt(x * x + y * y);
    }

    double dot(Vec other) {
      return x * other.x + y * other.y;
    }

    Vec normal() {
      return new Vec(-y, x);
    }
  }

  private final AbcStream.History history;
  private final boolean[] known = new boolean[AbcStream.HISTORY];
  private final Vec position;
  private int motionCount;

  AbcFeatures(AbcStream.History history, Vec position, long time) {
    this.history = history;
    this.position = position;
    for (int i = 0; i < known.length; i++) {
      known[i] = history.targetKnown(i, time + (i - 639) * 1000L);
      if (history.motionKnown(i)) {
        motionCount++;
      }
    }
  }

  boolean hasGoal() {
    return known[639];
  }

  Vec goal() {
    return hasGoal() ? target(639) : position;
  }

  Vec target(int i) {
    return new Vec(history.goalX(i), history.goalY(i));
  }

  boolean known(int i) {
    return known[i];
  }

  Vec motion(int i) {
    return new Vec(history.motion(i, 0), history.motion(i, 1));
  }

  Vec mean(int start, int length) {
    double x = 0;
    double y = 0;
    for (int i = start; i < start + length; i++) {
      x += history.motion(i, 0);
      y += history.motion(i, 1);
    }
    return new Vec(x / length, y / length);
  }

  private Vec targetVelocity(int i, int lag) {
    return i >= lag && known[i] && known[i - lag]
        ? target(i).minus(target(i - lag)).scale(1.0 / lag)
        : Vec.ZERO;
  }

  Motor motor() {
    float[][] coarse = new float[70][9];
    Vec[] positions = new Vec[640];
    Vec point = position;
    for (int i = 639; i >= 0; i--) {
      positions[i] = point;
      point = point.minus(motion(i));
    }
    for (int bucket = 0, start = 0; bucket < 70; bucket++) {
      int width = bucket < 30 ? 16 : 4;
      int end = start + width - 1;
      Vec velocity = mean(start, width).scale(1 / VELOCITY);
      Vec relative =
          known[end] && history.motionKnown(end)
              ? target(end).minus(positions[end]).scale(1 / POSITION)
              : Vec.ZERO;
      Vec targetVelocity = targetVelocity(end, 32).scale(1 / VELOCITY);
      int validMotion = 0;
      for (int i = start; i <= end; i++) {
        if (history.motionKnown(i)) {
          validMotion++;
        }
      }
      coarse[bucket] =
          new float[] {
            (float) velocity.x,
            (float) velocity.y,
            (float) asinh(relative.x),
            (float) asinh(relative.y),
            (float) targetVelocity.x,
            (float) targetVelocity.y,
            known[end] ? 1 : 0,
            (float) validMotion / width,
            width / 16f
          };
      start += width;
    }

    float[] fine = new float[54];
    for (int i = 0; i < 16; i++) {
      Vec velocity = motion(624 + i).scale(1 / VELOCITY);
      fine[2 * i] = (float) velocity.x;
      fine[2 * i + 1] = (float) velocity.y;
      fine[32 + i] = history.motionKnown(624 + i) ? 1 : 0;
    }
    Vec error = goal().minus(position).scale(1 / POSITION);
    Vec targetVelocity = targetVelocity(639, 32).scale(1 / VELOCITY);
    fine[48] = (float) asinh(error.x);
    fine[49] = (float) asinh(error.y);
    fine[50] = (float) targetVelocity.x;
    fine[51] = (float) targetVelocity.y;
    fine[52] = hasGoal() ? 1 : 0;
    fine[53] = motionCount / 640f;

    float[] dynamics = new float[8];
    for (int axis = 0; axis < 2; axis++) {
      dynamics[axis] = fine[30 + axis];
      float previous = 0;
      float recent = 0;
      boolean valid = true;
      for (int i = 8; i < 12; i++) {
        previous += fine[2 * i + axis];
        recent += fine[2 * (i + 4) + axis];
        valid &= fine[32 + i] > .5f && fine[36 + i] > .5f;
      }
      dynamics[2 + axis] = valid ? (recent - previous) / 4 * (float) VELOCITY : 0;
    }
    double ex = Math.sinh(fine[48]) * POSITION;
    double ey = Math.sinh(fine[49]) * POSITION;
    double length = Math.sqrt(ex * ex + ey * ey + 100);
    float dx = (float) (ex / length);
    float dy = (float) (ey / length);
    float vx = dynamics[0] - fine[50];
    float vy = dynamics[1] - fine[51];
    dynamics[4] = vx * dx + vy * dy;
    dynamics[5] = -vx * dy + vy * dx;
    dynamics[6] = dynamics[2] * dx + dynamics[3] * dy;
    dynamics[7] = -dynamics[2] * dy + dynamics[3] * dx;
    return new Motor(new float[][][] {coarse}, new float[][] {fine}, new float[][] {dynamics});
  }

  Event event(
      double holdAge,
      Vec holdGoal,
      Vec holdPosition,
      double initialError,
      double innovationAge,
      boolean moving) {
    Vec goal = goal();
    Vec error = goal.minus(position);
    double distance = error.norm();
    Vec axis = error.scale(1 / Math.max(distance, 1e-8));
    Vec v1 = motion(639);
    Vec v8 = mean(632, 8);
    Vec v32 = mean(608, 32);
    double s8 = v8.norm();
    double s32 = v32.norm();
    Vec tv8 = targetVelocity(639, 8);
    Vec tv32 = targetVelocity(639, 32);
    Vec tv128 = targetVelocity(639, 128);
    double ts32 = tv32.norm();
    double growth =
        hasGoal() && known[607]
            ? (distance - target(607).minus(position.minus(v32.scale(32))).norm()) / 32
            : 0;
    Vec acceleration = v8.minus(mean(624, 8)).scale(1.0 / 8);
    int age = 128;
    int knownCount = 0;
    double excursion = 0;
    for (int i = 512; i < 640; i++) {
      if (known[i]) {
        knownCount++;
        double offset = target(i).minus(goal).norm();
        excursion = Math.max(excursion, offset);
        if (offset > 1e-8) {
          age = 639 - i;
        }
      } else {
        age = 639 - i;
      }
    }
    float[] raw = {
      (float) distance,
      (float) v1.norm(),
      (float) s8,
      (float) s32,
      (float) (s8 / Math.max(s32, .005)),
      (float) ts32,
      (float) tv128.norm(),
      (float) v8.dot(axis),
      (float) Math.abs(v8.dot(axis.normal())),
      (float) growth,
      (float) (-acceleration.dot(v8) / Math.max(s8, .005)),
      (float) excursion,
      age,
      knownCount / 128f,
      hasGoal() ? 1 : 0,
      motionCount / 640f,
      (float) Math.min(holdAge, 256),
      moving ? 0 : (float) goal.minus(holdGoal).norm(),
      moving ? 0 : (float) position.minus(holdPosition).norm(),
      (float) tv8.norm(),
      (float) (v8.dot(tv32) / Math.max(s8 * ts32, 1e-8)),
      moving ? 0 : (float) initialError
    };
    Vec direction =
        distance > 1e-6
            ? error.scale(1 / Math.max(distance, 1e-9))
            : s8 > 1e-6 ? v8.scale(1 / Math.max(s8, 1e-9)) : new Vec(1, 0);
    Vec normal = direction.normal();
    float[] context = Arrays.copyOf(raw, 32);
    for (int i = 0; i < raw.length; i++) {
      context[i] = (float) asinh(raw[i] / SCALES[i]);
    }
    Vec[] extraAxes = {v1, acceleration.scale(8), tv32, tv128};
    for (int i = 0; i < extraAxes.length; i++) {
      context[22 + i * 2] = (float) asinh(extraAxes[i].dot(direction));
      context[23 + i * 2] = (float) asinh(extraAxes[i].dot(normal));
    }
    context[30] = (float) asinh(v8.dot(normal));
    context[31] = (float) asinh(Math.min(2048, innovationAge) / 128);
    return new Event(new float[][] {context}, direction, raw[0]);
  }

  static double asinh(double value) {
    double x = Math.abs(value);
    return Math.copySign(Math.log1p(x + x * (x / (1 + Math.hypot(1, x)))), value);
  }
}
