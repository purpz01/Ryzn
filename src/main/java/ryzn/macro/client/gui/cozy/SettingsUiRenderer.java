package ryzn.macro.client.gui.cozy;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import ryzn.macro.client.config.groups.MenuTheme;

/** Window chrome from Ryzn's settings renderer, with bounds supplied by Ryzn. */
public final class SettingsUiRenderer {
  private static final float WINDOW_RADIUS = 18f;
  private static final MenuTheme PALETTE = MenuTheme.MIDNIGHT;

  private SettingsUiRenderer() {}

  public static void drawWindow(
      Canvas canvas, int width, int height, int headerHeight, float scale) {
    final RRect window = RRect.makeXYWH(1, 1, width - 2, height - 2, WINDOW_RADIUS * scale);
    fill(canvas, window, PALETTE.window());
    canvas.save();
    canvas.clipRRect(window, true);
    if (headerHeight > 0) {
      fillRect(canvas, Rect.makeXYWH(1, 1, width - 2, headerHeight), PALETTE.header());
    }
    canvas.restore();
    stroke(canvas, window, Math.max(1f, scale), PALETTE.tint(0x33));
  }

  private static void fill(Canvas c, RRect rr, int color) {
    if ((color >>> 24) == 0) return;
    try (Paint p = new Paint().setColor(color).setAntiAlias(true)) {
      c.drawRRect(rr, p);
    }
  }

  private static void fillRect(Canvas c, Rect r, int color) {
    try (Paint p = new Paint().setColor(color).setAntiAlias(true)) {
      c.drawRect(r, p);
    }
  }

  private static void stroke(Canvas c, RRect rr, float width, int color) {
    try (Paint p =
        new Paint()
            .setColor(color)
            .setAntiAlias(true)
            .setMode(PaintMode.STROKE)
            .setStrokeWidth(width)) {
      c.drawRRect(rr, p);
    }
  }
}
