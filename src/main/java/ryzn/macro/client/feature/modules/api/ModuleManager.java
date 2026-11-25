package ryzn.macro.client.feature.modules.api;

import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.feature.api.Manager;

/** Registers the live module instances in menu order. */
public final class ModuleManager extends Manager<Module> {
  @Override
  protected void registerElements() {
    register(RyznSettings.get().combat());
  }
}
