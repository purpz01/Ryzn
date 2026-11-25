package ryzn.macro.client.gui;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.types.RRect;

/**
 * Pure-Skija vector glyph set for the Apple-styled GUI. Every icon is monochrome line/fill art
 * drawn with rounded caps + joins (SF-Symbol feel) so it tints cleanly inside a coloured app-icon
 * tile or sidebar row.
 *
 * <p>All drawers share the {@link Glyph} shape {@code (canvas, cx, cy, size, color, scale)} where
 * {@code cx,cy} is the glyph centre and {@code size} is the full box edge in framebuffer pixels
 * (already multiplied by the GUI scale). Paths are assembled with {@link PathBuilder} (this Skija
 * build keeps the mutable contour API there, not on {@link Path}) and closed each frame - no
 * retained state.
 */
public final class Icons {

  /** A glyph that can render itself centred in a box. Lets {@code TabButton} stay generic. */
  @FunctionalInterface
  public interface Glyph {
    void draw(Canvas c, float cx, float cy, float size, int color, float scale);
  }

  @FunctionalInterface
  private interface PathOps {
    void build(PathBuilder pb);
  }

  private Icons() {}

  private static Paint stroke(int color, float w) {
    return new Paint()
        .setColor(color)
        .setAntiAlias(true)
        .setMode(PaintMode.STROKE)
        .setStrokeWidth(w)
        .setStrokeCap(PaintStrokeCap.ROUND)
        .setStrokeJoin(PaintStrokeJoin.ROUND);
  }

  private static Paint fill(int color) {
    return new Paint().setColor(color).setAntiAlias(true);
  }

  private static float sw(float size) {
    return Math.max(1.3f, size * 0.085f);
  }

  /**
   * Build a one-shot path from {@code ops} and draw it with {@code paint}, freeing both native
   * handles.
   */
  private static void path(Canvas c, Paint paint, PathOps ops) {
    try (PathBuilder pb = new PathBuilder()) {
      ops.build(pb);
      try (Path p = pb.build()) {
        c.drawPath(p, paint);
      }
    }
  }

  // ===== Category glyphs

  public static void gear(Canvas c, float cx, float cy, float size, int color, float scale) {
    final float r = size * 0.30f;
    final float tooth = size * 0.10f;
    try (Paint p = stroke(color, sw(size))) {
      c.drawCircle(cx, cy, r * 0.55f, p);
      for (int i = 0; i < 8; i++) {
        final double a = Math.PI * 2.0 * i / 8.0;
        final float ix = cx + (float) Math.cos(a) * r;
        final float iy = cy + (float) Math.sin(a) * r;
        final float ox = cx + (float) Math.cos(a) * (r + tooth);
        final float oy = cy + (float) Math.sin(a) * (r + tooth);
        c.drawLine(ix, iy, ox, oy, p);
      }
    }
  }

  public static void eye(Canvas c, float cx, float cy, float size, int color, float scale) {
    final float w = size * 0.40f;
    final float h = size * 0.34f;
    try (Paint p = stroke(color, sw(size))) {
      path(
          c,
          p,
          pb ->
              pb.moveTo(cx - w, cy)
                  .quadTo(cx, cy - h, cx + w, cy)
                  .quadTo(cx, cy + h, cx - w, cy)
                  .closePath());
    }
    try (Paint p = fill(color)) {
      c.drawCircle(cx, cy, size * 0.13f, p);
    }
  }

  public static void command(Canvas c, float cx, float cy, float size, int color, float scale) {
    final float h = size * 0.16f;
    final float rr = size * 0.13f;
    try (Paint p = stroke(color, sw(size))) {
      c.drawRRect(RRect.makeXYWH(cx - h, cy - h, h * 2f, h * 2f, size * 0.04f), p);
      c.drawCircle(cx - h, cy - h, rr, p);
      c.drawCircle(cx + h, cy - h, rr, p);
      c.drawCircle(cx - h, cy + h, rr, p);
      c.drawCircle(cx + h, cy + h, rr, p);
    }
  }

  public static void route(Canvas c, float cx, float cy, float size, int color, float scale) {
    final float r = size * 0.11f;
    final float ax = cx - size * 0.26f;
    final float ay = cy - size * 0.26f; // top-left node
    final float bx = cx - size * 0.26f;
    final float by = cy + size * 0.26f; // bottom-left node
    final float dx = cx + size * 0.28f;
    final float dy = cy; // right node
    try (Paint p = stroke(color, sw(size))) {
      c.drawLine(ax, ay, dx, dy, p);
      c.drawLine(bx, by, dx, dy, p);
    }
    try (Paint fp = fill(color)) {
      c.drawCircle(ax, ay, r, fp);
      c.drawCircle(bx, by, r, fp);
      c.drawCircle(dx, dy, r, fp);
    }
  }

  public static void sliders(Canvas c, float cx, float cy, float size, int color, float scale) {
    final float w = size * 0.40f;
    final float[] rows = {-size * 0.24f, 0f, size * 0.24f};
    final float[] knob = {0.62f, 0.38f, 0.70f};
    try (Paint line = stroke(color, sw(size));
        Paint dot = fill(color)) {
      for (int i = 0; i < 3; i++) {
        final float ly = cy + rows[i];
        c.drawLine(cx - w, ly, cx + w, ly, line);
        final float kx = cx - w + (2f * w) * knob[i];
        c.drawCircle(kx, ly, size * 0.10f, dot);
      }
    }
  }

  // ===== Module app-icon glyphs

  public static void hand(Canvas c, float cx, float cy, float size, int color, float scale) {
    final float w = sw(size);
    try (Paint p = stroke(color, w)) {
      c.drawLine(cx - size * 0.18f, cy + size * 0.38f, cx - size * 0.04f, cy + size * 0.12f, p);
      c.drawLine(cx - size * 0.04f, cy + size * 0.12f, cx + size * 0.19f, cy + size * 0.25f, p);
      c.drawLine(cx + size * 0.19f, cy + size * 0.25f, cx + size * 0.27f, cy + size * 0.07f, p);
      c.drawLine(cx + size * 0.27f, cy + size * 0.07f, cx + size * 0.11f, cy - size * 0.02f, p);
      c.drawLine(cx + size * 0.11f, cy - size * 0.02f, cx + size * 0.16f, cy - size * 0.30f, p);
      c.drawLine(cx + size * 0.02f, cy - size * 0.04f, cx + size * 0.04f, cy - size * 0.36f, p);
      c.drawLine(cx - size * 0.07f, cy - size * 0.02f, cx - size * 0.08f, cy - size * 0.32f, p);
      c.drawLine(cx - size * 0.16f, cy + size * 0.02f, cx - size * 0.20f, cy - size * 0.22f, p);
      c.drawLine(cx - size * 0.20f, cy + size * 0.12f, cx - size * 0.34f, cy + size * 0.02f, p);
    }
    try (Paint p = fill(color)) {
      c.drawCircle(cx + size * 0.29f, cy + size * 0.03f, size * 0.05f, p);
    }
  }

  public static void chat(Canvas c, float cx, float cy, float size, int color, float scale) {
    final float w = size * 0.40f;
    final float h = size * 0.30f;
    try (Paint p = stroke(color, sw(size))) {
      c.drawRRect(RRect.makeXYWH(cx - w, cy - h * 1.1f, w * 2f, h * 2f, size * 0.14f), p);
      path(
          c,
          p,
          pb ->
              pb.moveTo(cx - w * 0.45f, cy + h * 0.7f)
                  .lineTo(cx - w * 0.75f, cy + h * 1.25f)
                  .lineTo(cx - w * 0.05f, cy + h * 0.7f));
    }
    try (Paint p = fill(color)) {
      for (int i = -1; i <= 1; i++) {
        c.drawCircle(cx + i * size * 0.16f, cy - h * 0.1f, size * 0.045f, p);
      }
    }
  }

  public static void grid(Canvas c, float cx, float cy, float size, int color, float scale) {
    final float d = size * 0.155f;
    try (Paint p = fill(color)) {
      for (int gx = -1; gx <= 1; gx++) {
        for (int gy = -1; gy <= 1; gy++) {
          c.drawRRect(
              RRect.makeXYWH(
                  cx + gx * d * 2f - d * 0.6f,
                  cy + gy * d * 2f - d * 0.6f,
                  d * 1.2f,
                  d * 1.2f,
                  d * 0.45f),
              p);
        }
      }
    }
  }

  public static void wheat(Canvas c, float cx, float cy, float size, int color, float scale) {
    try (Paint p = stroke(color, sw(size))) {
      c.drawLine(cx, cy + size * 0.40f, cx, cy - size * 0.34f, p);
      for (int i = 0; i < 3; i++) {
        final float gy = cy - size * 0.30f + i * size * 0.22f;
        c.drawLine(cx, gy, cx - size * 0.24f, gy + size * 0.12f, p);
        c.drawLine(cx, gy, cx + size * 0.24f, gy + size * 0.12f, p);
      }
    }
  }

  public static void note(Canvas c, float cx, float cy, float size, int color, float scale) {
    final float headR = size * 0.12f;
    final float stemX = cx + size * 0.20f;
    try (Paint p = stroke(color, sw(size))) {
      c.drawLine(stemX, cy + size * 0.22f, stemX, cy - size * 0.34f, p);
      c.drawLine(cx - size * 0.18f, cy + size * 0.10f, cx - size * 0.18f, cy - size * 0.34f, p);
      c.drawLine(cx - size * 0.18f, cy - size * 0.34f, stemX, cy - size * 0.24f, p);
    }
    try (Paint p = fill(color)) {
      c.drawCircle(cx - size * 0.18f - headR * 0.4f, cy + size * 0.10f, headR, p);
      c.drawCircle(stemX - headR * 0.4f, cy + size * 0.22f, headR, p);
    }
  }

  /** A filled play triangle with rounded corners, optically centred. */
  public static void play(Canvas c, float cx, float cy, float size, int color, float scale) {
    try (Paint p =
        fill(color)
            .setMode(PaintMode.STROKE_AND_FILL)
            .setStrokeWidth(size * 0.12f)
            .setStrokeJoin(PaintStrokeJoin.ROUND)) {
      final float h = size * 0.5f;
      final float x0 = cx - size * 0.18f;
      path(
          c,
          p,
          pb ->
              pb.moveTo(x0, cy - h / 2f)
                  .lineTo(x0 + size * 0.42f, cy)
                  .lineTo(x0, cy + h / 2f)
                  .closePath());
    }
  }

  public static void pin(Canvas c, float cx, float cy, float size, int color, float scale) {
    final float r = size * 0.24f;
    final float top = cy - size * 0.18f;
    try (Paint p = stroke(color, sw(size))) {
      path(
          c,
          p,
          pb ->
              pb.moveTo(cx, cy + size * 0.38f)
                  .quadTo(cx - r * 1.3f, top, cx, top - r)
                  .quadTo(cx + r * 1.3f, top, cx, cy + size * 0.38f)
                  .closePath());
    }
    try (Paint p = fill(color)) {
      c.drawCircle(cx, top - r * 0.05f, size * 0.085f, p);
    }
  }

  public static void inventory(Canvas c, float cx, float cy, float size, int color, float scale) {
    final float d = size * 0.26f;
    try (Paint p = stroke(color, sw(size))) {
      for (int gx = -1; gx <= 0; gx++) {
        for (int gy = -1; gy <= 0; gy++) {
          c.drawRRect(
              RRect.makeXYWH(
                  cx + gx * d - size * 0.02f,
                  cy + gy * d - size * 0.02f,
                  d * 0.85f,
                  d * 0.85f,
                  size * 0.05f),
              p);
        }
      }
    }
  }

  public static void tree(Canvas c, float cx, float cy, float size, int color, float scale) {
    try (Paint p = stroke(color, sw(size))) {
      c.drawLine(cx, cy + size * 0.40f, cx, cy - size * 0.04f, p);
    }
    try (Paint p = fill(color)) {
      path(
          c,
          p,
          pb ->
              pb.moveTo(cx, cy - size * 0.40f)
                  .lineTo(cx - size * 0.28f, cy + size * 0.06f)
                  .lineTo(cx + size * 0.28f, cy + size * 0.06f)
                  .closePath());
    }
  }

  // ===== Window chrome

  public static void chevronDown(Canvas c, float cx, float cy, float size, int color, float scale) {
    try (Paint p = stroke(color, sw(size) * 1.05f)) {
      c.drawLine(cx - size * 0.22f, cy - size * 0.1f, cx, cy + size * 0.12f, p);
      c.drawLine(cx, cy + size * 0.12f, cx + size * 0.22f, cy - size * 0.1f, p);
    }
  }

  public static void plus(Canvas c, float cx, float cy, float size, int color, float scale) {
    try (Paint p = stroke(color, sw(size) * 1.1f)) {
      c.drawLine(cx - size * 0.26f, cy, cx + size * 0.26f, cy, p);
      c.drawLine(cx, cy - size * 0.26f, cx, cy + size * 0.26f, p);
    }
  }

  public static void magnifier(Canvas c, float cx, float cy, float size, int color, float scale) {
    final float r = size * 0.24f;
    final float ox = cx - size * 0.05f;
    final float oy = cy - size * 0.05f;
    try (Paint p = stroke(color, sw(size) * 1.05f)) {
      c.drawCircle(ox, oy, r, p);
      c.drawLine(ox + r * 0.72f, oy + r * 0.72f, ox + r * 1.5f, oy + r * 1.5f, p);
    }
  }
}
