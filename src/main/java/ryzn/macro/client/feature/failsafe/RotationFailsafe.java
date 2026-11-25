package ryzn.macro.client.feature.failsafe;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import ryzn.macro.client.config.groups.FailsafeSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.failsafe.api.Failsafe;
import ryzn.macro.client.feature.rotation.api.RotationManager;

public final class RotationFailsafe extends Failsafe {
  /**
   * @description Creates the process-lifetime detector owned by the failsafe manager.
   */
  public RotationFailsafe() {
    super("rotation", "Forced Rotation", "Detects forced rotation.");
  }

  private final FailsafeDelay delay = new FailsafeDelay();

  void tick(Minecraft client, long tick, FailsafeSettings settings) {
    if (client == null || client.player == null) {
      reset();
      return;
    }
    RotationManager.Deviation deviation =
        RyznBootstrap.rotationManager()
            .rotationDeviation(
                client.player,
                tick,
                settings.isEnabled()
                    && settings.isRotationEnabled()
                    && FailsafeActivity.foregroundActive());
    if (deviation == null) {
      delay.reset();
      return;
    }
    boolean changed =
        deviation.yaw() > settings.getRotationYawThreshold()
            || deviation.pitch() > settings.getRotationPitchThreshold();
    if (delay.update(
        changed,
        tick,
        settings.getRotationDelayTicks(),
        () -> FailsafeCoordinator.sampleAdditionalDelay(settings))) {
      RyznBootstrap.failsafeManager()
          .trigger(
              FailsafeType.ROTATION,
              String.format(
                  Locale.ROOT,
                  "rotation changed by %.1f yaw / %.1f pitch",
                  deviation.yaw(),
                  deviation.pitch()));
    }
  }

  void expect(float yaw, float pitch) {
    RyznBootstrap.rotationManager().expectRotation(yaw, pitch);
    delay.reset();
  }

  void allowGrace(long untilTick) {
    RyznBootstrap.rotationManager().allowRotationGrace(untilTick);
    delay.reset();
  }

  void reset() {
    RyznBootstrap.rotationManager().resetRotationExpectation();
    delay.reset();
  }
}
