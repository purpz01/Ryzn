package ryzn.macro.client.feature.macros.impl.foragingquest;

final class ForagingChopSession {

  private int logCountBaseline;
  private int choppedTreeBaseline;
  private boolean startedByQuest;

  void begin(int logCountBaseline, int choppedTreeBaseline) {
    this.logCountBaseline = logCountBaseline;
    this.choppedTreeBaseline = choppedTreeBaseline;
    startedByQuest = true;
  }

  boolean wasStartedByQuest() {
    return startedByQuest;
  }

  void markStopped() {
    startedByQuest = false;
  }

  void reset() {
    logCountBaseline = 0;
    choppedTreeBaseline = 0;
    startedByQuest = false;
  }
}
