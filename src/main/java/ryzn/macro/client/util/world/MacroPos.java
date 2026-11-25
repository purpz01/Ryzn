package ryzn.macro.client.util.world;

import net.minecraft.core.BlockPos;

/**
 * Pure position arithmetic shared by the Skyblock macros. No world / level access (see {@link
 * MacroWorld} for block-state queries). Behaviour is byte-for-byte identical to the per-macro
 * copies it replaces.
 */
public final class MacroPos {

  private MacroPos() {}

  /** Squared distance from {@code (x, y, z)} to the centre of {@code block}. */
  public static double distanceToBlockCenterSqr(double x, double y, double z, BlockPos block) {
    final double dx = block.getX() + 0.5 - x;
    final double dy = block.getY() + 0.5 - y;
    final double dz = block.getZ() + 0.5 - z;
    return dx * dx + dy * dy + dz * dz;
  }
}
