package ryzn.macro.client.feature.failsafe;

import java.util.Locale;

public enum FailsafeAction {
  STOP,
  IGNORE,
  /** Leave the lobby and come back to the grind spot instead of ending the session. */
  HOP;

  public static FailsafeAction fromConfig(String value) {
    if (value == null || value.isBlank()) {
      return STOP;
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return STOP;
    }
  }

  /**
   * {@code Dropdown} is 1-based (see {@code TreeLogType.uiIndex}): row 0 reports 1. This was
   * 0-based, so every action read back one row early and "Lobby Hop" - the last row, reporting 3 -
   * fell off the end into the default and silently selected STOP, which looked like the option
   * refusing to be picked. Enum order must stay in step with the label array in {@code
   * FeatureWidgetBuilder.detectorWidgets}.
   */
  private static final FailsafeAction[] values = values();

  public static FailsafeAction fromUiIndex(int index) {
    return values[Math.clamp(index, 1, values.length) - 1];
  }

  public int uiIndex() {
    return ordinal() + 1;
  }
}
