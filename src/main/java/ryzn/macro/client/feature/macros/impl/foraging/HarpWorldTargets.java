package ryzn.macro.client.feature.macros.impl.foraging;

import static ryzn.macro.client.util.entity.MacroEntities.entityNameLower;
import static ryzn.macro.client.util.entity.MacroEntities.findNearestNamedEntity;
import static ryzn.macro.client.util.entity.MacroEntities.isNearBlockCenter;
import static ryzn.macro.client.util.world.MacroPos.distanceToBlockCenterSqr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Owns the cached Melody and harp block targets used by the AFK harp route. */
final class HarpWorldTargets {
  private static final BlockPos MELODY_POSITION = new BlockPos(-412, 109, 70);
  private static final double NPC_SEARCH_RADIUS = 8.0;
  private static final double EXACT_ARRIVAL_DISTANCE_SQUARED = 1.4 * 1.4;
  private static final int HARP_SLAB_SCAN_RADIUS = 6;

  private Entity melody;
  private BlockPos harpSlab;

  BlockPos melodyPosition() {
    return MELODY_POSITION;
  }

  Entity findMelody(ClientLevel level) {
    return findNearestNamedEntity(
        level,
        "melody",
        MELODY_POSITION.getX(),
        MELODY_POSITION.getY(),
        MELODY_POSITION.getZ(),
        NPC_SEARCH_RADIUS);
  }

  Entity updateMelody(ClientLevel level) {
    if (melody == null
        || !melody.isAlive()
        || !isNearBlockCenter(
            melody,
            MELODY_POSITION.getX(),
            MELODY_POSITION.getY(),
            MELODY_POSITION.getZ(),
            NPC_SEARCH_RADIUS)
        || !entityNameLower(melody).contains("melody")) {
      melody = findMelody(level);
    }
    return melody;
  }

  void rememberMelody(Entity entity) {
    melody = entity;
  }

  void clearMelody() {
    melody = null;
  }

  boolean isAtMelody(LocalPlayer player) {
    double dx = (MELODY_POSITION.getX() + 0.5) - player.getX();
    double dz = (MELODY_POSITION.getZ() + 0.5) - player.getZ();
    return player.getBlockX() == MELODY_POSITION.getX()
        && player.getBlockZ() == MELODY_POSITION.getZ()
        && Math.abs(player.getY() - MELODY_POSITION.getY()) <= 2.0
        && dx * dx + dz * dz <= EXACT_ARRIVAL_DISTANCE_SQUARED;
  }

  BlockPos refreshHarpSlab(LocalPlayer player, ClientLevel level) {
    if (harpSlab == null || !isQuartzSlab(level, harpSlab)) {
      harpSlab = scanForHarpSlab(player, level);
    }
    return harpSlab;
  }

  boolean hasHarpSlab() {
    return harpSlab != null;
  }

  BlockPos harpSlab() {
    return harpSlab;
  }

  void clearHarpSlab() {
    harpSlab = null;
  }

  Vec3 harpAimPoint() {
    return harpSlab == null ? null : Vec3.atCenterOf(harpSlab.above());
  }

  boolean isLookingAtHarp(Minecraft client) {
    if (harpSlab == null || client.hitResult == null) {
      return false;
    }
    HitResult hit = client.hitResult;
    if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
      BlockPos position = blockHit.getBlockPos();
      return position.getY() > harpSlab.getY()
          && position.getY() <= harpSlab.getY() + 4
          && Math.abs(position.getX() - harpSlab.getX()) <= 1
          && Math.abs(position.getZ() - harpSlab.getZ()) <= 1;
    }
    if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit) {
      Entity entity = entityHit.getEntity();
      // The harp renders as an armor stand on its slab, so an entity pick counts only inside that
      // column. A plain radius let Melody - who stands about two blocks away - read as the harp,
      // which pointed the open-harp click at her song menu instead.
      return entity != null
          && Math.abs(entity.getX() - (harpSlab.getX() + 0.5)) <= 1.0
          && Math.abs(entity.getZ() - (harpSlab.getZ() + 0.5)) <= 1.0
          && entity.getY() >= harpSlab.getY() - 1
          && entity.getY() <= harpSlab.getY() + 3;
    }
    return false;
  }

  void reset() {
    melody = null;
    harpSlab = null;
  }

  static boolean isQuartzSlab(ClientLevel level, BlockPos position) {
    if (level == null || position == null) {
      return false;
    }
    var state = level.getBlockState(position);
    if (state.is(Blocks.QUARTZ_SLAB) || state.is(Blocks.SMOOTH_QUARTZ_SLAB)) {
      return true;
    }
    String key = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    return key.contains("quartz") && key.contains("slab");
  }

  private static BlockPos scanForHarpSlab(LocalPlayer player, ClientLevel level) {
    BlockPos base = player.blockPosition();
    Vec3 eye = player.getEyePosition();
    BlockPos best = null;
    double bestDistance = Double.MAX_VALUE;
    for (int dy = -HARP_SLAB_SCAN_RADIUS; dy <= HARP_SLAB_SCAN_RADIUS; dy++) {
      for (int dx = -HARP_SLAB_SCAN_RADIUS; dx <= HARP_SLAB_SCAN_RADIUS; dx++) {
        for (int dz = -HARP_SLAB_SCAN_RADIUS; dz <= HARP_SLAB_SCAN_RADIUS; dz++) {
          BlockPos position = base.offset(dx, dy, dz);
          if (!isQuartzSlab(level, position)) {
            continue;
          }
          double distance = distanceToBlockCenterSqr(eye.x, eye.y, eye.z, position);
          if (distance < bestDistance) {
            best = position.immutable();
            bestDistance = distance;
          }
        }
      }
    }
    return best;
  }
}
