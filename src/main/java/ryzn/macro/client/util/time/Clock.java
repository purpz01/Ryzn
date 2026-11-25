package ryzn.macro.client.util.time;

import java.util.function.LongSupplier;

/**
 * Minimal millisecond stopwatch used by the path executor for stuck / jump-fail timing. {@link
 * #schedule(long)} arms the clock for a duration; {@link #passed()} reports whether that duration
 * has elapsed.
 */
public class Clock {

  /** Millisecond source for every executor timer. The headless sandbox swaps in simulated time. */
  private static volatile LongSupplier source = System::currentTimeMillis;

  private long endTime = -1L;

  /** The current time on the executor's clock, in milliseconds. */
  public static long now() {
    return source.getAsLong();
  }

  /** Arm the clock to fire {@code ms} milliseconds from now. */
  public void schedule(long ms) {
    this.endTime = now() + ms;
  }

  /** True while the clock is armed (between {@link #schedule(long)} and {@link #reset()}). */
  public boolean isScheduled() {
    return endTime != -1L;
  }

  /** True if armed and the scheduled duration has elapsed. */
  public boolean passed() {
    return isScheduled() && now() >= endTime;
  }

  /** Disarm the clock. */
  public void reset() {
    this.endTime = -1L;
  }
}
