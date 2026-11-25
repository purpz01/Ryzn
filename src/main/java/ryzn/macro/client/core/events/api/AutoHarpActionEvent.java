package ryzn.macro.client.core.events.api;

/**
 * Requests a synchronous change to the Auto Harp macro's run state.
 *
 * @description Carries a menu, failsafe, or AFK handoff request to the owning macro.
 * @param enabled the requested run state
 */
public record AutoHarpActionEvent(boolean enabled) implements Event {}
