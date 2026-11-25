package ryzn.macro.client.feature.rotation.impl.abcurves;

import java.util.Map;
import ryzn.macro.client.feature.rotation.impl.abcurves.AbcFeatures.Vec;

final class AbcPolicy implements AbcStream.Policy {
  private enum Mode {
    MOVING,
    BRAKING,
    HOLDING
  }

  private final AbcModels models;
  private final AbcRandom motorRandom;
  private final AbcRandom selectorRandom;
  private final AbcRandom brakeRandom;
  private final double[][] meanBasis = new double[16][21];
  private final double[] meanCarry = new double[16];
  private Mode mode;
  private float[][] previous;
  private Vec origin;
  private Vec holdGoal;
  private Vec holdPosition;
  private double holdError;
  private double age;
  private double duration;
  private Vec brakeVelocity;
  private Vec brakeAcceleration;
  private Vec[] coefficients;
  private double innovationAt = Double.NaN;
  private boolean changed;
  private double resumeIntegral;
  private double stopIntegral;
  private double resumeBudget;
  private double stopBudget;

  AbcPolicy(AbcModels models, long seed) {
    this.models = models;
    motorRandom = new AbcRandom(seed);
    selectorRandom = new AbcRandom(seed ^ 0x5397a1);
    brakeRandom = new AbcRandom(seed ^ 0x1763b4);
    for (int i = 0; i < 16; i++) {
      for (int j = 0; j < 4; j++) {
        int row = i * 4 + j;
        meanCarry[i] += models.carry[row] / 4;
        for (int k = 0; k < 21; k++) {
          meanBasis[i][k] += models.basis[row][k] / 4;
        }
      }
    }
  }

  @Override
  public double[][] plan(AbcStream.History history, double x, double y, long timeMicros) {
    Vec position = new Vec(x, y);
    var features = new AbcFeatures(history, position, timeMicros);
    Vec goal = features.goal();
    double now = timeMicros / 1000.0;
    if (mode == null) {
      initialize(features, position, goal, now);
    }
    Mode old = mode;
    if (old != Mode.MOVING) {
      previous = null;
      for (int i = 608; i < 640; i++) {
        if (features.known(i) && features.target(i).minus(holdGoal).norm() > 1e-7) {
          changed = true;
          if (Double.isNaN(innovationAt)) {
            innovationAt = now - 639 + i;
          }
          break;
        }
      }
    }
    double innovationAge = Double.isNaN(innovationAt) ? 0 : Math.max(0, now - innovationAt);
    var event =
        features.event(age, holdGoal, holdPosition, holdError, innovationAge, old == Mode.MOVING);
    boolean innovation =
        old == Mode.HOLDING
            && (changed || position.minus(holdPosition).norm() > 1e-5 || event.distance() > 10);
    float[] hazard =
        old == Mode.MOVING || innovation
            ? ((float[][]) models.run("hazard", Map.of("context", event.context()))[0])[0]
            : new float[2];
    float stopDraw = selectorRandom.uniform();
    float resumeDraw = selectorRandom.uniform();
    boolean drewBrake = false;
    if (old == Mode.MOVING && features.hasGoal()) {
      stopIntegral += integratedHazard(hazard[0]);
      if (stopIntegral >= stopBudget) {
        startBraking(features, event, goal);
        drewBrake = true;
      }
    } else if (innovation && features.hasGoal()) {
      resumeIntegral += integratedHazard(hazard[1]);
      if (resumeIntegral >= resumeBudget) {
        mode = Mode.MOVING;
        age = 0;
        changed = false;
        innovationAt = Double.NaN;
        stopIntegral = 0;
        stopBudget = budget(stopDraw);
      }
    }

    if (!drewBrake) {
      brakeRandom.discardCategorical(16);
    }
    if (mode == Mode.MOVING) {
      age += 32;
      return motor(features, position);
    }
    motorRandom.discardCategorical(16);
    double[][] action = new double[32][2];
    if (mode == Mode.BRAKING) {
      Vec point = brakePoint(age);
      for (int i = 0; i < action.length; i++) {
        Vec next = brakePoint(age + i + 1);
        Vec delta = next.minus(point);
        action[i][0] = delta.x();
        action[i][1] = delta.y();
        point = next;
      }
      if (age + 32 >= duration) {
        mode = Mode.HOLDING;
        holdPosition = position.plus(point.minus(brakePoint(age)));
        holdGoal = goal;
        holdError = goal.minus(holdPosition).norm();
        changed = false;
        innovationAt = Double.NaN;
        resumeIntegral = 0;
        resumeBudget = budget(resumeDraw);
        age -= duration;
      }
    }
    age += 32;
    previous = null;
    return action;
  }

  private void initialize(AbcFeatures features, Vec position, Vec goal, double now) {
    mode = Mode.HOLDING;
    for (int i = 608; i < 640; i++) {
      Vec value = features.motion(i);
      if (Math.max(Math.abs(value.x()), Math.abs(value.y())) > 1e-12) {
        mode = Mode.MOVING;
        break;
      }
    }
    age = 160;
    holdGoal = goal;
    holdPosition = position;
    holdError = goal.minus(position).norm();
    changed = holdError > 1e-7;
    innovationAt = changed ? now : Double.NaN;
    resumeBudget = budget(selectorRandom.uniform());
    stopBudget = budget(brakeRandom.uniform());
  }

  private void startBraking(AbcFeatures features, AbcFeatures.Event event, Vec goal) {
    Object[] prediction = models.run("brake", Map.of("context", event.context()));
    int head = select(((float[][]) prediction[2])[0], brakeRandom);
    duration = ((float[][]) prediction[0])[0][head];
    if (!Double.isFinite(duration) || duration <= 0) {
      throw new IllegalStateException("ABCurves predicted an invalid brake duration");
    }
    float[][] shape = ((float[][][][]) prediction[1])[0][head];
    coefficients = new Vec[shape.length];
    for (int i = 0; i < shape.length; i++) {
      coefficients[i] =
          event.direction().scale(shape[i][0]).plus(event.direction().normal().scale(shape[i][1]));
    }
    mode = Mode.BRAKING;
    age = 0;
    brakeVelocity = features.motion(639);
    brakeAcceleration = features.mean(632, 8).minus(features.mean(624, 8)).scale(1.0 / 8);
    holdGoal = goal;
    holdError = event.distance();
    changed = false;
    innovationAt = Double.NaN;
  }

  private Vec brakePoint(double time) {
    double s = Math.clamp(time / duration, 0, 1);
    double z = 2 * s - 1;
    double s2 = s * s;
    double s3 = s2 * s;
    double s4 = s3 * s;
    double s5 = s4 * s;
    double tail = Math.pow(1 - s, 3);
    double envelope = 64 * s3 * tail;
    return brakeVelocity
        .scale((s - 6 * s3 + 8 * s4 - 3 * s5) * duration)
        .plus(brakeAcceleration.scale(.5 * s2 * tail * duration * duration))
        .plus(coefficients[0].scale(10 * s3 - 15 * s4 + 6 * s5))
        .plus(coefficients[1].scale(envelope))
        .plus(coefficients[2].scale(envelope * z))
        .plus(coefficients[3].scale(envelope * (3 * z * z - 1) / 2))
        .plus(coefficients[4].scale(envelope * (5 * z * z * z - 3 * z) / 2));
  }

  private double[][] motor(AbcFeatures features, Vec position) {
    var input = features.motor();
    Object[] prediction =
        models.run(
            "motor",
            Map.of("coarse", input.coarse(), "fine", input.fine(), "dynamics", input.dynamics()));
    float[][][] heads = ((float[][][][]) prediction[1])[0];
    float[][][] geometry = new float[16][16][2];
    float[][] unary = new float[16][16];
    float[][] pairs = new float[16][20];
    Vec incoming = features.motion(639);
    Vec step = origin == null ? Vec.ZERO : position.minus(origin);
    for (int head = 0; head < heads.length; head++) {
      for (int i = 0; i < 16; i++) {
        for (int axis = 0; axis < 2; axis++) {
          geometry[head][i][axis] =
              (float) decode(heads[head], meanBasis[i], meanCarry[i], incoming, axis);
          if (i < 8) {
            unary[head][i * 2 + axis] = (float) AbcFeatures.asinh(geometry[head][i][axis]);
            if (previous != null) {
              pairs[head][i * 2 + axis] =
                  (float) AbcFeatures.asinh(previous[i + 8][axis] - geometry[head][i][axis]);
            }
          }
        }
      }
      if (previous != null) {
        for (int axis = 0; axis < 2; axis++) {
          float actual = (float) (axis == 0 ? step.x() : step.y());
          float oldTotal = sumAxis(previous, axis, 16) * 4;
          float oldFirst = sumAxis(previous, axis, 8) * 4;
          float newFirst = sumAxis(geometry[head], axis, 8) * 4;
          pairs[head][16 + axis] = (float) AbcFeatures.asinh((oldTotal - actual - newFirst) / 32);
          pairs[head][18 + axis] = (float) AbcFeatures.asinh((oldFirst - actual) / 32);
        }
      }
    }
    Object[] choice =
        models.run(
            "choice",
            Map.of(
                "encoded", prediction[0],
                "unary_geometry", new float[][][] {unary},
                "pairs", new float[][][] {pairs},
                "previous_valid", new float[][] {{previous == null ? 0 : 1}}));
    int head = select(((float[][]) choice[0])[0], motorRandom);
    previous = geometry[head];
    origin = position;
    var result = new double[32][2];
    for (int i = 0; i < result.length; i++) {
      for (int axis = 0; axis < 2; axis++) {
        result[i][axis] = decode(heads[head], models.basis[i], models.carry[i], incoming, axis);
        if (!Double.isFinite(result[i][axis]) || Math.abs(result[i][axis]) > 1e5) {
          throw new IllegalStateException("ABCurves predicted an invalid motion sample");
        }
      }
    }
    return result;
  }

  private static float sumAxis(float[][] values, int axis, int length) {
    float sum = 0;
    for (int i = 0; i < length; i++) {
      sum += values[i][axis];
    }
    return sum;
  }

  private static double decode(
      float[][] coefficients, double[] basis, double carry, Vec incoming, int axis) {
    double value = 0;
    for (int i = 0; i < basis.length; i++) {
      value += basis[i] * coefficients[i][axis];
    }
    return value + carry * (axis == 0 ? incoming.x() : incoming.y());
  }

  private static int select(float[] logits, AbcRandom random) {
    float max = Float.NEGATIVE_INFINITY;
    for (float logit : logits) {
      max = Math.max(max, logit);
    }
    float total = 0;
    float[] probabilities = new float[logits.length];
    for (int i = 0; i < logits.length; i++) {
      probabilities[i] = (float) Math.exp(logits[i] - max);
      total += probabilities[i];
    }
    if (!Float.isFinite(total) || total <= 0) {
      throw new IllegalStateException("ABCurves predicted invalid probabilities");
    }
    int best = 0;
    float bestScore = Float.NEGATIVE_INFINITY;
    for (int i = 0; i < probabilities.length; i++) {
      float score = (probabilities[i] / total) / random.exponential();
      if (score > bestScore) {
        best = i;
        bestScore = score;
      }
    }
    return best;
  }

  private static double integratedHazard(float logit) {
    float probability = 1f / (1f + (float) Math.exp(-Math.clamp(logit, -40, 40)));
    if (!Float.isFinite(probability)) {
      throw new IllegalStateException("ABCurves predicted an invalid hazard");
    }
    return -Math.log(Math.max(1e-8, 1 - (double) probability));
  }

  private static float budget(float probability) {
    return (float) -Math.log(Math.max(1e-8, probability));
  }
}
