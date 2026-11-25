package ryzn.macro.client.input;

/**
 * Holds a sent menu click open until the server confirms it. Hypixel silently drops clicks aimed at
 * a menu page it is still swapping, so a macro that treats "click sent" as "click worked" strands
 * itself waiting on something that never happened. The caller passes an observed value that the
 * click is expected to change (an item count, a slot's contents, a boolean as 0/1); the click
 * counts as landed once that value differs from what it was when the click went out.
 *
 * <p>Only needed where re-clicking costs something (spending coins, moving a stack twice). A click
 * that is harmless to repeat needs no confirmation at all - just re-derive from the menu each tick
 * and let the action delay pace the retries.
 */
public final class ClickConfirm {

  private static final int NONE = Integer.MIN_VALUE;

  private int mark = NONE;
  private int ticksLeft;

  /** Call right after sending the click, with the observed value as it was BEFORE the click. */
  public void sent(int observedBefore, int ceilingTicks) {
    mark = observedBefore == NONE ? NONE + 1 : observedBefore;
    ticksLeft = ceilingTicks;
  }

  /**
   * True while a sent click is still outstanding, so the caller holds off on sending another. Turns
   * false as soon as the observed value moves - or at the ceiling, so a click the server refused
   * outright still gets retried rather than hanging until the state times out.
   */
  public boolean awaiting(int observed) {
    if (mark == NONE) {
      return false;
    }
    if (observed != mark || ticksLeft <= 0) {
      mark = NONE;
      return false;
    }
    ticksLeft--;
    return true;
  }

  public boolean isPending() {
    return mark != NONE;
  }

  public void clear() {
    mark = NONE;
    ticksLeft = 0;
  }
}
