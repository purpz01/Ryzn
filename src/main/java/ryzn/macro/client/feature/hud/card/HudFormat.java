package ryzn.macro.client.feature.hud.card;

import java.util.Locale;

/**
 * Number and duration formatting for the HUD. Every reference client compacts its numbers ({@code
 * 1.47m}, {@code 186.0k}) because a raw digit string blows the value column; this is the one place
 * that happens.
 */
public final class HudFormat {

  private HudFormat() {}

  /** Hypixel-style compact number: {@code 942}, {@code 186.0k}, {@code 1.47m}, {@code 2.59b}. */
  public static String compact(double value) {
    final double abs = Math.abs(value);
    if (abs < 1_000d) {
      return trim(value, 0);
    }
    if (abs < 1_000_000d) {
      return trim(value / 1_000d, abs < 10_000d ? 2 : 1) + "k";
    }
    if (abs < 1_000_000_000d) {
      return trim(value / 1_000_000d, abs < 10_000_000d ? 2 : 1) + "m";
    }
    return trim(value / 1_000_000_000d, 2) + "b";
  }

  /** Exact grouped digits, for totals where precision is the point. */
  public static String exact(long value) {
    return String.format(Locale.US, "%,d", value);
  }

  /** {@code 00:07:35} - the session-timer form. */
  public static String duration(long millis) {
    if (millis < 0L) {
      return "--:--:--";
    }
    final long total = millis / 1000L;
    return String.format(
        Locale.ROOT, "%02d:%02d:%02d", total / 3600L, (total / 60L) % 60L, total % 60L);
  }

  /** {@code 4m 12s} / {@code 9h 7m} - the compact human form for a subtitle. */
  public static String coarse(long millis) {
    if (millis < 0L) {
      return "-";
    }
    final long total = millis / 1000L;
    final long h = total / 3600L;
    final long m = (total / 60L) % 60L;
    if (h > 0L) {
      return h + "h " + m + "m";
    }
    if (m > 0L) {
      return m + "m " + (total % 60L) + "s";
    }
    return total + "s";
  }

  /** {@code 7.2%} */
  public static String percent(float fraction) {
    return String.format(Locale.ROOT, "%.1f%%", clamp01(fraction) * 100f);
  }

  /** {@code 32 / 38} */
  public static String fraction(long done, long total) {
    return done + " / " + total;
  }

  public static float clamp01(float v) {
    if (Float.isNaN(v)) {
      return 0f;
    }
    return v < 0f ? 0f : Math.min(v, 1f);
  }

  private static String trim(double value, int decimals) {
    final String text = String.format(Locale.ROOT, "%." + decimals + "f", value);
    if (decimals == 0 || text.indexOf('.') < 0) {
      return text;
    }
    // 1.50m -> 1.5m, 2.00k -> 2k: trailing zeros only add width
    return text.replaceAll("0+$", "").replaceAll("\\.$", "");
  }
}
