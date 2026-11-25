package ryzn.macro.client.core.events.api;

import ryzn.macro.client.render.RyznRenderContext;

/**
 * Start-of-main-level-render notification from the Minecraft hook.
 *
 * @param context the original terrain render context
 */
public record StartMainRenderEvent(RyznRenderContext context) implements RuntimeEvent {}
