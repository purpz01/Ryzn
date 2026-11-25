package ryzn.macro.client.debug;

import lombok.RequiredArgsConstructor;

/** Emits state and status transitions for workflow macros while macro debugging is enabled. */
@RequiredArgsConstructor
public final class MacroStateTrace {
  private final String name;
  private String lastState;
  private String lastStatus = "";

  public void log(String state, String status) {
    if (!DebugLog.isEnabled(DebugLog.Category.MACRO)) {
      return;
    }
    if (!state.equals(lastState)) {
      DebugLog.trace(
          DebugLog.Category.MACRO,
          "%s: %s -> %s",
          name,
          lastState == null ? "start" : lastState,
          state);
      lastState = state;
    }
    logStatus(status);
  }

  public void logStatus(String status) {
    if (!DebugLog.isEnabled(DebugLog.Category.MACRO)) {
      return;
    }
    if (status != null && !status.equals(lastStatus)) {
      DebugLog.trace(DebugLog.Category.MACRO, "%s: %s", name, status);
      lastStatus = status;
    }
  }
}
