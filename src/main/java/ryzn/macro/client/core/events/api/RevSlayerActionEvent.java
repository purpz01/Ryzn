package ryzn.macro.client.core.events.api;

/**
 * Requests a synchronous change to the Rev Slayer macro's run state.
 *
 * @description Carries a menu or failsafe request to the owning macro.
 * @param enabled the requested run state
 */
public record RevSlayerActionEvent(boolean enabled) implements Event {}
