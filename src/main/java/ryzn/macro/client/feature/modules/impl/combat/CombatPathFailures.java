package ryzn.macro.client.feature.modules.impl.combat;

final class CombatPathFailures {
  private int lastSequence;
  private int count;

  boolean consumeIfChanged(int sequence) {
    if (sequence == lastSequence) {
      return false;
    }
    lastSequence = sequence;
    return true;
  }

  boolean record(int sequence, boolean ownedTargetRoute, int maximum) {
    if (!consumeIfChanged(sequence) || !ownedTargetRoute) {
      return false;
    }
    return ++count >= maximum;
  }

  int count() {
    return count;
  }

  void resetCount() {
    count = 0;
  }
}
