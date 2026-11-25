package ryzn.macro.client.feature.failsafe.api;

import ryzn.macro.client.feature.api.Element;

/** A detector that can trigger the shared automation stop path. */
public abstract class Failsafe extends Element {
  /**
   * @description Creates a failsafe with stable config and menu identity.
   * @param id the existing configuration section ID
   * @param name the menu display name
   * @param description the menu display description
   */
  protected Failsafe(String id, String name, String description) {
    super(id, name, description);
  }
}
