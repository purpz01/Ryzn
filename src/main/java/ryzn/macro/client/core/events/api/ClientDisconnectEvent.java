package ryzn.macro.client.core.events.api;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * The client left a server.
 *
 * @param handler the connection that closed
 * @param client the active Minecraft client
 */
public record ClientDisconnectEvent(ClientPacketListener handler, Minecraft client)
    implements RuntimeEvent {}
