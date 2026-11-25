package ryzn.macro.client.feature.rotation.impl;

import net.minecraft.client.Minecraft;

/** Mouse-sensitivity rotation quantum shared by assisted aim controllers. */
public final class MouseGcd {

  /**
   * @description Prevents construction of this stateless rotation helper.
   */
  private MouseGcd() {}

  /** Stands in for the options slider when there is no client: the headless sandbox sets it. */
  private static volatile double headlessSensitivity = 0.5;

  /**
   * @description Calculates the current mouse-sensitivity rotation quantum.
   * @return the minimum vanilla-sized angle step in degrees
   */
  public static float quantum() {
    final Minecraft client = Minecraft.getInstance();
    double sensitivity = client != null ? client.options.sensitivity().get() : headlessSensitivity;
    double scaled = sensitivity * 0.6 + 0.2;
    return (float) (scaled * scaled * scaled * 1.2);
  }

  /**
   * @description Rounds an angle to the nearest whole sensitivity step.
   * @param value the angle to quantize
   * @param step the positive quantum, or a nonpositive value to leave the angle unchanged
   * @return the quantized angle
   */
  public static float quantize(float value, float step) {
    return step <= 0f ? value : Math.round(value / step) * step;
  }
}
