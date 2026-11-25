package ryzn.macro.client.feature.rotation.impl;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Angle math for Mojang-mapped vanilla types ({@code Vec3} instead of the {@code .xCoord} shim,
 * vanilla {@code BlockPos}).
 */
public final class AngleUtil {

  /** Degrees to radians, matching the exact literal vanilla {@code Mth} uses. */
  private static final float DEG_TO_RAD = 0.017453292F;

  /** Half-turn phase offset (PI) applied when deriving look vectors. */
  private static final float PI = 3.1415927F;

  /** Maximum vertical aim jitter, in blocks, added to entity aim points. */
  private static final double AIM_JITTER = 0.15;

  /** Fraction of an entity's height to aim at, before the absolute cap. */
  private static final double EYE_HEIGHT_FRACTION = 0.85;

  /** Absolute cap on how high above an entity's feet we aim, in blocks. */
  private static final double MAX_AIM_HEIGHT = 1.7;

  private static final Minecraft mc = Minecraft.getInstance();

  /**
   * @description Prevents construction of this stateless angle utility.
   */
  private AngleUtil() {}

  /**
   * @description Reads the player's current yaw and pitch or returns zero before a player exists.
   * @return the current player angle
   */
  public static Angle getPlayerAngle() {
    if (mc.player == null) {
      return new Angle(0, 0);
    }
    return new Angle(get360RotationYaw(), mc.player.getXRot());
  }

  /**
   * @description Wraps a yaw into the zero-to-360-degree range.
   * @param yaw the yaw to wrap in degrees
   * @return the wrapped yaw
   */
  public static float get360RotationYaw(float yaw) {
    return (yaw % 360 + 360) % 360;
  }

  /**
   * @description Reads the player's yaw in the zero-to-360-degree range.
   * @return the wrapped player yaw, or zero before a player exists
   */
  public static float get360RotationYaw() {
    if (mc.player == null) {
      return 0;
    }
    return get360RotationYaw(mc.player.getYRot());
  }

  /**
   * @description Wraps a yaw into the signed half-turn range.
   * @param yaw the yaw to wrap in degrees
   * @return the wrapped yaw between negative and positive 180 degrees
   */
  public static float normalizeAngle(float yaw) {
    float newYaw = yaw % 360F;
    if (newYaw < -180F) {
      newYaw += 360F;
    }
    if (newYaw > 180F) {
      newYaw -= 360F;
    }
    return newYaw;
  }

  /**
   * @description Converts pitch and yaw into a three-dimensional look vector.
   * @param pitch the vertical angle in degrees
   * @param yaw the horizontal angle in degrees
   * @return the look direction vector
   */
  public static Vec3 getVectorForRotation(float pitch, float yaw) {
    float yawCos = Mth.cos(-yaw * DEG_TO_RAD - PI);
    float yawSin = Mth.sin(-yaw * DEG_TO_RAD - PI);
    float pitchCos = -Mth.cos(-pitch * DEG_TO_RAD);
    float pitchSin = Mth.sin(-pitch * DEG_TO_RAD);
    return new Vec3(yawSin * pitchCos, pitchSin, yawCos * pitchCos);
  }

  /**
   * @description Converts yaw into a horizontal look vector.
   * @param yaw the horizontal angle in degrees
   * @return the horizontal direction vector
   */
  public static Vec3 getVectorForRotation(float yaw) {
    return new Vec3(-Mth.sin(-yaw * DEG_TO_RAD - PI), 0, -Mth.cos(-yaw * DEG_TO_RAD - PI));
  }

  /**
   * @description Calculates the angle from the player's eyes toward a world point.
   * @param to the target point
   * @return the target angle, or zero before a player exists
   */
  public static Angle getRotation(Vec3 to) {
    if (mc.player == null) {
      return new Angle(0, 0);
    }
    return getRotation(mc.player.getEyePosition(), to);
  }

  /**
   * @description Aims near an entity's chest with fresh bounded vertical jitter on each call.
   * @param to the entity to face
   * @return the target angle, or zero before a player exists
   */
  public static Angle getRotation(Entity to) {
    if (mc.player == null) {
      return new Angle(0, 0);
    }
    final double jitter = ThreadLocalRandom.current().nextDouble(-AIM_JITTER, AIM_JITTER);
    return getRotation(
        mc.player.getEyePosition(),
        new Vec3(to.getX(), to.getY(), to.getZ())
            .add(
                0, Math.min((to.getBbHeight() * EYE_HEIGHT_FRACTION) + jitter, MAX_AIM_HEIGHT), 0));
  }

  /**
   * @description Calculates the angle from the player's eyes to a block center.
   * @param pos the block position to face
   * @return the target angle, or zero before a player exists
   */
  public static Angle getRotation(BlockPos pos) {
    if (mc.player == null) {
      return new Angle(0, 0);
    }
    return getRotation(
        mc.player.getEyePosition(), new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
  }

  /**
   * @description Calculates an angle from an arbitrary point to a block center.
   * @param from the starting world point
   * @param pos the target block position
   * @return the target yaw and pitch
   */
  public static Angle getRotation(Vec3 from, BlockPos pos) {
    return getRotation(from, new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
  }

  /**
   * @description Calculates yaw and pitch between two world points.
   * @param from the starting world point
   * @param to the target world point
   * @return the target yaw and pitch
   */
  public static Angle getRotation(Vec3 from, Vec3 to) {
    double diffX = to.x - from.x;
    double diffY = to.y - from.y;
    double diffZ = to.z - from.z;

    double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);

    float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90F;
    float pitch = (float) -Math.toDegrees(Math.atan2(diffY, dist));

    return new Angle(yaw, pitch);
  }

  /**
   * @description Calculates the yaw from the player's position toward a world point.
   * @param to the target world point
   * @return the signed yaw, or zero before a player exists
   */
  public static float getRotationYaw(Vec3 to) {
    if (mc.player == null) {
      return 0;
    }
    return (float) -Math.toDegrees(Math.atan2(to.x - mc.player.getX(), to.z - mc.player.getZ()));
  }

  /**
   * @description Calculates signed yaw between two horizontal world positions.
   * @param from the starting world point
   * @param to the target world point
   * @return the signed yaw in degrees
   */
  public static float getRotationYaw(Vec3 from, Vec3 to) {
    return (float) -Math.toDegrees(Math.atan2(to.x - from.x, to.z - from.z));
  }

  /**
   * @description Calculates positive wrapped yaw from the player toward a world point.
   * @param to the target world point
   * @return yaw in the zero-to-360-degree range, or zero before a player exists
   */
  public static float getRotationYaw360(Vec3 to) {
    if (mc.player == null) {
      return 0;
    }
    float yaw =
        (float) -Math.toDegrees(Math.atan2(to.x - mc.player.getX(), to.z - mc.player.getZ()));
    if (yaw < 0) {
      return yaw + 360.0f;
    }
    return yaw;
  }

  /**
   * @description Calculates positive wrapped yaw between two world points.
   * @param from the starting world point
   * @param to the target world point
   * @return yaw in the zero-to-360-degree range
   */
  public static float getRotationYaw360(Vec3 from, Vec3 to) {
    float yaw = (float) -Math.toDegrees(Math.atan2(to.x - from.x, to.z - from.z));
    if (yaw < 0) {
      return yaw + 360.0f;
    }
    return yaw;
  }
}
