package ryzn.macro.client.feature.macros.impl.tree;

final class TreeChopContactProgress {

  private int arrivalTicks;
  private int recontactAttempts;
  private int contactTicks;

  boolean keepClosingOnStand(int maximumTicks) {
    return ++arrivalTicks <= maximumTicks;
  }

  void resetArrival() {
    arrivalTicks = 0;
  }

  void advanceContact() {
    contactTicks++;
  }

  boolean contactTimedOut(int timeoutTicks) {
    return contactTicks >= timeoutTicks;
  }

  void resetContact() {
    contactTicks = 0;
  }

  boolean tryRecontact(int maximumAttempts) {
    return ++recontactAttempts <= maximumAttempts;
  }

  void resetRecontacts() {
    recontactAttempts = 0;
  }

  void reset() {
    arrivalTicks = 0;
    recontactAttempts = 0;
    contactTicks = 0;
  }
}
