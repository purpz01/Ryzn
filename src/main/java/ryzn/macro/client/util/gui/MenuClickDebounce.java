package ryzn.macro.client.util.gui;

import ryzn.macro.client.util.inventory.MacroSlots;

/**
 * Drops a menu click that would undo the click before it.
 *
 * <p>Every item-moving click is its own inverse: click a slot holding a stack and it comes onto the
 * cursor, click the same slot again and it goes straight back down. So a macro that re-clicks while
 * its last click is still in flight picks up, drops, picks up, drops - the stutter. Individual
 * macros guard this with their own confirms, and every macro that forgot one rediscovered the same
 * bug, so the guard lives here instead: {@link MacroSlots#clickSlot} routes every macro click
 * through one of these.
 *
 * <p>The test is not "same slot twice" - a sell loop legitimately clicks one slot for stack after
 * stack. It is "same slot twice with <b>nothing changed</b>": if neither the target slot nor the
 * cursor has moved since the last click, that click has not landed yet and repeating it can only
 * undo it. Once the server echoes anything, the state differs and clicks flow again.
 *
 * <p>{@value #HOLD_TICKS} ticks caps the hold, because Hypixel does silently drop clicks aimed at a
 * menu page it is still swapping. A click that never lands must eventually be retried rather than
 * wedge the slot shut forever.
 */
public final class MenuClickDebounce {

  /** How long an unconfirmed click holds its slot shut before a retry is allowed. */
  private static final int HOLD_TICKS = 20;

  private int menuId = Integer.MIN_VALUE;
  private int slot = Integer.MIN_VALUE;
  private String state = "";
  private int sentTick = Integer.MIN_VALUE;

  /**
   * @param state what this click is expected to change - the target slot's contents plus the cursor
   * @param tick a monotonic client tick; going backwards (a fresh world) counts as a new click
   * @return true when the click should be sent
   */
  public boolean allow(int menuId, int slot, String state, int tick) {
    final int age = tick - sentTick;
    final boolean unconfirmed =
        menuId == this.menuId
            && slot == this.slot
            && state.equals(this.state)
            && age >= 0
            && age < HOLD_TICKS;
    if (unconfirmed) {
      return false;
    }

    this.menuId = menuId;
    this.slot = slot;
    this.state = state;
    this.sentTick = tick;
    return true;
  }
}
