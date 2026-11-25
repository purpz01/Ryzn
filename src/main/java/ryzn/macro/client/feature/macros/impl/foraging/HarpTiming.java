package ryzn.macro.client.feature.macros.impl.foraging;

import java.util.Arrays;
import net.minecraft.client.Minecraft;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.hud.PingMeter;

/**
 * A clock locked to the harp's beat, so a click can be aimed at the song instead of at the network.
 *
 * <p>The note rows only change when the server sends a container update, so the gap between two
 * note patterns is the note-advance interval as the harp experiences it - server tempo and TPS both
 * land in the same number, with nothing to estimate.
 *
 * <p>What that number is not is a schedule. The updates do not arrive on the beat they describe:
 * one steady 350ms song was logged arriving as 446, 254, 450, 256, 545, 152 - jittery pairs that
 * still sum to two beats every time. Timing a click off the last arrival therefore aims it at the
 * jitter, which is worth over a hundred milliseconds of error on a window only 350ms wide.
 *
 * <p>So the beat runs free on the measured period and each observation nudges its phase by a
 * fraction of the error. Jitter averages out over a few beats, a real tempo change is still
 * followed, and {@link #timeOfBeat} can say when any future beat lands.
 *
 * <p>The period itself comes from the same reasoning taken further. A mean of the gaps is hostage
 * to one bad sample and a median of them throws away most of what was measured, so once a run is a
 * few beats old the period is fitted across the whole of it - see {@link #spanFitMs} - and the
 * median only stands guard over that.
 *
 * <p>Pair it with {@link PingMeter} and you have both halves of the timing problem - when the note
 * lands, and how far ahead of that a click has to leave.
 */
final class HarpTiming {

  /**
   * Only a single update split across two packets is shorter than this. The floor has to stay well
   * under the fastest song: jitter drags real gaps of a 234ms song down to about 85ms, and a floor
   * that clips those keeps only the long half of the noise, which biases the median upward and
   * quietly aims every click late. Rejecting tails is the median's job, not the filter's.
   */
  private static final long MIN_ADVANCE_MS = 40L;

  private static final long MAX_ADVANCE_MS = 1500L;

  /** Odd, so the median is a real sample and not an average of the middle two. */
  private static final int MAX_SAMPLES = 15;

  /**
   * Beats before the whole-run fit says anything. Two: the fit is the mean gap, and the jitter is
   * zero-mean and bounded, so it beats a median of the same handful of samples immediately - a
   * median of two picks one of them, and either one can be 30% out on its own.
   */
  private static final int SPAN_MIN_BEATS = 2;

  /**
   * Samples before the median is steady enough to overrule the fit rather than just disagree with
   * it.
   */
  private static final int MEDIAN_MIN_SAMPLES = 6;

  /** How far the fit may sit from the median before the median is believed instead. */
  private static final double SPAN_SANITY = 0.25;

  /** Share of each observation's phase error folded into the clock: quick to lock, then steady. */
  private static final float PHASE_TRACK_LOCK = 0.35f;

  private static final float PHASE_TRACK_HOLD = 0.10f;
  private static final int LOCK_BEATS = 4;

  private final long[] samples = new long[MAX_SAMPLES];
  private final long[] ordered = new long[MAX_SAMPLES];
  private int sampleCount;
  private int sampleIndex;

  private String lastPattern = "";
  private long lastChangeMs;
  private long lastAdvanceMs = -1L;

  /** Beats counted since the harp opened, and the phase-corrected clock reading of that beat. */
  private long beatIndex;

  private long firstBeatMs;
  private long beatAtMs;

  /**
   * Feed the current note pattern. Returns true when it differs from the last one, i.e. the notes
   * just advanced a step.
   */
  boolean observe(String pattern) {
    return observe(pattern, System.currentTimeMillis());
  }

  /**
   * Same, with the clock reading supplied, so the beat arithmetic can be exercised off a real game.
   */
  boolean observe(String pattern, long now) {
    if (pattern.equals(lastPattern)) {
      return false;
    }
    final long sincePrevious = lastChangeMs > 0L ? now - lastChangeMs : -1L;
    // One advance arriving as several slot packets is still one beat, and the half-drawn grid in
    // between is not a note position. Counting those pieces both inflates the beat total - which is
    // the divisor of the whole-run fit, so the period reads short - and shoves the phase most of a
    // note forward, which is a miss on a window one note wide.
    if (sincePrevious >= 0L && sincePrevious < MIN_ADVANCE_MS) {
      lastPattern = pattern;
      return true;
    }
    if (!lastPattern.isEmpty() && sincePrevious > 0L) {
      recordSpan(sincePrevious);
    }
    lastPattern = pattern;
    // trackBeat reads the fitted period, and the fit is a span over a beat count. Moving the span's
    // end on before the count catches up makes every period read one beat too long, which is enough
    // on its own to walk the clock ahead of the song a note at a time.
    trackBeat(now);
    lastChangeMs = now;
    if (DebugLog.isEnabled(DebugLog.Category.MACRO)) {
      DebugLog.detail(
          DebugLog.Category.MACRO,
          "[harp] %s advance=%dms period=%dms beat=%d ping=%dms",
          pattern,
          lastAdvanceMs,
          advanceMs(),
          beatIndex,
          ping());
    }
    return true;
  }

  /** Interval between note advances in ms, or -1 before the first one has been timed. */
  int advanceMs() {
    final int median = medianGapMs();
    if (median <= 0) {
      return -1;
    }
    final int fitted = spanFitMs();
    if (fitted <= 0) {
      return median;
    }
    // A miscounted beat would send the fit badly wrong; the median is the sanity check on it, but
    // only once it has enough samples to be steadier than the thing it is checking.
    if (sampleCount < MEDIAN_MIN_SAMPLES) {
      return fitted;
    }
    return Math.abs(fitted - median) <= median * SPAN_SANITY ? fitted : median;
  }

  /**
   * The period fitted across the whole run: elapsed time over the beats inside it.
   *
   * <p>One gap on its own says almost nothing - the same steady song produces 152ms and then 499ms
   * - but the jitter is bounded and cancels, so dividing the span by its beat count converges on
   * the real period and keeps sharpening as the song plays. Six beats in, that already beats any
   * average of the gaps themselves. Returns -1 until there are enough beats to be worth it.
   */
  private int spanFitMs() {
    if (beatIndex < SPAN_MIN_BEATS || firstBeatMs <= 0L) {
      return -1;
    }
    return (int) ((lastChangeMs - firstBeatMs) / beatIndex);
  }

  private int medianGapMs() {
    if (sampleCount <= 0) {
      return -1;
    }
    System.arraycopy(samples, 0, ordered, 0, sampleCount);
    Arrays.sort(ordered, 0, sampleCount);
    return (int) ordered[sampleCount / 2];
  }

  /**
   * Beats seen so far. A note {@code n} advances above its pad lands on beat {@code beatIndex + n}.
   */
  long beatIndex() {
    return beatIndex;
  }

  /** Full game-layer round trip in ms, or -1 before the first pong. */
  int ping() {
    return PingMeter.millis(Minecraft.getInstance());
  }

  /**
   * How long before a beat a click has to be sent to arrive {@code marginMs} into that note's
   * window. The click needs a full round trip to get there, so it leaves {@code ping - margin}
   * early. Returns -1 until both the period and the ping are known, which is the caller's signal to
   * stay reactive.
   */
  int leadMs(int marginMs) {
    final int period = advanceMs();
    final int ping = ping();
    if (period <= 0 || ping < 0) {
      return -1;
    }
    return Math.max(0, period + marginMs - ping);
  }

  /**
   * How many note advances happen while our click is in flight. Above 1 the pad has already moved
   * on by the time a click sent on sight would land, which is the point where reacting cannot win.
   */
  float advancesInFlight() {
    final int period = advanceMs();
    final int ping = ping();
    if (period <= 0 || ping <= 0) {
      return -1f;
    }
    return ping / (float) period;
  }

  void reset() {
    sampleCount = 0;
    sampleIndex = 0;
    lastPattern = "";
    lastChangeMs = 0L;
    lastAdvanceMs = -1L;
    beatIndex = 0L;
    beatAtMs = 0L;
    firstBeatMs = 0L;
  }

  /**
   * Fold one observed advance into the beat clock. How many beats actually passed is read from the
   * elapsed time rather than assumed to be one, so an update we never saw - two beats coalesced
   * into a single packet - shifts the count instead of corrupting the phase. Rounding to the
   * nearest beat also bounds the residual error to half a period, so the clock cannot drift a whole
   * note out.
   */
  private void trackBeat(long now) {
    final int period = advanceMs();
    if (period <= 0 || beatAtMs <= 0L) {
      beatIndex = 0L;
      beatAtMs = now;
      firstBeatMs = now;
      return;
    }
    final long steps = Math.max(1L, Math.round((now - beatAtMs) / (double) period));
    final long predicted = beatAtMs + steps * period;
    beatIndex += steps;
    // Songs are short enough that a slow lock costs real notes, so the clock chases hard for the
    // first few beats and only then settles into averaging the jitter out.
    final float track = beatIndex <= LOCK_BEATS ? PHASE_TRACK_LOCK : PHASE_TRACK_HOLD;
    beatAtMs = predicted + Math.round((now - predicted) * track);
  }

  /**
   * Fold one observed gap into the samples. A gap is not always a single advance: a lane that is
   * empty for a few beats, or a run of notes that all sit in the same rows, leaves the falling grid
   * unchanged until the next one moves. Dividing by the beats the gap actually spans keeps the
   * median measuring one period instead of quietly averaging in multiples of it.
   */
  private void recordSpan(long gap) {
    final int period = advanceMs();
    final long beats = period > 0 ? Math.max(1L, Math.round(gap / (double) period)) : 1L;
    final long each = gap / beats;
    if (each >= MIN_ADVANCE_MS && each <= MAX_ADVANCE_MS) {
      record(each);
    }
  }

  private void record(long delta) {
    lastAdvanceMs = delta;
    samples[sampleIndex] = delta;
    sampleIndex = (sampleIndex + 1) % MAX_SAMPLES;
    if (sampleCount < MAX_SAMPLES) {
      sampleCount++;
    }
  }
}
