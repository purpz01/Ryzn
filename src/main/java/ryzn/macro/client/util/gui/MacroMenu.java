package ryzn.macro.client.util.gui;

import java.util.function.ToIntFunction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import ryzn.macro.client.util.inventory.MacroSlots;

/**
 * Narrow scanning primitives over a container menu's non-player slots.
 *
 * <p>The scoring / skip decision stays with the caller (a per-slot scorer); this class only owns
 * the scan-and-keep-best loop that the shop / menu macros each re-implement. Behaviour matches the
 * hand-rolled loops it replaces: non-player slots only (via {@link
 * MacroSlots#isPlayerInventorySlot}), a scorer returning {@link #SKIP} excludes the slot, and ties
 * keep the first slot encountered (replacement is strictly-greater).
 */
public final class MacroMenu {

  private MacroMenu() {}

  /** Sentinel score a scorer returns to exclude a slot from consideration. */
  public static final int SKIP = Integer.MIN_VALUE;

  /**
   * The non-player slot whose {@code scorer} value is highest, or {@code null} when no non-player
   * slot scores above {@link #SKIP}. Ties keep the first such slot reached in menu-slot order.
   */
  public static Slot bestNonPlayerSlot(
      AbstractContainerMenu menu, LocalPlayer player, ToIntFunction<Slot> scorer) {
    Slot best = null;
    int bestScore = SKIP;
    for (Slot slot : menu.slots) {
      if (slot == null || MacroSlots.isPlayerInventorySlot(menu, player, slot)) {
        continue;
      }
      final int score = scorer.applyAsInt(slot);
      if (score > bestScore) {
        best = slot;
        bestScore = score;
      }
    }
    return best;
  }
}
