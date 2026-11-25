package ryzn.macro.client.core.events.api;

import net.minecraft.network.chat.Component;

/**
 * Carries one received in-game chat message at its original callback position.
 *
 * @description Lets the registered feature inspect a message without owning a Fabric callback.
 * @param message the received game component, possibly null
 * @param overlay whether Minecraft marked the message as an overlay
 */
public record GameMessageEvent(Component message, boolean overlay) implements RuntimeEvent {}
