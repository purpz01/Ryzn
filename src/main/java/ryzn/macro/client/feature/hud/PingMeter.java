package ryzn.macro.client.feature.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.util.debugchart.LocalSampleLogger;

/** Reports the game-layer round trip from vanilla's ping samples. */
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
