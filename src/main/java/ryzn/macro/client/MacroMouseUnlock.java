package ryzn.macro.client;

import lombok.Getter;
import net.minecraft.client.Minecraft;
import ryzn.macro.client.core.RyznBootstrap;

/** Releases the cursor while an Ryzn macro is running. */
public final class MacroMouseUnlock {

  @Getter private static boolean cursorReleased;

  private MacroMouseUnlock() {}

  public static void reset(Minecraft client) {
    boolean wasReleased = cursorReleased;
    cursorReleased = false;
    if (wasReleased && client.gui.screen() == null && !client.mouseHandler.isMouseGrabbed()) {
      client.mouseHandler.grabMouse();
    }
  }

  /**
   * Whether the player's own clicks/scrolls should be swallowed. Tracks the macro directly rather
   * than the grab state, so freelook can hold the cursor without handing manual input back to a
   * player who is only meant to be looking around.
   */
  public static boolean shouldSwallowManualInput() {
    return RyznBootstrap.macroManager().anyMacroRunning();
  }

  public static void onClientTick(Minecraft client) {
    // Freelook needs a grabbed cursor to receive mouse deltas at all, so it temporarily wins
    // over the macro's release; the cursor goes back out when freelook ends.
    boolean shouldRelease = RyznBootstrap.macroManager().anyMacroRunning();
    if (cursorReleased == shouldRelease) {
      return;
    }

    cursorReleased = shouldRelease;
    if (shouldRelease) {
      if (client.mouseHandler.isMouseGrabbed()) {
        client.mouseHandler.releaseMouse();
      }
    } else if (!client.mouseHandler.isMouseGrabbed() && client.gui.screen() == null) {
      client.mouseHandler.grabMouse();
    }
  }
}
