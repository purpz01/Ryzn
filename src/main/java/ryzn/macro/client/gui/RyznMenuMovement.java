package ryzn.macro.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.entity.player.Input;
import org.lwjgl.glfw.GLFW;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.mixin.accessor.KeyMappingAccessor;

public final class RyznMenuMovement {
  private RyznMenuMovement() {}

  public static Input resolve(Input original) {
    var client = Minecraft.getInstance();
    if (!RyznSettings.get().ryznMenu().isMoveInMenu()
        || !(client.gui.screen() instanceof RyznModulesScreen menu)
        || menu.isPauseScreen()
        || menu.isConfirmingReset()
        || menu.getFocused() instanceof EditBox
        || !client.isWindowActive()) {
      return original;
    }
    var options = client.options;
    return new Input(
        down(client, options.keyUp),
        down(client, options.keyDown),
        down(client, options.keyLeft),
        down(client, options.keyRight),
        down(client, options.keyJump),
        down(client, options.keyShift),
        menu.sprintInput());
  }

  static boolean down(Minecraft client, KeyMapping mapping) {
    var key = ((KeyMappingAccessor) mapping).ryzn$key();
    if (key == InputConstants.UNKNOWN) return false;
    return switch (key.getType()) {
      case KEYSYM -> InputConstants.isKeyDown(client.getWindow(), key.getValue());
      case MOUSE ->
          GLFW.glfwGetMouseButton(client.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
      case SCANCODE -> false;
    };
  }
}
