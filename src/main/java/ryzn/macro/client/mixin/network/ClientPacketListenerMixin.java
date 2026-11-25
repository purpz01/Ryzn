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

  /**
   * Post-apply tap on armor-stand entity data updates (main thread, after the custom name/flags are
   * written to the entity). Packet-driven detection keeps working while the window is unfocused or
   * minimized, where anything render-frame based goes stale - the same injection point Skyblocker's
   * sea creature tracker uses.
   */

  /**
   * Reads the fishing hook's flight as the SERVER sent it: launch velocity off the spawn packet,
   * then the exact per-update deltas, absolute syncs and motion changes. The entity cannot supply
   * any of this - the client smooths hook positions between updates - and on a server that does not
   * run stock hook physics these packets are the only measurement the cast model can be built from
   * instead of curve-fitted.
   */

  /**
   * Keeps vanilla's own ping measurement running while the F3 network charts are closed.
   *
   * <p>{@code ClientPacketListener#tick} only ticks its {@link
   * net.minecraft.client.multiplayer.PingDebugMonitor} when {@code showNetworkCharts()} is true, so
   * the meter is dead unless F3+3 is up. Forcing the check true is exactly what SkyHanni and
   * Skyblocker do (both on Hypixel's allowed list), and it means the ping traffic is vanilla's own
   * {@code ServerboundPingRequestPacket}, sent by vanilla code on vanilla's schedule - Ryzn never
   * crafts or sends anything itself.
   */
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
