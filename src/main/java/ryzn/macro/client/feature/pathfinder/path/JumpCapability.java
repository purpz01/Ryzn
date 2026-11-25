package ryzn.macro.client.feature.pathfinder.path;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Current dry-ground jump reach, using vanilla's launch impulse and the player's gravity. */
public final class JumpCapability {
  private JumpCapability() {}

  public static double height() {
    Minecraft client = Minecraft.getInstance();
    if (client == null || client.player == null || client.level == null) {
      return 1.3;
    }
    double velocity = velocity();
    double gravity = client.player.getAttributeValue(Attributes.GRAVITY);
    if (!Double.isFinite(velocity) || !Double.isFinite(gravity) || gravity <= 0.0) {
      return 1.3;
    }
    double height = 0.0;
    for (int tick = 0; tick < 200 && velocity > 0.0; tick++) {
      height += velocity;
      velocity = (velocity - gravity) * 0.98;
    }
    return Math.clamp(height - 0.05, 0.0, 16.0);
  }

  /** Vertical launch speed of a jump from where the player stands. */
  static double velocity() {
    Minecraft client = Minecraft.getInstance();
    var player = client.player;
    float factor = client.level.getBlockState(player.blockPosition()).getBlock().getJumpFactor();
    if (factor == 1.0f) {
      factor =
          client
              .level
              .getBlockState(player.getBlockPosBelowThatAffectsMyMovement())
              .getBlock()
              .getJumpFactor();
    }
    return (float) player.getAttributeValue(Attributes.JUMP_STRENGTH) * factor
        + player.getJumpBoostPower();
  }
}
