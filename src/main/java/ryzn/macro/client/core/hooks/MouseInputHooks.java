package ryzn.macro.client.core.hooks;

import net.minecraft.client.Minecraft;
import ryzn.macro.client.core.RyznHooks.MacroLook;
import ryzn.macro.client.core.RyznHooks.MacroMouseUnlock;
import ryzn.macro.client.core.RyznHooks.RyznSandMiner;

/**
 * @description Mouse input hooks behind the mixin-facing RyznHooks facade.
 */
public final class MouseInputHooks {
  private MouseInputHooks() {}

  public static boolean blocksClick(int button, int action) {
    if (MacroMouseUnlock.shouldSwallowManualInput()
        && Minecraft.getInstance().gui.screen() == null) {
      return true;
    }
    return RyznSandMiner.shouldSwallowManualClicks()
        && action == com.mojang.blaze3d.platform.InputConstants.PRESS
        && (button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
            || button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT);
  }

  public static boolean blocksScroll(double amount) {
    return MacroMouseUnlock.shouldSwallowManualInput()
        && Minecraft.getInstance().gui.screen() == null;
  }

  public static boolean keepsCursorReleased() {
    return MacroMouseUnlock.isCursorReleased();
  }

  public static boolean blocksLook() {
    return MacroLook.anyMacroOwnsLook();
  }
}
