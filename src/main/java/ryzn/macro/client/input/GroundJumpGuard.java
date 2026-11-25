package ryzn.macro.client.input;

public final class GroundJumpGuard {
  private int ticksSincePress = 8;
  private boolean held;

  public boolean apply(boolean requested, boolean groundPath) {
    ticksSincePress = Math.min(8, ticksSincePress + 1);
    boolean jump = requested && (!groundPath || held || ticksSincePress >= 8);
    if (jump && !held) {
      ticksSincePress = 0;
    }
    held = jump;
    return jump;
  }
}
