package ryzn.macro.client.core;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import ryzn.macro.client.core.events.api.ClientDisconnectEvent;
import ryzn.macro.client.core.events.api.CollectSubmitsEvent;
import ryzn.macro.client.core.events.api.EndClientTickEvent;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.StartClientTickEvent;
import ryzn.macro.client.core.events.api.StartMainRenderEvent;
import ryzn.macro.client.gui.RyznModulesScreen;
import ryzn.macro.client.render.RyznRenderContext;

public final class RyznRuntime {
  private RyznRuntime() {}

  public static void startClient(Minecraft client) {
    RyznBootstrap.onClientStarted();
    RyznBootstrap.onClientAvailable(client);
  }

  public static void stopClient() {
    RyznBootstrap.shutdown();
  }

  public static void startTick(Minecraft client) {
    RyznBootstrap.onClientAvailable(client);
    RyznModulesScreen.tickMenuKey(client);
    RyznBootstrap.eventBus().post(new StartClientTickEvent(client));
  }

  public static void endTick(Minecraft client) {
    RyznBootstrap.eventBus().post(new EndClientTickEvent(client));
  }

  public static void disconnect(ClientPacketListener listener) {
    RyznBootstrap.eventBus().post(new ClientDisconnectEvent(listener, Minecraft.getInstance()));
  }

  public static void startMain(SubmitNodeCollector collector) {
    RyznBootstrap.eventBus().post(new StartMainRenderEvent(new RyznRenderContext(null, collector)));
  }

  public static void collectSubmits(
      PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
    ryzn.macro.client.feature.pathfinder.path.PathTelemetry.onFrame();
    RyznBootstrap.eventBus()
        .post(new CollectSubmitsEvent(new RyznRenderContext(pose, collector, camera)));
  }

  public static Component gameMessage(Component message, boolean overlay) {
    if (overlay) {
      RyznHooks.observeOverlay(message);
    }
    RyznBootstrap.eventBus().post(new GameMessageEvent(message, overlay));
    return message;
  }
}
