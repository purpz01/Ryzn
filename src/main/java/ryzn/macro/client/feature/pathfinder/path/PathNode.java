package ryzn.macro.client.feature.pathfinder.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Planner cell plus the precise point and movement medium the executor should follow. {@code
 * gapFiller} marks a point inserted along a straight run for steering, which the overlay does not
 * draw as a node box.
 */
public record PathNode(
    BlockPos cell, Vec3 center, boolean jumpUp, Medium medium, boolean gapFiller) {

  public enum Medium {
    LAND,
    WADE,
    SWIM_SURFACE
  }

  public PathNode(BlockPos cell, Vec3 center) {
    this(cell, center, false, Medium.LAND);
  }

  public PathNode(BlockPos cell, Vec3 center, boolean jumpUp, Medium medium) {
    this(cell, center, jumpUp, medium, false);
  }

  public PathNode {
    if (cell == null || center == null || medium == null) {
      throw new IllegalArgumentException("Path node fields cannot be null");
    }
    center = new Vec3(center.x, movementY(cell, medium), center.z);
  }

  public static PathNode centered(BlockPos cell) {
    return precise(cell, cell.getX() + 0.5, cell.getZ() + 0.5);
  }

  public static PathNode precise(BlockPos cell, double centerX, double centerZ) {
    return precise(cell, centerX, centerZ, false);
  }

  public static PathNode precise(BlockPos cell, double centerX, double centerZ, boolean jumpUp) {
    return precise(cell, centerX, centerZ, jumpUp, Medium.LAND);
  }

  public static PathNode precise(
      BlockPos cell, double centerX, double centerZ, boolean jumpUp, Medium medium) {
    return precise(cell, centerX, centerZ, jumpUp, medium, false);
  }

  public static PathNode precise(
      BlockPos cell,
      double centerX,
      double centerZ,
      boolean jumpUp,
      Medium medium,
      boolean gapFiller) {
    return new PathNode(
        cell, new Vec3(centerX, movementY(cell, medium), centerZ), jumpUp, medium, gapFiller);
  }

  public boolean isWater() {
    return medium != Medium.LAND;
  }

  private static double movementY(BlockPos cell, Medium medium) {
    // Native walk nodes are serialized as the support block. A surface-swim node has no support, so
    // its
    // serialized cell is one below the actual water cell occupied by the feet.
    return medium == Medium.SWIM_SURFACE ? cell.getY() + 1.0 : cell.getY();
  }
}
