package ryzn.macro.client.feature.macros.impl.bea;

import static ryzn.macro.client.util.entity.MacroEntities.entityNameLower;
import static ryzn.macro.client.util.entity.MacroEntities.findNearestNamedEntity;
import static ryzn.macro.client.util.entity.MacroEntities.isNearBlockCenter;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/** Owns Bea's fixed route position and the currently resolved NPC entity. */
final class BeaWorldTarget {
  private static final BlockPos POSITION = new BlockPos(13, 72, -59);
  private static final double ARRIVAL_DISTANCE_SQUARED = 2.75 * 2.75;
  private static final double SEARCH_RADIUS = 8.0;

  private Entity entity;

  BlockPos position() {
    return POSITION;
  }

  boolean isNear(LocalPlayer player) {
    double dx = (POSITION.getX() + 0.5) - player.getX();
    double dz = (POSITION.getZ() + 0.5) - player.getZ();
    return dx * dx + dz * dz <= ARRIVAL_DISTANCE_SQUARED
        && Math.abs(player.getY() - POSITION.getY()) <= 3.0;
  }

  Entity resolve(ClientLevel level) {
    if (entity == null
        || !entity.isAlive()
        || !isNearBlockCenter(
            entity, POSITION.getX(), POSITION.getY(), POSITION.getZ(), SEARCH_RADIUS)
        || !entityNameLower(entity).contains("bea")) {
      entity =
          findNearestNamedEntity(
              level, "bea", POSITION.getX(), POSITION.getY(), POSITION.getZ(), SEARCH_RADIUS);
    }
    return entity;
  }

  void clear() {
    entity = null;
  }
}
