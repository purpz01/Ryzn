package ryzn.macro.client.core.events.api;

/**
 * Requests a synchronous change to the Carpentry Unlock run state.
 *
 * @description Carries a menu, command, or failsafe request to the owning macro.
 * @param enabled the requested run state
 */
public record CarpentryUnlockActionEvent(boolean enabled) implements Event {}
