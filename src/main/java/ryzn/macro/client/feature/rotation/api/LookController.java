package ryzn.macro.client.feature.rotation.api;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** One owner's smoothed macro aim, kept by the rotation manager. */
public interface LookController {
  /**
   * @description Sets the point the next ticks ease toward.
   * @param target the world point to aim at
   */
  void target(Vec3 target);

  /**
   * @description Returns the current aim point.
   * @return the point being aimed at, or null before one is set
   */
  Vec3 target();

  /**
   * @description Reports whether a point or look-down aim is active.
   * @return true while the controller is aiming
   */
  boolean hasAim();

  /**
   * @description Aims at an entity's look point.
   * @param player the local player whose head turns
   * @param entity the entity to aim at
   * @return true once the aim has settled on it
   */
  boolean aimAtEntity(LocalPlayer player, Entity entity);

  /**
   * @description Aims at a block's upper centre.
   * @param player the local player whose head turns
   * @param position the block to aim at
   * @return true once the aim has settled on it
   */
  boolean aimAtBlock(LocalPlayer player, BlockPos position);

  /**
   * @description Aims at a world point.
   * @param player the local player whose head turns
   * @param point the point to aim at
   * @return true once the aim has settled on it
   */
  boolean aimAtPoint(LocalPlayer player, Vec3 point);

  /**
   * @description Aims at the owner's look-down pitch.
   * @param player the local player whose head turns
   * @return true once the aim has settled
   */
  boolean aimDown(LocalPlayer player);

  /**
   * @description Switches to the look-down aim without checking whether it has settled.
   */
  void down();

  /**
   * @description Advances the settle check with this tick's aim result.
   * @param aimedNow whether the aim is within tolerance this tick
   * @return true once the aim has held long enough to act on
   */
  boolean settle(boolean aimedNow);

  /**
   * @description Restarts the settle check.
   */
  void resetSettle();

  /**
   * @description Restarts the ease from the head's current angle after something else drove it.
   */
  void reanchor();

  /**
   * @description Drops the aim so nothing turns the head.
   */
  void clear();

  /**
   * @description Eases toward the aim by real elapsed time and applies the turn.
   * @param player the local player whose head turns
   */
  void tick(LocalPlayer player);

  /**
   * @description Eases toward the aim at a scaled speed and applies the turn.
   * @param player the local player whose head turns
   * @param speedMultiplier the factor applied to the standard turn speed
   */
  void tick(LocalPlayer player, float speedMultiplier);

  /**
   * @description Eases and applies the turn from a render frame for legacy callers.
   * @param player the local player whose head turns
   */
  void render(LocalPlayer player);
}
