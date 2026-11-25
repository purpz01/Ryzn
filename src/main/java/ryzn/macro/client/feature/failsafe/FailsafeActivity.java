package ryzn.macro.client.feature.failsafe;

import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.macros.impl.wheat.WheatBreakerMacro;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;

final class FailsafeActivity {
  private FailsafeActivity() {}

  static boolean foregroundActive() {
    return RyznBootstrap.macroManager().anyMacroRunning() || navigationActive();
  }

  static boolean farmingActive() {
    return RyznBootstrap.macroManager().getByClass(WheatBreakerMacro.class).isHarvesting();
  }

  private static boolean navigationActive() {
    return WalkingNavigation.isNavigationActive();
  }
}
