package ryzn.macro.client.feature.hud;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;

/**
 * Estimates Hypixel server tick rate from distinct ping packets. Samples are timestamped on the
 * network thread to preserve arrival timing between client ticks.
 */
public final class TpsMeter {

  /** ~2s of history at a healthy 20 TPS - long enough to be steady, short enough to react. */
  private static final int WINDOW = 40;

  private static final int MIN_SAMPLES = 20;

  /**
   * A gap this long is a freeze, a lobby switch, or a reconnect, never a tick - drop the window.
   */
  private static final long BREAK_NANOS = 1_000_000_000L;

  private static final long[] arrivals = new long[WINDOW];
  private static int count;
  private static int next;
  private static int lastPingId;

  private static volatile float tps = -1f;
  private static volatile long lastArrival;

  private TpsMeter() {}

  /** Called for every inbound packet on the Netty thread; anything but a ping is ignored. */
  public static void onInboundPacket(Packet<?> packet) {
    if (!(packet instanceof ClientboundPingPacket ping)) {
      return;
    }
    if (ping.getId() == lastPingId) {
      return;
    }
    lastPingId = ping.getId();

    final long now = System.nanoTime();
    if (count > 0 && now - lastArrival > BREAK_NANOS) {
      count = 0;
      next = 0;
      tps = -1f;
    }
    lastArrival = now;
    arrivals[next] = now;
    next = (next + 1) % WINDOW;
    if (count < WINDOW) {
      count++;
    }

    if (count < MIN_SAMPLES) {
      return;
    }
    final long oldest = arrivals[count < WINDOW ? 0 : next];
    final long span = now - oldest;
    tps = span <= 0L ? -1f : Math.min(20f, (float) ((count - 1) * 1_000_000_000d / span));
  }

  /** Measured rate, or -1 with no server on the other end / before the window fills. */
  public static float tps() {
    return System.nanoTime() - lastArrival > BREAK_NANOS ? -1f : tps;
  }

  /** {@code 19.9}, or a dash while the rate is not known. */
  public static String text() {
    final float now = tps();
    return now < 0f ? "-" : String.format(java.util.Locale.US, "%.1f", now);
  }
}
