package ryzn.macro.client.feature.pathfinder.path;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import ryzn.swift.nativepath.NativeVoxelFlags;

/** Chooses the planner's feet cell from vanilla's grounded support reading. */
public final class PathSearchStart {

  private PathSearchStart() {}

  public static BlockPos groundedFeetCell(
      BlockPos roundedFeetCell, Optional<BlockPos> mainSupport, boolean onGround) {
    if (roundedFeetCell == null) {
      throw new IllegalArgumentException("Rounded feet cell cannot be null");
    }
    if (!onGround || mainSupport == null || mainSupport.isEmpty()) {
      return roundedFeetCell;
    }

    BlockPos support = mainSupport.get();
    boolean bodySharesSupportCell =
        support.getX() == roundedFeetCell.getX()
            && support.getY() == roundedFeetCell.getY()
            && support.getZ() == roundedFeetCell.getZ();
    return bodySharesSupportCell ? roundedFeetCell.above() : roundedFeetCell;
  }

  /**
   * A typed or clicked goal is usually the block to stand ON, not the cell to stand IN - HUD coords
   * read the farmland/slab/path cell under the feet, one below the cell the walker occupies. The
   * planner only ever expands walk-passable cells, so such a goal can never be reached; raise it
   * one when the space above is open. A carpet is passable but is itself the floor (the planner
   * rejects a feet cell holding one), so it is raised the same way. Null codes mean "not cached" -
   * leave the goal alone.
   */
  public static BlockPos standableGoalCell(BlockPos goal, Short goalCode, Short aboveCode) {
    if (goal == null) {
      throw new IllegalArgumentException("Goal cannot be null");
    }
    final boolean floorCell = !isWalkPassable(goalCode) || isCarpet(goalCode);
    return floorCell && isWalkPassable(aboveCode) ? goal.above() : goal;
  }

  private static boolean isCarpet(Short code) {
    return code != null && (code & NativeVoxelFlags.CARPET_LIKE) != 0;
  }

  private static boolean isWalkPassable(Short code) {
    return code != null && NativeVoxelFlags.isPassable(code);
  }
}
