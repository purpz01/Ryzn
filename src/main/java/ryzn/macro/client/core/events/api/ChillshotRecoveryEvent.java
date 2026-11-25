package ryzn.macro.client.core.events.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * Asks the Chillshot recovery to hop lobbies and resume the hunt instead of stopping it.
 *
 * <p>The failsafe needs the answer synchronously: the hunting macro records whether its recovery
 * took the incident, and the failsafe stops everything when it did not.
 */
@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public final class ChillshotRecoveryEvent implements Event {
  /** The failsafe incident that triggered the request, shown in the recovery status. */
  private final String reason;

  /** True only after the registered hunting macro started a hop. */
  private boolean accepted;

  /**
   * @description Records whether the recovery took the incident.
   * @param accepted whether a hop started
   */
  public void setAccepted(boolean accepted) {
    this.accepted = accepted;
  }
}
