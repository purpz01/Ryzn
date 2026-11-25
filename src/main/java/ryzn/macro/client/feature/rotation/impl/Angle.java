package ryzn.macro.client.feature.rotation.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/** Mutable yaw/pitch pair. */
@AllArgsConstructor
@Getter
@Setter
public class Angle {
  /** Current horizontal angle in degrees; the generated accessor exposes this mutable value. */
  public float yaw;

  /** Current vertical angle in degrees; the generated accessor exposes this mutable value. */
  public float pitch;

  /**
   * @description Adds the absolute yaw and pitch magnitudes.
   * @return the sum of component magnitudes
   */
  public float getValue() {
    return Math.abs(this.yaw) + Math.abs(this.pitch);
  }

  /**
   * @description Formats the mutable yaw and pitch values for diagnostics.
   * @return a readable rotation description
   */
  @Override
  public String toString() {
    return "Rotation{" + "yaw=" + yaw + ", pitch=" + pitch + "}";
  }
}
