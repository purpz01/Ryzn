package ryzn.macro.client.feature.macros.impl.goldshovel;

import static ryzn.macro.client.util.entity.MacroEntities.entityNameLower;
import static ryzn.macro.client.util.entity.MacroEntities.findNearestNamedEntity;
import static ryzn.macro.client.util.entity.MacroEntities.isNearBlockCenter;
import static ryzn.macro.client.util.world.MacroPos.distanceToBlockCenterSqr;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;

final class GoldShovelWorldTargets {

  private static final BlockPos LUMBER_POSITION = new BlockPos(-126, 73, -41);
  private static final BlockPos LIBRARIAN_POSITION = new BlockPos(-68, 70, -79);
  private static final double NPC_SEARCH_RADIUS = 8.0;
  private static final int ENCHANT_TABLE_SEARCH_RADIUS = 8;
  private static final double EXACT_ARRIVAL_DIST_SQ = 0.85 * 0.85;
  private static final double NEAR_ARRIVAL_DIST_SQ = 8.0 * 8.0;
  private static final double NEAR_ARRIVAL_HEIGHT = 5.0;

  private Entity lumberEntity;
  private Entity librarianEntity;

  BlockPos lumberPosition() {
    return LUMBER_POSITION;
  }

  BlockPos librarianPosition() {
    return LIBRARIAN_POSITION;
  }

  Entity npc(ClientLevel level, boolean lumber) {
    Entity cached = lumber ? lumberEntity : librarianEntity;
    String name = lumber ? "lumber merchant" : "librarian";
    BlockPos position = lumber ? LUMBER_POSITION : LIBRARIAN_POSITION;
    Entity resolved = resolveNpc(cached, level, name, position);
    if (lumber) {
      lumberEntity = resolved;
    } else {
      librarianEntity = resolved;
    }
    return resolved;
  }

  void clearLumber() {
    lumberEntity = null;
  }

  void clearLibrarian() {
    librarianEntity = null;
  }

  void reset() {
    lumberEntity = null;
    librarianEntity = null;
  }

  BlockPos enchantingTable(ClientLevel level, LocalPlayer player, BlockPos cached) {
    if (cached != null
        && level.getBlockState(cached).is(Blocks.ENCHANTING_TABLE)
        && distanceToBlockCenterSqr(player.getX(), player.getY(), player.getZ(), cached)
            <= (double) ENCHANT_TABLE_SEARCH_RADIUS * ENCHANT_TABLE_SEARCH_RADIUS) {
      return cached;
    }
    return findNearbyEnchantingTable(level, player);
  }

  boolean isAtTarget(LocalPlayer player, BlockPos target) {
    return isAtTarget(
        player.getX(),
        player.getY(),
        player.getZ(),
        player.getBlockX(),
        player.getBlockZ(),
        target);
  }

  boolean isAtTarget(
      double playerX,
      double playerY,
      double playerZ,
      int playerBlockX,
      int playerBlockZ,
      BlockPos target) {
    double dx = target.getX() + 0.5 - playerX;
    double dz = target.getZ() + 0.5 - playerZ;
    return playerBlockX == target.getX()
        && playerBlockZ == target.getZ()
        && Math.abs(playerY - target.getY()) <= 2.0
        && dx * dx + dz * dz <= EXACT_ARRIVAL_DIST_SQ;
  }

  /** Route ended here: near enough that the rotate state can walk the last step in. */
  boolean isNearTarget(LocalPlayer player, BlockPos target) {
    return isNearTarget(player.getX(), player.getY(), player.getZ(), target);
  }

  boolean isNearTarget(double playerX, double playerY, double playerZ, BlockPos target) {
    double dx = target.getX() + 0.5 - playerX;
    double dz = target.getZ() + 0.5 - playerZ;
    return dx * dx + dz * dz <= NEAR_ARRIVAL_DIST_SQ
        && Math.abs(playerY - target.getY()) <= NEAR_ARRIVAL_HEIGHT;
  }

  private Entity resolveNpc(Entity cached, ClientLevel level, String name, BlockPos position) {
    if (cached != null
        && cached.isAlive()
        && isNearBlockCenter(
            cached, position.getX(), position.getY(), position.getZ(), NPC_SEARCH_RADIUS)
        && entityNameLower(cached).contains(name)) {
      return cached;
    }
    return findNearestNamedEntity(
        level, name, position.getX(), position.getY(), position.getZ(), NPC_SEARCH_RADIUS);
  }

  private BlockPos findNearbyEnchantingTable(ClientLevel level, LocalPlayer player) {
    BlockPos base = player.blockPosition();
    BlockPos best = null;
    double bestDistance = Double.MAX_VALUE;
    for (int dy = -3; dy <= 3; dy++) {
      for (int dx = -ENCHANT_TABLE_SEARCH_RADIUS; dx <= ENCHANT_TABLE_SEARCH_RADIUS; dx++) {
        for (int dz = -ENCHANT_TABLE_SEARCH_RADIUS; dz <= ENCHANT_TABLE_SEARCH_RADIUS; dz++) {
          BlockPos position = base.offset(dx, dy, dz);
          if (!level.getBlockState(position).is(Blocks.ENCHANTING_TABLE)) {
            continue;
          }
          double distance =
              distanceToBlockCenterSqr(player.getX(), player.getY(), player.getZ(), position);
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
