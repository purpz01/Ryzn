package ryzn.macro.client.feature.pathfinder.path;

/**
 * A boolean that refuses to change until the new value has held for a few straight ticks.
 *
 * <p>Every gate that picks the BODY steering target is a razor-edge test: {@code onGround} while
 * auto-step bounces the feet, "am I within a block of the node" while the body inches against a
 * wall, a raycast grazing the corner block it is rubbing. Read clean each tick, all of them chatter
 * - and each flip jumps the steering target by {@link PathExecutor#LEDGE_AIM_OVERSHOOT} blocks or
 * onto a different node, which swings the bearing far enough to cross a 67.5 degree key cone and
 * rewrite the whole key set. That is the corner shuffle: caught in the log as two samples one trace
 * apart with the same cursor, the same node and the yaw identical to 0.1 degrees, yet {@code go}
 * two blocks apart and moveErr 45.6 -> 146.5.
 *
 * <p>The body cannot act on a one-tick opinion anyway - it takes several ticks to move a block - so
 * making the gates agree with themselves for {@code holdTicks} before flipping costs nothing real
 * and removes the chatter at its source.
 *
 * <p>ponytail: one counter, no timers, no per-gate tuning. Sample it once per tick and read it as
 * many times as you like.
 */
final class StickyGate {

  private final int holdTicks;
  private boolean state;
  private int pending;

  StickyGate(int holdTicks, boolean initial) {
    this.holdTicks = holdTicks;
    this.state = initial;
  }

  /** Feed this tick's raw reading; returns the debounced value. */
  boolean update(boolean raw) {
    if (raw == state) {
      pending = 0;
    } else if (++pending >= holdTicks) {
      state = raw;
      pending = 0;
    }
    return state;
  }

  boolean get() {
    return state;
  }

  void reset(boolean value) {
    state = value;
    pending = 0;
  }
}
