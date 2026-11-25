package ryzn.macro.client.feature.modules.impl;

import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.feature.modules.api.ModuleManager;

/** Explicit module registry, expanded as modules migrate to instance ownership. */
public final class ModuleManagerImpl extends ModuleManager {
  /**
   * @description Registers the live auto sprint element already owned by the config root.
   */
  @Override
  protected void registerElements() {
    register(RyznSettings.get().combat());
  }
}
