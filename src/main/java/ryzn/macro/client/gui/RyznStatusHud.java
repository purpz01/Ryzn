package ryzn.macro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.feature.hud.TpsMeter;

public final class RyznStatusHud {
  private static long nextMetrics;
  private static String metrics = "";

  private RyznStatusHud() {}

  public static void render(GuiGraphicsExtractor graphics) {
    var client = Minecraft.getInstance();
    var settings = RyznSettings.get();
    if (!settings.statusHud().isEnabled()
        || client.player == null
        || client.gui.screen() instanceof RyznModulesScreen
        || client.gui.hud.isHidden()
        || client.getDebugOverlay().showDebugScreen()) {
      return;
    }

    long now = System.nanoTime();
    if (metrics.isEmpty() || now - nextMetrics >= 0) {
      nextMetrics = now + 500_000_000L;
      var connection = client.getConnection();
      var info = connection == null ? null : connection.getPlayerInfo(client.player.getUUID());
      String ping = info == null ? "-" : info.getLatency() + " ms";
      metrics = "FPS " + client.getFps() + "   PING " + ping + "   TPS " + TpsMeter.text();
    }
    boolean showCoordinates = settings.statusCoords().isEnabled();
    int width = 180;
    RyznMenuStyle.glass(
        graphics, 8, 8, width, showCoordinates ? 51 : 39, RyznTheme.TOP, RyznTheme.BOTTOM, 9);
    RyznMenuStyle.crown(graphics, 14, 12, 17, 14);
    RyznMenuStyle.text(
        graphics, client.font, "Ryzn", 36, 15, width - 34, RyznTheme.ACCENT, true, false);
    RyznMenuStyle.text(
        graphics, client.font, metrics, 16, 29, width - 16, RyznTheme.INK, false, false);
    if (showCoordinates) {
      var pos = client.player.blockPosition();
      String coordinates = "X " + pos.getX() + "   Y " + pos.getY() + "   Z " + pos.getZ();
      RyznMenuStyle.text(
          graphics, client.font, coordinates, 16, 42, width - 16, RyznTheme.MUTED, false, false);
    }
  }
}
