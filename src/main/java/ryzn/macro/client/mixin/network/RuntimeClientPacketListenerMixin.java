package ryzn.macro.client.mixin.network;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ryzn.macro.client.core.RyznRuntime;
import ryzn.macro.client.feature.commands.api.RyznCommands;

@Mixin(ClientPacketListener.class)
public abstract class RuntimeClientPacketListenerMixin {
  @Unique private boolean ryzn$disconnected;

  @Inject(method = "close", at = @At("HEAD"))
  private void ryzn$disconnect(CallbackInfo ci) {
    if (!ryzn$disconnected) {
      ryzn$disconnected = true;
      RyznRuntime.disconnect((ClientPacketListener) (Object) this);
    }
  }

  @Inject(method = "handleCommands", at = @At("RETURN"))
  private void ryzn$commandSuggestions(ClientboundCommandsPacket packet, CallbackInfo ci) {
    RyznCommands.installSuggestions((ClientPacketListener) (Object) this);
  }

  @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
  private void ryzn$clientCommand(String command, CallbackInfo ci) {
    if (RyznCommands.execute(command)) {
      ci.cancel();
    }
  }

  @Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
  private void ryzn$unattendedClientCommand(String command, Screen screen, CallbackInfo ci) {
    if (RyznCommands.execute(command)) {
      ci.cancel();
    }
  }
}
