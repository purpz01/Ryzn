package ryzn.macro.client.core.events.api;

/**
 * Requests a synchronous change to the Mining Merchant run state.
 *
 * @description Carries a menu, command, or failsafe request to the owning macro.
 * @param enabled the requested run state
 */
public record MiningMerchantActionEvent(boolean enabled) implements Event {}
