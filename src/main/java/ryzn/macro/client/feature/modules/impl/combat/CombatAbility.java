package ryzn.macro.client.feature.modules.impl.combat;

import net.minecraft.client.player.LocalPlayer;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.util.inventory.MacroItems;
import ryzn.macro.client.util.inventory.MacroSlots;

/**
 * The hotbar ability a managed session fires instead of swinging: swap to the item, use it, swap
 * back.
 *
 * <p>A swap cannot be instantaneous - the server has to see the held item change before the use
 * lands - so this is a three-stage machine rather than one call, with a tick budget on each stage:
 *
 * <ul>
 *   <li>{@code IDLE} - nothing held, the normal swing path applies.
 *   <li>{@code PREPARED} - the ability item is selected and settling. The bot keeps aiming during
 *       this window but does not swing, so the cast lands on the mob it was pointed at.
 *   <li>{@code CAST} - the ability has been used; the original weapon goes back when the budget
 *       expires.
 * </ul>
 *
 * <p>The item is found by NAME each time rather than by a remembered slot, because the hotbar can
 * be rearranged mid-session. If either name is missing from the hotbar the swap is abandoned and
 * the session falls back to a plain swing, which is why {@link #prepare} returns a boolean rather
 * than throwing.
 *
 * <p>Anti-detection: the swap is a vanilla slot select and the use is a vanilla click, both through
 * {@link MacroInput}; nothing is sent that a player pressing the key would not send.
 */
final class CombatAbility {

  /**
   * Ticks the ability item is held before the use is allowed - the server needs to see the swap
   * first.
   */
  private static final int SWAP_PREPARE_TICKS = 6;

  /**
   * Ticks after the cast before the original weapon comes back, so the use is not cancelled by the
   * swap.
   */
  static final int SWAP_RESTORE_TICKS = 12;

  enum Stage {
    IDLE,
    PREPARED,
    CAST
  }

  private Stage stage = Stage.IDLE;
  private int swapTicks;
  private int itemSlot = -1;
  private int restoreSlot = -1;

  boolean isSwapping() {
    return stage != Stage.IDLE;
  }

  boolean isPrepared() {
    return stage == Stage.PREPARED;
  }

  int swapTicks() {
    return swapTicks;
  }

  /**
   * Count down the current stage. Returns true when a CAST has expired and the weapon should go
   * back.
   */
  boolean tick() {
    if (stage != Stage.IDLE && swapTicks > 0) {
      swapTicks--;
    }
    return stage == Stage.CAST && swapTicks <= 0;
  }

  /**
   * A session only swaps when it named BOTH items; naming one is a config mistake, not a half-swap.
   */
  boolean usesSwap(CombatModule.ManagedSettings session) {
    return !session.abilityItemName().isBlank() && !session.restoreItemName().isBlank();
  }

  /**
   * Select the ability item, or confirm it is still selected. False means the swap cannot be done
   * (an item is missing from the hotbar, or the player moved the selection) and the caller should
   * not cast.
   */
  boolean prepare(LocalPlayer player, CombatModule.ManagedSettings session) {
    if (stage == Stage.IDLE) {
      itemSlot =
          MacroSlots.findHotbarSlot(
              player, stack -> MacroItems.stackMatchesName(stack, session.abilityItemName()));
      restoreSlot =
          MacroSlots.findHotbarSlot(
              player, stack -> MacroItems.stackMatchesName(stack, session.restoreItemName()));
      if (itemSlot < 0 || restoreSlot < 0) {
        itemSlot = -1;
        restoreSlot = -1;
        return false;
      }
      MacroInput.selectHotbarSlot(player, itemSlot);
      stage = Stage.PREPARED;
      swapTicks = SWAP_PREPARE_TICKS;
      return true;
    }
    if (stage != Stage.PREPARED) {
      return false;
    }
    // Something else moved the hand (the player, or another macro): start the swap over rather than
    // casting with whatever is now held.
    if (player.getInventory().getSelectedSlot() != itemSlot
        || !MacroItems.stackMatchesName(player.getMainHandItem(), session.abilityItemName())) {
      stage = Stage.IDLE;
      swapTicks = 0;
      return prepare(player, session);
    }
    return true;
  }

  /** The ability just went off: start the timer that puts the weapon back. */
  void noteCast() {
    stage = Stage.CAST;
    swapTicks = SWAP_RESTORE_TICKS;
  }

  /**
   * Give the weapon back and forget the swap. Must be called while the hand is still known - before
   * any wider teardown drops the slots - or the player is left holding the ability item.
   */
  void restore(LocalPlayer player) {
    if (stage != Stage.IDLE && player != null && restoreSlot >= 0) {
      MacroInput.selectHotbarSlot(player, restoreSlot);
    }
    stage = Stage.IDLE;
    swapTicks = 0;
    itemSlot = -1;
    restoreSlot = -1;
  }

  /**
   * Both the swap settle and the between-casts cooldown have to be clear before the ability may
   * fire.
   */
  static boolean ready(int swapTicks, int cooldownTicks) {
    return swapTicks <= 0 && cooldownTicks <= 0;
  }

  /**
   * Start the swap early, so the item is settled by the time the cooldown ends (no swap-then-wait
   * stall).
   */
  static boolean shouldPrepare(int cooldownTicks, boolean swapsAbilityItem) {
    return cooldownTicks <= (swapsAbilityItem ? SWAP_PREPARE_TICKS : 0);
  }

  static boolean shouldCast(boolean clustered, boolean prepared) {
    return clustered || prepared;
  }

  /**
   * Keep aiming through the prepare window, so the cast lands on the mob the head was already on.
   */
  static boolean shouldAim(int cooldownTicks, boolean prepared, boolean swapsAbilityItem) {
    return prepared || shouldPrepare(cooldownTicks, swapsAbilityItem);
  }
}
