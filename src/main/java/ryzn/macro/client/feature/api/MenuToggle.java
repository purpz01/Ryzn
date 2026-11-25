package ryzn.macro.client.feature.api;

/** A registered feature whose menu row shows an on/off switch. */
public interface MenuToggle {
  /**
   * @description Reports the state the feature's menu switch shows.
   * @return true while the feature is on or running
   */
  boolean menuEnabled();

  /**
   * @description Flips the feature from its menu switch through the same request path its command
   *     and failsafe use.
   */
  void menuToggle();
}
