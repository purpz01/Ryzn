package ryzn.macro.client.feature.failsafe;

import net.minecraft.network.chat.Component;
import ryzn.macro.client.config.groups.FailsafeSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.failsafe.api.Failsafe;

public final class GhostBlockFailsafe extends Failsafe {
  /**
   * @description Creates the process-lifetime detector owned by the failsafe manager.
   */
  public GhostBlockFailsafe() {
    super("ghost_block", "Ghost Block", "Detects ghost block.");
  }

  private static final int FARMING_TEXT_FRESHNESS_TICKS = 30;

  private final FailsafeDelay delay = new FailsafeDelay();
  private long lastFarmingTextTick = Long.MIN_VALUE;

  void observe(Component component, long tick) {
    if (component != null && component.getString().contains("+")) {
      lastFarmingTextTick = tick;
    }
  }

  void tick(long tick, FailsafeSettings settings) {
    if (!settings.isEnabled()
        || !settings.isGhostBlockEnabled()
        || !FailsafeActivity.farmingActive()) {
      reset();
      return;
    }
    boolean missing =
        lastFarmingTextTick == Long.MIN_VALUE
            || tick - lastFarmingTextTick > FARMING_TEXT_FRESHNESS_TICKS;
    int triggerTicks = settings.getGhostBlockWindowTicks() + settings.getGhostBlockDelayTicks();
    if (delay.update(
        missing, tick, triggerTicks, () -> FailsafeCoordinator.sampleAdditionalDelay(settings))) {
      RyznBootstrap.failsafeManager()
          .trigger(FailsafeType.GHOST_BLOCK, "farming XP text disappeared while harvesting");
    }
  }

  void reset() {
    lastFarmingTextTick = Long.MIN_VALUE;
    delay.reset();
  }
}
