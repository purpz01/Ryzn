package ryzn.macro.client.core.events.api;

import net.minecraft.client.Minecraft;

/**
 * End-of-client-tick notification from the Minecraft hook.
 *
 * @param client the active Minecraft client
 */
public record EndClientTickEvent(Minecraft client) implements RuntimeEvent {}
