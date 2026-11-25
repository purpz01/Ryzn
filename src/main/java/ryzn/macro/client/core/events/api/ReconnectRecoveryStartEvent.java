package ryzn.macro.client.core.events.api;

/**
 * Automatic recovery began.
 *
 * @param generation the new recovery generation
 */
public record ReconnectRecoveryStartEvent(long generation) implements Event {
  @Override
  public boolean fatalOnFailure() {
    return false;
  }
}
