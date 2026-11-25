package ryzn.macro.client.core.events.api;

/** Marker for synchronous, typed runtime notifications. */
public interface Event {
  /**
   * @description Whether a listener failure disables Ryzn, or is only logged.
   * @return true unless the event must keep reaching its remaining listeners
   */
  default boolean fatalOnFailure() {
    return true;
  }
}
