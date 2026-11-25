package ryzn.macro.client.feature.pathfinder.path;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.util.text.MacroText;

/** Chooses optional sprint hops from the server's TAB Speed stat, not movement constants. */
final class AutomaticHop {
  private static final double FLAT_SPRINT_SPEED = 136.0;
  private static final Pattern SPEED_LINE =
      Pattern.compile("(?im)^\\s*speed\\s*:[^\\d\\r\\n]*([0-9][0-9,]*(?:\\.[0-9]+)?)");
  private static ClientPacketListener lastConnection;
  private static Object lastLevel;
  private static long readAtMs;
  private static double speed = Double.NaN;

  private AutomaticHop() {}

  static boolean enabled(Minecraft mc) {
    if (RyznSettings.get().pathfinder().isConstantJumping()) {
      return true;
    }
    if (!RyznSettings.get().pathfinder().isSprint()) {
      return false;
    }
    double current = tabSpeed(mc);
    return current > 0 && current < FLAT_SPRINT_SPEED;
  }

  private static double tabSpeed(Minecraft mc) {
    if (mc == null || mc.level == null || mc.getConnection() == null) {
      lastConnection = null;
      lastLevel = null;
      speed = Double.NaN;
      return speed;
    }
    ClientPacketListener connection = mc.getConnection();
    long now = System.currentTimeMillis();
    if (connection == lastConnection && mc.level == lastLevel && now - readAtMs < 250) {
      return speed;
    }
    lastConnection = connection;
    lastLevel = mc.level;
    readAtMs = now;
    speed = Double.NaN;
    for (var info : connection.getOnlinePlayers()) {
      var display = info.getTabListDisplayName();
      if (display == null) {
        continue;
      }
      Matcher match = SPEED_LINE.matcher(MacroText.cleanDecoratedText(display.getString()));
      if (match.find()) {
        try {
          speed = Double.parseDouble(match.group(1).replace(",", ""));
          return speed;
        } catch (NumberFormatException ignored) {
          // Ignore a malformed stat row and keep looking for a usable one.
        }
      }
    }
    return speed;
  }
}
