package ryzn.macro.client.util.world;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * Narrow client-level block-state queries shared by the macros that scan for stand positions. Uses
 * the same collision-shape test the per-macro copies used, so results are byte-for-byte identical
 * to the code this replaces.
 */
public final class MacroWorld {

  private MacroWorld() {}

  /** A non-empty collision shape occupies {@code pos}. */
  public static boolean isSolid(ClientLevel level, BlockPos pos) {
    return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
  }

  /** Nothing collidable occupies {@code pos}. */
  public static boolean isPassable(ClientLevel level, BlockPos pos) {
    return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
  }

  /** Solid floor below {@code pos} with two passable blocks of headroom (feet + head). */
  public static boolean isStandable(ClientLevel level, BlockPos pos) {
    return isSolid(level, pos.below()) && isPassable(level, pos) && isPassable(level, pos.above());
  }
}
