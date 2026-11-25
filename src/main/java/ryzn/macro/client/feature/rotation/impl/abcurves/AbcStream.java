package ryzn.macro.client.feature.rotation.impl.abcurves;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The continuous planner's causal 1 ms sample clock and 32 ms action buffer. */
final class AbcStream {
  static final int HISTORY = 640;
  static final int COMMIT = 32;
  private static final int WARM_HISTORY = 160;
  private static final int SAMPLE_MICROS = 1000;

  record Sample(long timeMicros, double x, double y) {}

  interface Policy {
    double[][] plan(History history, double x, double y, long timeMicros);
  }

  static final class History {
    private final double[][] motion = new double[HISTORY][2];
    private final Sample[] targets = new Sample[HISTORY];
    private final boolean[] motionKnown = new boolean[HISTORY];
    private int cursor;

    private int slot(int index) {
      return (cursor + index) % HISTORY;
    }

    boolean motionKnown(int index) {
      return motionKnown[slot(index)];
    }

    double motion(int index, int axis) {
      return motion[slot(index)][axis];
    }

    boolean targetKnown(int index, long throughMicros) {
      Sample target = targets[slot(index)];
      return target != null && target.timeMicros <= throughMicros;
    }

    double goalX(int index) {
      Sample target = targets[slot(index)];
      return target == null ? 0 : target.x;
    }

    double goalY(int index) {
      Sample target = targets[slot(index)];
      return target == null ? 0 : target.y;
    }

    private void append(double dx, double dy, Sample target) {
      motion[cursor][0] = dx;
      motion[cursor][1] = dy;
      targets[cursor] = target;
      motionKnown[cursor] = true;
      cursor = (cursor + 1) % HISTORY;
    }

    private void clearSlot(int slot) {
      Arrays.fill(motion[slot], 0);
      targets[slot] = null;
      motionKnown[slot] = false;
    }
  }

  private final Policy policy;
  private final History history = new History();
  private final ArrayDeque<Sample> receipts = new ArrayDeque<>();
  private final double[][] points = new double[COMMIT][2];
  private double[][] actions;
  private int pending;
  private long clock;
  private long requested;
  private long lastReceipt;
  private Sample target;
  private double x;
  private double y;
  private boolean observed;
  private boolean failed;
  private int decisions;

  AbcStream(Policy policy) {
    this.policy = policy;
    reset(0, 0);
  }

  void reset(double x, double y) {
    requireFinite(x, y);
    this.x = x;
    this.y = y;
    clock = 0;
    requested = 0;
    lastReceipt = -1;
    pending = COMMIT;
    target = null;
    observed = false;
    failed = false;
    decisions = 0;
    receipts.clear();
    history.cursor = 0;
    for (int i = 0; i < HISTORY; i++) {
      history.clearSlot(i);
      history.motionKnown[i] = i >= HISTORY - WARM_HISTORY;
    }
  }

  void updateTarget(double x, double y, long receiptMicros) {
    requireFinite(x, y);
    if (receiptMicros < Math.max(clock, lastReceipt)) {
      throw new IllegalArgumentException("Target receipt precedes the stream clock");
    }
    receipts.addLast(new Sample(receiptMicros, x, y));
    lastReceipt = receiptMicros;
  }

  int decisions() {
    return decisions;
  }

  List<Sample> advance(long throughMicros) {
    if (failed) {
      throw new IllegalStateException("Reset the failed rotation stream before advancing");
    }
    if (throughMicros < requested) {
      throw new IllegalArgumentException("Advance clock must be monotonic");
    }
    int count = Math.toIntExact((throughMicros - clock) / SAMPLE_MICROS);
    var samples = new ArrayList<Sample>(count);
    requested = throughMicros;
    receiveTargets();
    for (int i = 0; i < count; i++) {
      if (pending == COMMIT && target != null) {
        prepareAction();
      }
      if (pending == COMMIT) {
        history.append(0, 0, target);
      } else {
        x = points[pending][0];
        y = points[pending][1];
        history.append(actions[pending][0], actions[pending][1], target);
        pending++;
      }
      clock += SAMPLE_MICROS;
      receiveTargets();
      samples.add(new Sample(clock, x, y));
    }
    return samples;
  }

  private void receiveTargets() {
    while (!receipts.isEmpty() && receipts.getFirst().timeMicros <= clock) {
      target = receipts.removeFirst();
      history.targets[history.slot(HISTORY - 1)] = target;
    }
  }

  private void prepareAction() {
    if (!observed) {
      for (int i = 0; i < HISTORY - WARM_HISTORY; i++) {
        history.clearSlot(history.slot(i));
      }
      observed = true;
    }
    try {
      actions = policy.plan(history, x, y, clock);
      if (actions.length != COMMIT) {
        throw new IllegalStateException("Planner must return a complete action block");
      }
      double originX = x;
      double originY = y;
      for (int block = 0; block < COMMIT; block += 8) {
        double sumX = 0;
        double sumY = 0;
        for (int i = block; i < block + 8; i++) {
          double[] action = actions[i];
          if (action.length != 2) {
            throw new IllegalStateException("Planner action must have two axes");
          }
          sumX += action[0];
          sumY += action[1];
          points[i][0] = originX + sumX;
          points[i][1] = originY + sumY;
          requireFinite(points[i][0], points[i][1]);
        }
        originX = points[block + 7][0];
        originY = points[block + 7][1];
      }
      pending = 0;
      decisions++;
    } catch (RuntimeException exception) {
      failed = true;
      throw exception;
    }
  }

  private static void requireFinite(double x, double y) {
    if (!Double.isFinite(x) || !Double.isFinite(y)) {
      throw new IllegalArgumentException("Rotation position must be finite");
    }
  }
}
