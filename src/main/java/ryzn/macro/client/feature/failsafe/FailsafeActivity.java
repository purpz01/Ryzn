package ryzn.macro.client.feature.failsafe;

import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.feature.macros.impl.wheat.WheatBreakerMacro;

final class FailsafeActivity {
  private FailsafeActivity() {}

  static boolean foregroundActive() {
    return RyznBootstrap.macroManager().anyMacroRunning() || navigationActive();
  }

  static boolean farmingActive() {
    return RyznBootstrap.macroManager().getByClass(WheatBreakerMacro.class).isHarvesting();
  }

  private static boolean navigationActive() {
    return PathfindCommand.isNavigationActive();
  }
}
