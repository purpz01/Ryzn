package ryzn.macro.client.core.events.api;

/**
 * Requests a synchronous change to the AFK Auto Harp macro's run state.
 *
 * @description Carries a menu, failsafe, or foraging quest request to the owning macro.
 * @param enabled the requested run state
 */
public record AfkAutoHarpActionEvent(boolean enabled) implements Event {}
