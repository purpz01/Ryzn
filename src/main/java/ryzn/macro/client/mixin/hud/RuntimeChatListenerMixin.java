package ryzn.macro.client.mixin.hud;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import ryzn.macro.client.core.RyznRuntime;

@Mixin(ChatListener.class)
public abstract class RuntimeChatListenerMixin {
  @WrapMethod(method = "handleSystemMessage")
  private void ryzn$gameMessage(Component message, boolean fromServer, Operation<Void> original) {
    original.call(RyznRuntime.gameMessage(message, false), fromServer);
  }

  @WrapMethod(method = "handleOverlay")
  private void ryzn$overlayMessage(Component message, Operation<Void> original) {
    original.call(RyznRuntime.gameMessage(message, true));
  }
}
