package ryzn.macro.client.mixin.accessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Vanilla's chat click handler, so a macro answering a chat option sends what a mouse click does.
 */
@Mixin(Screen.class)
public interface ScreenAccessor {

  @Invoker("defaultHandleGameClickEvent")
  static void ryzn$defaultHandleGameClickEvent(ClickEvent click, Minecraft mc, Screen screen) {
    throw new AssertionError();
  }
}
