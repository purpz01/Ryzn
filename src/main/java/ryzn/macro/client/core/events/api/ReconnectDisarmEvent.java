package ryzn.macro.client.core.events.api;

/** A manual stop, import or shutdown cancelled automatic recovery; drop any deferred restart. */
public record ReconnectDisarmEvent() implements Event {
  @Override
  public boolean fatalOnFailure() {
    return false;
  }
}
