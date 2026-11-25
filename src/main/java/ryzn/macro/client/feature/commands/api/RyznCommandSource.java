package ryzn.macro.client.feature.commands.api;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public interface RyznCommandSource extends SharedSuggestionProvider {
  default Minecraft getClient() {
    return Minecraft.getInstance();
  }

  default LocalPlayer getPlayer() {
    return getClient().player;
  }

  default ClientLevel getLevel() {
    return getClient().level;
  }

  default Entity getEntity() {
    return getPlayer();
  }

  default Vec3 getPosition() {
    return getPlayer() == null ? Vec3.ZERO : getPlayer().position();
  }

  default Vec2 getRotation() {
    return getPlayer() == null ? Vec2.ZERO : getPlayer().getRotationVector();
  }

  default void sendFeedback(Component message) {
    getClient().gui.hud.getChat().addClientSystemMessage(message);
    getClient().getNarrator().saySystemChatQueued(message);
  }

  default void sendError(Component message) {
    sendFeedback(Component.empty().append(message).withStyle(net.minecraft.ChatFormatting.RED));
  }
}
