package ryzn.macro.client.feature.pathfinder.path;

/**
 * Pure math extracted from PathExecutor's rotation pipeline. Kept side-effect free so it can be
 * unit-tested without a live Minecraft instance.
 */
public final class RotationSmoothing {

  private RotationSmoothing() {}

  /**
   * Minecraft's mouse input GCD: the smallest rotation step the vanilla mouse code can produce at a
   * given sensitivity. Quantizing to this grid keeps packet traffic indistinguishable from human
   * input.
   */
  public static float mouseGcd(double sensitivity) {
    double scaled = sensitivity * 0.6 + 0.2;
    return (float) (scaled * scaled * scaled * 1.2);
  }

  /**
   * Snaps {@code value} to the nearest multiple of {@code step}. Returns the value unchanged when
   * step is non-positive.
   */
  public static float quantize(float value, float step) {
    if (step <= 0) {
      return value;
    }
    return Math.round(value / step) * step;
  }
}
