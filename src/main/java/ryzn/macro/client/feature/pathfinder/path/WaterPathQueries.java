package ryzn.macro.client.feature.pathfinder.path;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;

final class WaterPathQueries {
  private static final int DEPTH_SCAN_LIMIT = 8;

  private WaterPathQueries() {}

  static boolean isSurfaceClear(
      BlockGetter level, double x, double feetY, double z, double halfWidth) {
    if (level == null) {
      return false;
    }
    int minX = (int) Math.floor(x - halfWidth);
    int maxX = (int) Math.floor(x + halfWidth);
    int minZ = (int) Math.floor(z - halfWidth);
    int maxZ = (int) Math.floor(z + halfWidth);
    int y = (int) Math.floor(feetY + 1.0e-4);
    BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    for (int blockX = minX; blockX <= maxX; blockX++) {
      for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
        probe.set(blockX, y, blockZ);
        if (!isWater(level, probe) || isWater(level, probe.above())) {
          return false;
        }
        if (!WalkCheck.canWalkThrough(probe.above())) {
          return false;
        }
      }
    }
    return true;
  }

  static int depthAt(BlockGetter level, double x, double y, double z) {
    if (level == null) {
      return 0;
    }
    BlockPos.MutableBlockPos probe =
        new BlockPos.MutableBlockPos(
            (int) Math.floor(x), (int) Math.floor(y + 1.0e-4), (int) Math.floor(z));
    int depth = 0;
    while (depth < DEPTH_SCAN_LIMIT && isWater(level, probe)) {
      depth++;
      probe.move(0, -1, 0);
    }
    return depth;
  }

  private static boolean isWater(BlockGetter level, BlockPos pos) {
    return level.getFluidState(pos).is(FluidTags.WATER);
  }
}
