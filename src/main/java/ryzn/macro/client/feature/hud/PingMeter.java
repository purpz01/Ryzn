package ryzn.macro.client.feature.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.util.debugchart.LocalSampleLogger;

/**
 * Vanilla's own round trip, read straight out of the F3 network chart's sample log.
 *
 * <p>{@code PingDebugMonitor} sends a {@code ServerboundPingRequestPacket} stamped with the current
 * millis and logs {@code now - packet.time()} when the pong comes back, so the sample is the full
 * game-layer round trip. {@link ryzn.macro.client.mixin.network.ClientPacketListenerMixin} keeps
 * that monitor ticking with F3 closed; this class only reads what it recorded. Same approach as
 * SkyHanni and Skyblocker.
 *
 * <p>Two things it deliberately is not. The tab-list latency ({@code PlayerInfo#getLatency()}) is
 * measured by whatever backend is holding us and Hypixel pins it near 1ms. A TCP handshake to the
 * connection's remote address (what this class used to do) completes at the edge/DDoS front, so it
 * reported ~9ms while the real round trip was hundreds - it misses the proxy hop entirely.
 */
public final class PingMeter {

  private PingMeter() {}

  /**
   * Mean of the logged samples in ms, or -1 before the first pong lands. Vanilla samples once per
   * tick, so the window is the last few seconds - averaged, because a single sample is jittery.
   */
  public static int millis(Minecraft mc) {
    if (mc == null || mc.getConnection() == null) {
      return -1;
    }
    final LocalSampleLogger log = mc.getDebugOverlay().getPingLogger();
    final int size = log.size();
    if (size <= 0) {
      return -1;
    }
    long total = 0L;
    for (int i = 0; i < size; i++) {
      total += log.get(i);
    }
    return (int) (total / size);
  }
}
