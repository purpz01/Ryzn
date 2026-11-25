package ryzn.macro.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-applies the cursor grab when the window system silently drops it on Linux. MC forces GLFW's
 * X11 backend (XWayland on Wayland desktops), which skips re-grabbing on focus-in events tagged
 * NotifyGrab/NotifyUngrab (alt-tab, compositor shortcuts) and ignores XGrabPointer failures, so MC
 * stays "grabbed" while the pointer roams out of the window. Vanilla usually hides this because
 * refocusing goes through the pause screen; keep-focus skips that, so we redo the grab ourselves.
 */
public final class CursorGrabGuard {

  private static final Logger logger = LoggerFactory.getLogger("ryzn");
  private static final int REGRAB_COOLDOWN_TICKS = 10;

  private static boolean wasFocused = true;
  private static boolean pending;
  private static int cooldown;
  private static boolean logged;

  private CursorGrabGuard() {}

  public static void onClientTick(Minecraft client) {
    int platform = GLFW.glfwGetPlatform();
    if (platform != GLFW.GLFW_PLATFORM_X11 && platform != GLFW.GLFW_PLATFORM_WAYLAND) {
      return;
    }
    Window window = client.getWindow();
    boolean focused = window.isFocused();
    if (focused && !wasFocused) {
      pending = true;
    }
    wasFocused = focused;
    if (cooldown > 0) {
      cooldown--;
    }
    if (!focused || client.gui.screen() != null || !client.mouseHandler.isMouseGrabbed()) {
      return;
    }
    long handle = window.handle();
    if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_HOVERED) == GLFW.GLFW_FALSE) {
      pending = true;
    }
    if (!pending || cooldown > 0 || anyButtonHeld(handle)) {
      return;
    }
    pending = false;
    cooldown = REGRAB_COOLDOWN_TICKS;
    // GLFW no-ops a same-mode set, so cycle through NORMAL to force a fresh pointer grab.
    double x = window.getScreenWidth() / 2.0;
    double y = window.getScreenHeight() / 2.0;
    InputConstants.grabOrReleaseMouse(window, InputConstants.CURSOR_NORMAL, x, y);
    InputConstants.grabOrReleaseMouse(window, InputConstants.CURSOR_DISABLED, x, y);
    client.mouseHandler.setIgnoreFirstMove();
    if (!logged) {
      logged = true;
      logger.info("Re-applied lost cursor grab (further re-grabs are not logged)");
    }
  }

  private static boolean anyButtonHeld(long handle) {
    for (int button = GLFW.GLFW_MOUSE_BUTTON_1; button <= GLFW.GLFW_MOUSE_BUTTON_3; button++) {
      if (GLFW.glfwGetMouseButton(handle, button) == GLFW.GLFW_PRESS) {
        return true;
      }
    }
    return false;
  }
}
