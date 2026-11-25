package ryzn.macro.client.core.events.api;

/**
 * Automatic reconnect put the player back in SkyBlock; deferred recoveries may resume.
 *
 * @param generation the recovery generation this hand-over belongs to
 */
public record ReconnectRejoinEvent(long generation) implements Event {
  @Override
  public boolean fatalOnFailure() {
    return false;
  }
}
