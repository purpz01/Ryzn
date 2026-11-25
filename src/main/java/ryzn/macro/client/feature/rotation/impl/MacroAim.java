package ryzn.macro.client.feature.rotation.impl;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Shared NPC-aim geometry for the Skyblock macros. Every aiming macro independently re-implemented
 * these identical helpers for picking an aim point on an NPC and turning a (eye -> target) vector
 * into yaw/pitch; consolidated here. Behaviour is byte-for-byte the same as the per-macro copies
 * they replace.
 */
public final class MacroAim {

  private MacroAim() {}

  /** Fraction of an NPC's hitbox height to aim at (chest-ish, looks natural). */
  public static final double NPC_AIM_HEIGHT_FRACTION = 0.72;

  /**
   * The point on {@code entity} the macros aim the crosshair at.
   *
   * @param entity the entity to aim at
   * @return the aim point on the entity
   */
  public static Vec3 entityLookPoint(Entity entity) {
    return new Vec3(
        entity.getX(),
        entity.getY() + entity.getBbHeight() * NPC_AIM_HEIGHT_FRACTION,
        entity.getZ());
  }

  /**
   * Yaw/pitch (degrees) needed to look from {@code eye} toward {@code target}, as {@code [yaw,
   * pitch]}.
   *
   * @param eye the eye position
   * @param target the point to look at
   * @return a two-element array of yaw then pitch, in degrees
   */
  public static float[] desiredYawPitch(Vec3 eye, Vec3 target) {
    final float[] out = new float[2];
    desiredYawPitch(eye, target, out);
    return out;
  }

  public static void desiredYawPitch(Vec3 eye, Vec3 target, float[] out) {
    final double dx = target.x - eye.x;
    final double dy = target.y - eye.y;
    final double dz = target.z - eye.z;
    final double horizontal = Math.sqrt(dx * dx + dz * dz);
    out[0] = (float) Math.toDegrees(Math.atan2(-dx, dz));
    out[1] = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
  }

  public static boolean isWithinRange(Vec3 from, Vec3 target, double range) {
    return from.distanceToSqr(target) <= range * range;
  }

  public static float clampPitch(double pitch) {
    return (float) Math.clamp(pitch, -90.0, 90.0);
  }

  static float snap(float delta, float quantum) {
    if (quantum <= 0f) {
      return delta;
    }
    // Keep the nearest reachable angle, including float noise at a half-step boundary.
    // Carrying this absolute target's residual forward makes a fixed aim oscillate.
    if (Math.abs(delta) <= quantum * 0.5f + 0.00001f) {
      return 0f;
    }
    return MouseGcd.quantize(delta, quantum);
  }

  public static boolean isLookWithin(
      LocalPlayer player, Vec3 target, float tolerance, float[] scratch) {
    desiredYawPitch(player.getEyePosition(), target, scratch);
    return isLookWithin(player, scratch[0], scratch[1], tolerance);
  }

  public static boolean isLookWithin(
      LocalPlayer player, float desiredYaw, float desiredPitch, float tolerance) {
    return Math.abs(Mth.wrapDegrees(desiredYaw - player.getYRot())) <= tolerance
        && Math.abs(desiredPitch - player.getXRot()) <= tolerance;
  }

  public static boolean isPitchWithin(LocalPlayer player, float desiredPitch, float tolerance) {
    return Math.abs(desiredPitch - player.getXRot()) <= tolerance;
  }

  /**
   * True when {@code target}'s aim point is within the player's interaction range (+1 block of
   * slack).
   *
   * @param player the local player
   * @param target the entity to reach
   * @return true when the target's aim point is within range
   */
  public static boolean isWithinInteractRange(LocalPlayer player, Entity target) {
    final Vec3 eye = player.getEyePosition();
    final double range = player.entityInteractionRange() + 1.0;
    final double dx = target.getX() - eye.x;
    final double dy = target.getY() + target.getBbHeight() * NPC_AIM_HEIGHT_FRACTION - eye.y;
    final double dz = target.getZ() - eye.z;
    return dx * dx + dy * dy + dz * dz <= range * range;
  }
}
