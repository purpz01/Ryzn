package ryzn.macro.client.mixin.network;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds every inbound S2C packet to read-only observers (world cache, world-change detection,
 * InvWalk's ping gate, and the TPS meter). Observes the client's own {@link
 * Connection#channelRead0} (the Netty inbound handler) at HEAD; bundle packets are unwrapped inside
 * each observer. Nothing is sent, blocked, delayed, or altered - this only reads the packets the
 * client already received, so it is invisible to the server.
 */
@Mixin(Connection.class)
public class ClientConnectionMixin {

  @Inject(method = "channelRead0", at = @At("HEAD"))
  private void ryzn$capturePathfinderPacket(
      ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
    ryzn.macro.client.core.RyznHooks.inboundPacket(packet);
  }
}
