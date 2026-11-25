package ryzn.macro.client.core.events.api;

import ryzn.macro.client.render.RyznRenderContext;

/**
 * World-render submission notification from the Minecraft hook.
 *
 * @param context the original level render context
 */
public record CollectSubmitsEvent(RyznRenderContext context) implements RuntimeEvent {}
