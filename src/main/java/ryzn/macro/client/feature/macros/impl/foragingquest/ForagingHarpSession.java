package ryzn.macro.client.feature.macros.impl.foragingquest;

import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.AfkAutoHarpActionEvent;
import ryzn.macro.client.feature.macros.impl.foraging.AfkAutoHarpMacro;

/** Owns the full-harp child macro started by the foraging quest workflow. */
final class ForagingHarpSession {
  enum Result {
    RUNNING,
    COMPLETE,
    STOPPED
  }

  private boolean startedByQuest;

  void start() {
    if (!harp().isEnabled()) {
      RyznBootstrap.eventBus().post(new AfkAutoHarpActionEvent(true));
    }
    startedByQuest = true;
  }

  Result result() {
    if (harp().wasLastRunCompleted() && !harp().isEnabled()) {
      return Result.COMPLETE;
    }
    if (startedByQuest && !harp().isEnabled()) {
      return Result.STOPPED;
    }
    return Result.RUNNING;
  }

  String status() {
    return harp().statusText();
  }

  /** Songs cleared so far this run. The quest step watches it to tell progress from a stall. */
  int songsCleared() {
    return harp().songsCompleted();
  }

  String stopReason() {
    return harp().lastStopReason();
  }

  void stopOwnedMacro() {
    if (startedByQuest && harp().isEnabled()) {
      RyznBootstrap.eventBus().post(new AfkAutoHarpActionEvent(false));
    }
  }

  void reset() {
    startedByQuest = false;
  }

  private static AfkAutoHarpMacro harp() {
    return RyznBootstrap.macroManager().getByClass(AfkAutoHarpMacro.class);
  }
}
