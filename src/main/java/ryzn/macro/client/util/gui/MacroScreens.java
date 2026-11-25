package ryzn.macro.client.util.gui;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.util.inventory.MacroSlots;
import ryzn.macro.client.util.text.MacroText;

public final class MacroScreens {

  private MacroScreens() {}

  public static String cleanTitle(Minecraft mc) {
    if (mc == null || mc.gui.screen() == null || mc.gui.screen().getTitle() == null) {
      return "";
    }
    return MacroText.stripFormatting(mc.gui.screen().getTitle().getString());
  }

  public static boolean titleContains(Minecraft mc, String needle) {
    if (needle == null || needle.isEmpty()) {
      return false;
    }
    return cleanTitle(mc).toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  /**
   * Close whatever menu is open, unless the player opened it themselves.
   *
   * <p>Macros call this from every "something unexpected is on screen" branch, which is right for a
   * stray server menu and wrong for the player's own inventory: opening it is how someone checks on
   * a running macro, and having it snatched shut is both useless and obvious. The player's
   * inventory is left alone and {@code UnexpectedScreenFailsafe} stops the macro instead - see
   * {@link ScreenOwner} for how a macro's own menu is told apart from one the player opened.
   */
  public static void closeCurrentScreen(Minecraft mc) {
    if (mc == null || mc.gui.screen() == null || ScreenOwner.isPlayerInventory(mc)) {
      return;
    }
    DebugLog.action(DebugLog.Category.MACRO, "gui: closing \"%s\"", cleanTitle(mc));
    if (mc.player != null && mc.gui.screen() instanceof AbstractContainerScreen<?>) {
      mc.player.closeContainer();
    }
    if (mc.gui.screen() != null) {
      mc.gui.setScreen(null);
    }
  }

  public static AbstractContainerMenu currentContainerMenu(Minecraft mc) {
    if (mc == null || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
      return null;
    }
    return screen.getMenu();
  }

  public static boolean closeCurrentScreenIfCursorEmpty(Minecraft mc) {
    if (mc == null || mc.gui.screen() == null) {
      return true;
    }
    final AbstractContainerMenu menu = currentContainerMenu(mc);
    if (menu != null && !MacroSlots.isCursorEmpty(menu)) {
      return false;
    }
    closeCurrentScreen(mc);
    return true;
  }
}
