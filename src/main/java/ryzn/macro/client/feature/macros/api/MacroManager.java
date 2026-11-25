package ryzn.macro.client.feature.macros.api;

import ryzn.macro.client.feature.api.Manager;

/** Ordered owner of registered macros and active automation queries. */
public abstract class MacroManager extends Manager<Macro> {
  /**
   * @description Reports whether any runnable automation currently owns macro controls.
   * @return true while a registered or legacy macro is active
   */
  public abstract boolean anyMacroRunning();
}
