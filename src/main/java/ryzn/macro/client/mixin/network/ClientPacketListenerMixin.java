package ryzn.macro.client.mixin.network;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ryzn.macro.client.core.RyznHooks.RevSlayerMacro;

/**
 * Packet-driven world observation hooked into {@link ClientPacketListener}: armor-stand data
 * (fishing hotspots), bobber flight packets, Hypixel particle/sound cues and the ping monitor.
 * Read-only - nothing is blocked, altered, delayed or sent.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

  @Inject(
      method = {"handleContainerSetSlot", "handleContainerContent"},
      at = @At("TAIL"))
  private void ryzn$observeHarpPads(CallbackInfo ci) {
    ryzn.macro.client.core.RyznHooks.AutoHarpHooks.onNotesUpdated(Minecraft.getInstance());
  }

  /** Enables the vanilla ping monitor while the debug charts are closed. */
  @ModifyExpressionValue(
      method = "tick",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/components/DebugScreenOverlay;showNetworkCharts()Z"))
  private boolean ryzn$alwaysMeasurePing(boolean original) {
    return true;
  }

  @Inject(method = "handleParticleEvent", at = @At("RETURN"))
  private void ryzn$readParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
    RevSlayerMacro.onParticle(packet);
  }

  @Inject(method = "handleSoundEvent", at = @At("RETURN"))
  private void ryzn$readSounds(ClientboundSoundPacket packet, CallbackInfo ci) {
    RevSlayerMacro.onSound(packet);
  }
}
