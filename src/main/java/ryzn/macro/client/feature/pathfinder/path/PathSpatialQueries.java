package ryzn.macro.client.feature.pathfinder.path;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.feature.rotation.impl.AngleUtil;

/** Read-only world probes used by path movement and gaze decisions. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class PathSpatialQueries {
  private static final double HIT_SLOP = 0.15;
  private static final double MIN_RAY_RANGE = 0.25;

  private final Minecraft client;

  boolean forwardLaneOpen(Vec3 waypoint) {
    if (client.player == null || client.level == null || waypoint == null) {
      return true;
    }
    double distance =
        Math.hypot(client.player.getX() - waypoint.x, client.player.getZ() - waypoint.z);
    if (distance < MIN_RAY_RANGE) {
      return true;
    }
    double bodyY = client.player.getY() + 1.0;
    double eyeY = client.player.getY() + client.player.getEyeHeight(client.player.getPose());
    return rayReachesAt(bodyY, waypoint) && rayReachesAt(eyeY, waypoint);
  }

  boolean gazeSightOpen(Vec3 node) {
    if (client.player == null || client.level == null || node == null) {
      return true;
    }
    Vec3 eye =
        new Vec3(
            client.player.getX(),
            client.player.getY() + client.player.getEyeHeight(client.player.getPose()),
            client.player.getZ());
    Vec3 target = new Vec3(node.x, node.y + 1.0, node.z);
    if (eye.distanceTo(target) < MIN_RAY_RANGE) {
      return true;
    }
    HitResult hit =
        client.level.clip(
            new ClipContext(
                eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
    return reachesTarget(hit, target);
  }

  boolean blockedAtYawOffset(float yawOffsetDegrees, double distance) {
    if (client.player == null || client.level == null) {
      return false;
    }
    Vec3 direction = AngleUtil.getVectorForRotation(client.player.getYRot() + yawOffsetDegrees);
    return !rayClearAlong(direction, client.player.getY() + 0.35, distance)
        || !rayClearAlong(direction, client.player.getY() + 1.0, distance);
  }

  private boolean rayReachesAt(double probeY, Vec3 waypoint) {
    Vec3 start = new Vec3(client.player.getX(), probeY, client.player.getZ());
    Vec3 target = new Vec3(waypoint.x, probeY, waypoint.z);
    HitResult hit =
        client.level.clip(
            new ClipContext(
                start, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
    return reachesTarget(hit, target);
  }

  private boolean rayClearAlong(Vec3 direction, double probeY, double distance) {
    Vec3 start = new Vec3(client.player.getX(), probeY, client.player.getZ());
    Vec3 target =
        new Vec3(
            client.player.getX() + direction.x * distance,
            probeY,
            client.player.getZ() + direction.z * distance);
    HitResult hit =
        client.level.clip(
            new ClipContext(
                start, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
    return reachesTarget(hit, target);
  }

  static boolean reachesTarget(HitResult hit, Vec3 target) {
    return hit == null
        || hit.getType() == HitResult.Type.MISS
        || hit.getLocation().distanceTo(target) <= HIT_SLOP;
  }
}
