package ryzn.macro.client.core.events.api;

import net.minecraft.client.Minecraft;

/**
 * Start-of-client-tick notification from the Minecraft hook.
 *
 * @param client the active Minecraft client
 */
public record StartClientTickEvent(Minecraft client) implements RuntimeEvent {}
