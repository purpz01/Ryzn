package ryzn.macro.client.util.world;

import static ryzn.macro.client.feature.rotation.impl.MacroAim.entityLookPoint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.MacroInput;

/**
 * The shared interaction norm for every macro that clicks an NPC or a block: stand close (at most
 * {@link #MAX_INTERACT_DISTANCE} blocks), keep a clear line of sight (no block and no player
 * crossing the eye-to-target segment), tolerate nobody stacked on top of the bot, and when a player
 * keeps blocking for {@link #BLOCKED_RELOCATE_TICKS}, sidestep to a nearby standable tile that can
 * actually see the target instead of waiting them out.
 */
public final class MacroObstruction {

  /**
   * Norm: interact from at most this many blocks away - close range is far harder to body-block.
   */
  public static final double MAX_INTERACT_DISTANCE = 3.0;

  /** Norm: how long a player may block the crosshair before the macro relocates (~1s). */
  public static final int BLOCKED_RELOCATE_TICKS = 20;

  /**
   * How close the sidestep insists on standing. The norm is 3.0, but the click gate is {@code
   * entityInteractionRange - 0.4} = 2.6, so a tile picked at the full norm can be one the click can
   * never reach: the macro walked there, found itself still out of reach, picked the same tile
   * again and looped ("Route loaded: 2 turns, 2 steps" / "Arrived", forever, at Molbert - log
   * 2026-09-02 11:17:35, broken only by nudging the player by hand). Only offer tiles the click
   * will accept.
   */
  private static final double SIDESTEP_REACH = MAX_INTERACT_DISTANCE - 0.4;

  private static final int SIDESTEP_RADIUS = 3;
  private static final double EYE_HEIGHT = 1.62;
  private static final double PLAYER_HITBOX_SLACK = 0.15;
  private static final double LOS_HIT_NEAR_AIM = 2.0;

  private MacroObstruction() {}

  /**
   * True when {@code target}'s aim point is within the interaction-norm distance of the player's
   * eye.
   */
  public static boolean isWithinInteract(LocalPlayer player, Entity target) {
    return player.getEyePosition().distanceToSqr(entityLookPoint(target))
        <= MAX_INTERACT_DISTANCE * MAX_INTERACT_DISTANCE;
  }

  /**
   * True when no block sits between the player's eye and {@code target}'s aim point, i.e. a click
   * aimed at it would land on it and not on a wall or counter in between.
   */
  public static boolean hasClearView(ClientLevel level, LocalPlayer player, Entity target) {
    return level != null
        && hasBlockLineOfSight(level, player, player.getEyePosition(), entityLookPoint(target));
  }

  /**
   * True when another REAL player's hitbox crosses the {@code from -> to} segment ({@code ignore}
   * excluded). NPC bodies never count - the intended NPC's own (scrambled-name) body always sits in
   * the ray to its hologram, so counting fake players would poison every line-of-sight test.
   */
  public static boolean isPlayerBlockingSegment(
      ClientLevel level, LocalPlayer self, Vec3 from, Vec3 to, Entity ignore) {
    if (level == null || from == null || to == null) {
      return false;
    }
    final Minecraft mc = Minecraft.getInstance();
    for (Player other : level.players()) {
      if (other == self || other == ignore || !other.isAlive()) {
        continue;
      }
      if (!MacroInput.isRealPlayer(mc, other)) {
        continue;
      }
      if (other.getBoundingBox().inflate(PLAYER_HITBOX_SLACK).clip(from, to).isPresent()) {
        return true;
      }
    }
    return false;
  }

  /** True when another REAL player overlaps the bot's own hitbox (standing on or inside it). */
  public static boolean isPlayerStackedOn(ClientLevel level, LocalPlayer self) {
    return isStackedOn(level, self, true);
  }

  /**
   * True when ANY other body overlaps the bot's own hitbox - a real player or an NPC alike.
   *
   * <p>Deliberately wider than {@link #isPlayerStackedOn}. That one counts real players only
   * because it feeds line-of-sight tests, where the intended NPC's own body always sits in the ray
   * and would poison every answer. Standing ON someone has no such problem and no such exemption:
   * an NPC is just as solid to stand on, and the block underfoot is just as unreachable either way.
   */
  public static boolean isAnyoneStackedOn(ClientLevel level, LocalPlayer self) {
    return isStackedOn(level, self, false);
  }

  private static boolean isStackedOn(ClientLevel level, LocalPlayer self, boolean realPlayersOnly) {
    if (level == null || self == null) {
      return false;
    }
    final Minecraft mc = Minecraft.getInstance();
    for (Player other : level.players()) {
      if (other == self || !other.isAlive()) {
        continue;
      }
      if (realPlayersOnly && !MacroInput.isRealPlayer(mc, other)) {
        continue;
      }
      if (other.getBoundingBox().inflate(PLAYER_HITBOX_SLACK).intersects(self.getBoundingBox())) {
        return true;
      }
    }
    return false;
  }

  /**
   * A standable tile near {@code anchor} (the target's feet) whose eye position is within the
   * interact norm of {@code aim} and has a block- AND player-clear line to it, with no player on
   * the tile; the tile nearest the bot wins. Null when nothing currently qualifies - the caller
   * just keeps waiting.
   */
  public static BlockPos findSidestep(
      ClientLevel level, LocalPlayer self, BlockPos anchor, Vec3 aim, Entity target) {
    if (level == null || self == null || anchor == null || aim == null) {
      return null;
    }
    BlockPos best = null;
    double bestDistance = Double.MAX_VALUE;
    for (int dx = -SIDESTEP_RADIUS; dx <= SIDESTEP_RADIUS; dx++) {
      for (int dz = -SIDESTEP_RADIUS; dz <= SIDESTEP_RADIUS; dz++) {
        for (int dy = -1; dy <= 1; dy++) {
          final BlockPos candidate = anchor.offset(dx, dy, dz);
          if (candidate.equals(self.blockPosition())) {
            continue;
          } // here is where we're blocked
          if (!isStandable(level, candidate)) {
            continue;
          }
          if (isPlayerOnTile(level, self, candidate)) {
            continue;
          }
          final Vec3 eye =
              new Vec3(
                  candidate.getX() + 0.5, candidate.getY() + EYE_HEIGHT, candidate.getZ() + 0.5);
          if (eye.distanceToSqr(aim) > SIDESTEP_REACH * SIDESTEP_REACH) {
            continue;
          }
          if (!hasBlockLineOfSight(level, self, eye, aim)) {
            continue;
          }
          if (isPlayerBlockingSegment(level, self, eye, aim, target)) {
            continue;
          }
          final double distance =
              self.distanceToSqr(candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
          if (distance < bestDistance) {
            best = candidate.immutable();
            bestDistance = distance;
          }
        }
      }
    }
    return best;
  }

  /**
   * True when the world's blocks leave the {@code eye -> aim} ray clear (or it ends right at the
   * aim).
   */
  private static boolean hasBlockLineOfSight(
      ClientLevel level, LocalPlayer self, Vec3 eye, Vec3 aim) {
    final BlockHitResult hit =
        level.clip(
            new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, self));
    if (hit.getType() == HitResult.Type.MISS) {
      return true;
    }
    return hit.getLocation().distanceToSqr(aim) <= LOS_HIT_NEAR_AIM * LOS_HIT_NEAR_AIM;
  }

  /** True if any OTHER player occupies {@code pos} (same column, within 1 block of Y). */
  private static boolean isPlayerOnTile(ClientLevel level, LocalPlayer self, BlockPos pos) {
    for (Player other : level.players()) {
      if (other == self || !other.isAlive()) {
        continue;
      }
      if (other.getBlockX() == pos.getX()
          && other.getBlockZ() == pos.getZ()
          && Math.abs(other.getBlockY() - pos.getY()) <= 1) {
        return true;
      }
    }
    return false;
  }

  private static boolean isStandable(ClientLevel level, BlockPos pos) {
    return !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()
        && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
        && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
  }
}
