package ryzn.macro.client.util.inventory;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.EndClientTickEvent;
import ryzn.macro.client.util.gui.MacroScreens;
import ryzn.macro.client.util.gui.ScreenOwner;

/** One visible, paced pickup/place/return transaction shared by every hotbar refill. */
public final class InventoryHotbarTransfer {
  private static final int CLICK_DELAY = 5;
  private static LocalPlayer owner;
  private static InventoryScreen screen;
  private static Slot source;
  private static Slot destination;
  private static ItemStack original;
  private static int step;
  private static int delay;
  private static int elapsed;
  private static int settled;
  private static boolean closeWhenDone;

  private InventoryHotbarTransfer() {}

  public static boolean isBusy() {
    return owner != null;
  }

  /**
   * Freeze macro phases and their timers until the cursor is empty and the transfer has settled.
   */
  public static void registerMacroTick(Consumer<Minecraft> tick) {
    RyznBootstrap.eventBus()
        .subscribe(
            EndClientTickEvent.class,
            event -> {
              if (!isBusy()) {
                tick.accept(event.client());
              }
              if (isBusy()) {
                MacroInput.releaseAllKeys(event.client().options);
              }
            });
  }

  /** True means accepted; callers resume on their next unpaused tick after completion. */
  static boolean start(Minecraft mc, LocalPlayer player, int from, int to) {
    if (mc == null || player == null || mc.player != player || mc.gameMode == null || isBusy()) {
      return false;
    }
    if (from < 0
        || from >= 36
        || from == MacroSlots.RESERVED_HOTBAR_SLOT
        || !MacroSlots.isUsableHotbarSlot(to)) {
      return false;
    }
    if (player.containerMenu != player.inventoryMenu
        || !player.inventoryMenu.getCarried().isEmpty()) {
      return false;
    }
    if (mc.gui.screen() != null && !(mc.gui.screen() instanceof InventoryScreen)) {
      return false;
    }
    if (from == to) {
      return true;
    }
    Slot fromSlot = MacroSlots.findPlayerInventoryMenuSlot(player.inventoryMenu, player, from);
    Slot toSlot = MacroSlots.findPlayerInventoryMenuSlot(player.inventoryMenu, player, to);
    if (fromSlot == null || toSlot == null || !fromSlot.hasItem()) {
      return false;
    }
    owner = player;
    source = fromSlot;
    destination = toSlot;
    original = source.getItem().copy();
    screen = mc.gui.screen() instanceof InventoryScreen inventory ? inventory : null;
    closeWhenDone = screen == null;
    step = delay = elapsed = settled = 0;
    if (screen != null) {
      delay = CLICK_DELAY;
    }
    ScreenOwner.macroOpenedInventory();
    MacroInput.releaseAllKeys(mc.options);
    return true;
  }

  public static void tick(Minecraft mc) {
    if (!isBusy()) {
      return;
    }
    if (mc.player != owner || mc.level == null || mc.gameMode == null) {
      cancel();
      return;
    }
    MacroInput.releaseAllKeys(mc.options);
    if (++elapsed > 200) {
      fail("Inventory hotbar transfer timed out");
      return;
    }
    if (owner.containerMenu != owner.inventoryMenu
        || (screen != null && mc.gui.screen() != screen)
        || (screen == null && mc.gui.screen() != null)) {
      fail("Inventory changed during hotbar transfer");
      return;
    }
    ScreenOwner.macroOpenedInventory();
    if (screen == null) {
      if (owner.getDeltaMovement().horizontalDistanceSqr() > 0.0004) {
        settled = 0;
        return;
      }
      if (++settled < CLICK_DELAY) {
        return;
      }
      screen = new InventoryScreen(owner);
      mc.gui.setScreen(screen);
      delay = CLICK_DELAY;
      return;
    }
    if (delay-- > 0) {
      return;
    }
    var menu = screen.getMenu();
    ItemStack carried = menu.getCarried();
    int click =
        nextClick(
            step,
            source.index,
            destination.index,
            original,
            source.getItem(),
            destination.getItem(),
            carried);
    if (click == WAIT) {
      return;
    }
    if (click == DONE) {
      if (closeWhenDone) {
        MacroScreens.closeCurrentScreen(mc);
      }
      cancel();
      return;
    }
    MacroSlots.clickSlot(mc, menu, click, 0, ContainerInput.PICKUP, owner);
    step++;
    delay = CLICK_DELAY;
  }

  static final int WAIT = -1;
  static final int DONE = -2;

  static int nextClick(
      int step,
      int sourceSlot,
      int destinationSlot,
      ItemStack original,
      ItemStack source,
      ItemStack destination,
      ItemStack cursor) {
    return switch (step) {
      case 0 -> cursor.isEmpty() && ItemStack.matches(source, original) ? sourceSlot : WAIT;
      case 1 -> source.isEmpty() && ItemStack.matches(cursor, original) ? destinationSlot : WAIT;
      case 2 ->
          !ItemStack.isSameItemSameComponents(destination, original)
              ? WAIT
              : cursor.isEmpty() ? DONE : source.isEmpty() ? sourceSlot : WAIT;
      case 3 ->
          cursor.isEmpty() && ItemStack.isSameItemSameComponents(destination, original)
              ? DONE
              : WAIT;
      default -> WAIT;
    };
  }

  public static void cancel() {
    owner = null;
    screen = null;
    source = destination = null;
    original = null;
  }

  private static void fail(String reason) {
    cancel();
    RyznBootstrap.failsafeManager().stopAllMacros(reason);
  }
}
