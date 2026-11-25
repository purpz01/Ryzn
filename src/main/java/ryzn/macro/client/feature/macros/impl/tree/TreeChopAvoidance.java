package ryzn.macro.client.feature.macros.impl.tree;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import ryzn.macro.client.world.WorldChangeDetector;

/** Protected-region policy for tree targets, clusters, stands, and travel segments. */
final class TreeChopAvoidance {

  private static final AvoidBox DARK_OAK = new AvoidBox(-369, -355, -103, -86);
  private static final AvoidBox SPRUCE = new AvoidBox(-346, -324, 56, 85);

  /**
   * Eastern cutoff, applied only while standing in the Park.
   *
   * <p>The Park holds log blocks that are portal frames rather than trees, and breaking one warps
   * to the hub and ends the run. The cutoff cannot be global: the Park and the Hub are separate
   * islands that reuse the same coordinate space, so the very x range being refused here is where
   * the Hub's oak forest stands. Gating on the area name is what keeps one island's no-go zone from
   * deleting the other's trees.
   *
   * <p>It sits on {@code isAvoidTarget} because that is the single gate the index sweep, log
   * targeting and the stand finder all pass through, so one check covers what gets indexed, what
   * gets aimed at, and where the bot is willing to stand.
   */
  private static final int PARK_CUTOFF_X = -266;

  private static final String PARK_AREA = "park";

  boolean isAvoidTarget(TreeLogType type, BlockPos position) {
    if (position != null && position.getX() > PARK_CUTOFF_X && isInPark()) {
      return true;
    }
    AvoidBox box = boxFor(type);
    return box != null && box.contains(position);
  }

  private static boolean isInPark() {
    final String area = WorldChangeDetector.currentArea();
    return area != null && area.toLowerCase(Locale.ROOT).contains(PARK_AREA);
  }

  boolean blocksRoute(TreeLogType type, double fromX, double fromZ, BlockPos target) {
    return target != null
        && segmentCrosses(type, fromX, fromZ, target.getX() + 0.5, target.getZ() + 0.5);
  }

  boolean segmentCrosses(TreeLogType type, double fromX, double fromZ, double toX, double toZ) {
    AvoidBox box = boxFor(type);
    return box != null && box.intersectsSegment(fromX, fromZ, toX, toZ);
  }

  private static AvoidBox boxFor(TreeLogType type) {
    if (type == TreeLogType.DARK_OAK) {
      return DARK_OAK;
    }
    if (type == TreeLogType.SPRUCE) {
      return SPRUCE;
    }
    return null;
  }

  private record AvoidBox(int minX, int maxX, int minZ, int maxZ) {

    boolean contains(BlockPos position) {
      return position != null
          && position.getX() >= minX
          && position.getX() <= maxX
          && position.getZ() >= minZ
          && position.getZ() <= maxZ;
    }

    boolean intersectsSegment(double fromX, double fromZ, double toX, double toZ) {
      double maxExclusiveX = maxX + 1.0;
      double maxExclusiveZ = maxZ + 1.0;
      // The box protects a hazard in its middle (the Trial-of-Fire campfire sits dead-centre of the
      // dark
      // oak box), so a route may not path INTO or THROUGH it. But if the START is already inside -
      // the bot
      // steps off the trial campfire into the box, then has to chop the dark oak around it -
      // refusing
      // every route trapped it: all stands and navs were rejected and it chopped nothing. Allow a
      // route
      // that ORIGINATES inside so the bot can escape outward; logs/stands/targets inside are still
      // rejected by the contains() checks elsewhere, so the hazard stays protected.
      if (containsPoint(fromX, fromZ, maxExclusiveX, maxExclusiveZ)) {
        return false;
      }
      if (containsPoint(toX, toZ, maxExclusiveX, maxExclusiveZ)) {
        return true;
      }

      double lower = 0.0;
      double upper = 1.0;
      double deltaX = toX - fromX;
      double deltaZ = toZ - fromZ;
      double[] directions = {-deltaX, deltaX, -deltaZ, deltaZ};
      double[] distances = {
        fromX - minX, maxExclusiveX - fromX,
        fromZ - minZ, maxExclusiveZ - fromZ
      };
      for (int i = 0; i < directions.length; i++) {
        double direction = directions[i];
        if (Math.abs(direction) < 1.0E-7) {
          if (distances[i] < 0.0) {
            return false;
          }
          continue;
        }
        double ratio = distances[i] / direction;
        if (direction < 0.0) {
          if (ratio > upper) {
            return false;
          }
          lower = Math.max(lower, ratio);
        } else {
          if (ratio < lower) {
            return false;
          }
          upper = Math.min(upper, ratio);
        }
      }
      return true;
    }

    private boolean containsPoint(double x, double z, double maxExclusiveX, double maxExclusiveZ) {
      return x >= minX && x < maxExclusiveX && z >= minZ && z < maxExclusiveZ;
    }
  }
}
