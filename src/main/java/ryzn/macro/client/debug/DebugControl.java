package ryzn.macro.client.debug;

import ryzn.macro.client.feature.pathfinder.PathRenderer;

/**
 * Single source of truth for Ryzn's debug mode. Both the {@code /dev debug} command and the Misc ->
 * Debug GUI toggle route through here so they always agree: it flips the verbose chat logging (NAV
 * + MACRO categories), the native debug annotations, and the diagnostic path spline together. State
 * lives in {@link PathRenderer#isEnabled()} so a single flag backs everything. The route node
 * highlights are NOT part of this - they're always drawn while a route is active.
 */
public final class DebugControl {

  private DebugControl() {}

  public static boolean isEnabled() {
    return PathRenderer.isEnabled();
  }

  public static void setEnabled(boolean on) {
    DebugLog.setEnabled(DebugLog.Category.NAV, on); // nav trace + the gated [ryzn cache] chat
    DebugLog.setEnabled(DebugLog.Category.MACRO, on); // macro trace
    PathRenderer.setEnabled(
        on); // diagnostic spline only; the node highlights are always on while pathing
  }

  /** Flip debug mode and return the new state. */
  public static boolean toggle() {
    final boolean on = !isEnabled();
    setEnabled(on);
    return on;
  }
}
