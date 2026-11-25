package ryzn.macro.client.core.hooks;

import net.minecraft.client.Minecraft;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.macros.impl.foraging.AutoHarpMacro;

/**
 * @description Auto harp hooks behind the mixin-facing RyznHooks facade.
 */
public final class AutoHarpHooks {
  private AutoHarpHooks() {}

  public static void onNotesUpdated(Minecraft client) {
    if (RyznBootstrap.isReady()) {
      RyznBootstrap.macroManager().getByClass(AutoHarpMacro.class).onNotesUpdated(client);
    }
  }
}
