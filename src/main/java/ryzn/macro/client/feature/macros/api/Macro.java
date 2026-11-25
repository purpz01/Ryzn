package ryzn.macro.client.feature.macros.api;

import ryzn.macro.client.feature.api.Element;

/** A runnable automation feature. */
public abstract class Macro extends Element {
  /**
   * @description Creates a macro with stable config and menu identity.
   * @param id the existing configuration section ID
   * @param name the menu display name
   * @param description the menu display description
   */
  protected Macro(String id, String name, String description) {
    super(id, name, description);
  }
}
