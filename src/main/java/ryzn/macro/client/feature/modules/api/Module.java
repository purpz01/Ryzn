package ryzn.macro.client.feature.modules.api;

import ryzn.macro.client.feature.api.Element;

/** A menu feature that can be enabled independently. */
public abstract class Module extends Element {
  /**
   * @description Creates a module with stable config and menu identity.
   * @param id the existing configuration section ID
   * @param name the menu display name
   * @param description the menu display description
   */
  protected Module(String id, String name, String description) {
    super(id, name, description);
  }
}
