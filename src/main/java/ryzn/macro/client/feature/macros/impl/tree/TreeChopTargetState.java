package ryzn.macro.client.feature.macros.impl.tree;

import lombok.AccessLevel;
import lombok.Setter;
import net.minecraft.core.BlockPos;

final class TreeChopTargetState {

  private BlockPos committedLog;

  @Setter(AccessLevel.PACKAGE)
  private BlockPos glideLog;

  private BlockPos aimCandidateLog;
  private int commitTicks;
  private int candidateAimTicks;
  private int crosshairBlockedTicks;
  private float lastMiningProgress;
  private BlockPos obstructedLog;
  private int obstructionTicks;

  BlockPos committedLog() {
    return committedLog;
  }

  BlockPos glideLog() {
    return glideLog;
  }

  void commit(BlockPos log) {
    committedLog = log;
    glideLog = null;
    aimCandidateLog = null;
    commitTicks = 0;
    lastMiningProgress = 0f;
    obstructedLog = null;
    obstructionTicks = 0;
    candidateAimTicks = 0;
    crosshairBlockedTicks = 0;
  }

  void clearCommit() {
    committedLog = null;
    glideLog = null;
    aimCandidateLog = null;
    commitTicks = 0;
    lastMiningProgress = 0f;
    obstructedLog = null;
    obstructionTicks = 0;
    candidateAimTicks = 0;
    crosshairBlockedTicks = 0;
  }

  boolean commitTimedOut(int maxTicks) {
    return ++commitTicks > maxTicks;
  }

  /** Ticks spent on the current log - drives how far the aim leads toward the next one. */
  int commitAge() {
    return commitTicks;
  }

  void recordCrosshairHit() {
    crosshairBlockedTicks = 0;
  }

  boolean recordMiningProgress(BlockPos log, float progress) {
    if (committedLog == null || !committedLog.equals(log)) {
      return false;
    }
    boolean advanced = progress > lastMiningProgress;
    lastMiningProgress = progress;
    return advanced;
  }

  boolean obstructionTooLong(BlockPos log, int maxTicks) {
    if (log == null || !log.equals(obstructedLog)) {
      obstructedLog = log;
      obstructionTicks = 0;
    }
    return log != null && ++obstructionTicks >= maxTicks;
  }

  boolean crosshairBlockedTooLong(int maxTicks) {
    return ++crosshairBlockedTicks > maxTicks;
  }

  boolean noteAimCandidate(BlockPos candidate, int maxTicks) {
    if (candidate == null) {
      return false;
    }
    if (!candidate.equals(aimCandidateLog)) {
      aimCandidateLog = candidate;
      candidateAimTicks = 0;
      return false;
    }
    if (++candidateAimTicks <= maxTicks) {
      return false;
    }
    clearAimCandidate();
    return true;
  }

  void clearAimCandidate() {
    aimCandidateLog = null;
    candidateAimTicks = 0;
  }
}
