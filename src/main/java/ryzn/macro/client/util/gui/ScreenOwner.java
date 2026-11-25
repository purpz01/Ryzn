package ryzn.macro.client.util.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import ryzn.macro.client.util.inventory.InventoryHotbarTransfer;

/**
 * Who opened the screen that is on the player's display: a macro, or the player.
 *
 * <p>The failsafe needs that answer and cannot get it from the screen itself. Matching titles
 * against a table of the menus each macro uses was the obvious approach and is the wrong one - the
 * table has to be kept in step with a dozen macros and with whatever Hypixel renames next, and
 * every gap in it stops a grind for no reason. Ownership is not a property of the title, so this
 * asks the question that actually decides it: did a macro just do something that opens a menu?
 *
 * <p>A macro's menu always follows a macro action - a right-click on an NPC, a click on a slot -
 * within a moment. Nothing Ryzn does opens a menu without one. So a screen that appears inside
 * {@link #CLAIM_WINDOW_MS} of an action is the macro's, and a screen that appears out of nowhere is
 * the player's.
 *
 * <p>The player's own inventory is the exception, and a necessary one: a macro that holds
 * right-click for a living would claim everything under the general rule. The server cannot open
 * that screen at all - it is client-side, from the player's own key - so it is only ever claimed by
 * a macro that opened it outright through {@link #macroOpenedInventory()}.
 */
public final class ScreenOwner {

  /**
   * A menu follows the action that opened it within a round trip or so; beyond that it is not ours.
   */
  private static final long CLAIM_WINDOW_MS = 3000L;

  private static volatile long lastActionMs = Long.MIN_VALUE;
  private static volatile long inventoryOpenedMs = Long.MIN_VALUE;

  private ScreenOwner() {}

  /** Record a macro action that can open a menu: a use press, a slot click, an NPC interact. */
  public static void macroActed() {
    final long now = System.currentTimeMillis();
    lastActionMs = now;
    // A macro that drives the player's own inventory clicks in it for far longer than the claim
    // window,
    // and those clicks never touched the inventory claim - so the failsafe decided the PLAYER had
    // opened
    // it, mid-run. Renew a claim that is still live; never create one, or a screen the player
    // opened
    // during any macro action would be claimed by whatever that macro did next.
    if (now - inventoryOpenedMs <= CLAIM_WINDOW_MS) {
      inventoryOpenedMs = now;
    }
  }

  /**
   * Record a macro opening the player's own inventory itself, which no server menu can account for.
   */
  public static void macroOpenedInventory() {
    inventoryOpenedMs = System.currentTimeMillis();
    lastActionMs = inventoryOpenedMs;
  }

  public static boolean hasMacroInventoryClaim() {
    return System.currentTimeMillis() - inventoryOpenedMs <= CLAIM_WINDOW_MS;
  }

  /** True when {@code screen} is one a macro brought up, so the failsafe should leave it alone. */
  public static boolean isMacroOwned(Screen screen) {
    if (screen == null) {
      return true;
    }
    final long now = System.currentTimeMillis();
    if (screen instanceof InventoryScreen) {
      return hasMacroInventoryClaim();
    }
    return now - lastActionMs <= CLAIM_WINDOW_MS;
  }

  /** True when the player opened their own inventory and no macro asked for it. */
  public static boolean isPlayerInventory(Minecraft mc) {
    return mc != null
        && mc.gui.screen() instanceof InventoryScreen
        && !isMacroOwned(mc.gui.screen());
  }

  /**
   * Forget every claim. Called when the failsafe stops everything, so a stale claim cannot carry
   * over.
   */
  public static void reset() {
    InventoryHotbarTransfer.cancel();
    lastActionMs = Long.MIN_VALUE;
    inventoryOpenedMs = Long.MIN_VALUE;
  }
}
