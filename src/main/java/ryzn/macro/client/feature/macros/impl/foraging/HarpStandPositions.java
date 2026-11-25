package ryzn.macro.client.feature.macros.impl.foraging;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.util.world.MacroObstruction;

/**
 * Chooses where the AFK harp macro stands to open the harp. The priority is always the fixed
 * primary tile (-409,110,70); only if another player is on it - or it is briefly blacklisted after
 * being taken - does the macro auto-rotate to the nearest free, standable tile near it, returning
 * to the primary once it clears. The caller only holds right-click once it is actually standing on
 * the chosen tile (stabilised).
 */
final class HarpStandPositions {

  static final BlockPos PRIMARY = new BlockPos(-409, 110, 70);
  private static final int BLACKLIST_TICKS =
      200; // ~10s a taken tile stays off-limits before we retry it
  private static final int SCAN_RADIUS = 3;
  private static final double EYE_HEIGHT = 1.62;

  private final Map<Long, Integer> blacklistExpiry = new HashMap<>();

  /**
   * The tile to stand on this attempt, or null if nothing usable is nearby. The primary is always
   * preferred (it's the known-good spot); alternates additionally require line of sight to the harp
   * so we never rotate to a tile that can't actually open it. {@code harpSlab} may be null (LOS is
   * then skipped). {@code now} is a monotonic tick ({@code player.tickCount}) used for blacklist
   * expiry.
   */
  BlockPos choose(LocalPlayer self, ClientLevel level, BlockPos harpSlab, int now) {
    if (level == null) {
      return PRIMARY;
    }
    purge(now);
    if (isUsable(PRIMARY, self, level, now)
        && (harpSlab == null || canSeeHarp(level, self, PRIMARY, harpSlab))) {
      return PRIMARY;
    }
    BlockPos best = null;
    double bestDistance = Double.MAX_VALUE;
    for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
      for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
        if (dx == 0 && dz == 0) {
          continue;
        }
        for (int dy = -1; dy <= 1; dy++) {
          BlockPos candidate = PRIMARY.offset(dx, dy, dz);
          if (!isUsable(candidate, self, level, now)) {
            continue;
          }
          if (harpSlab != null && !canSeeHarp(level, self, candidate, harpSlab)) {
            continue;
          }
          double distance = candidate.distSqr(PRIMARY);
          if (distance < bestDistance) {
            bestDistance = distance;
            best = candidate;
          }
        }
      }
    }
    if (best != null) {
      return best;
    }
    // Last resort: the primary, LOS ignored. Sight is a preference, not a requirement - the caller
    // only
    // right-clicks once it is standing on the tile, and by then whoever was crossing the ray has
    // usually
    // moved. Returning null instead made the macro stand still doing NOTHING for the full 45 s path
    // timeout and then fail, which the quest read as a dead child and restarted, forever (log
    // 2026-08-27:
    // "Stopped Auto Harp / Started Auto Harp" seven times, 46 s apart). A busy Melody's Plateau can
    // fail
    // the ray from every tile in range at once, so this is the common case, not the corner.
    return isUsable(PRIMARY, self, level, now) ? PRIMARY : null;
  }

  /**
   * True if the harp block above the slab is visible from {@code stand}'s eye position - not walled
   * off AND no player's hitbox crossing the ray (a tile behind the same blocker is useless to
   * rotate to).
   */
  private static boolean canSeeHarp(
      ClientLevel level, LocalPlayer self, BlockPos stand, BlockPos harpSlab) {
    Vec3 eye = new Vec3(stand.getX() + 0.5, stand.getY() + EYE_HEIGHT, stand.getZ() + 0.5);
    return hasClearLine(level, self, eye, harpSlab);
  }

  private static boolean hasClearLine(
      ClientLevel level, LocalPlayer self, Vec3 eye, BlockPos harpSlab) {
    Vec3 target = Vec3.atCenterOf(harpSlab.above());
    if (MacroObstruction.isPlayerBlockingSegment(level, self, eye, target, null)) {
      return false;
    }
    BlockHitResult hit =
        level.clip(
            new ClipContext(eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, self));
    if (hit.getType() == HitResult.Type.MISS) {
      return true;
    } // nothing blocks the ray to the harp
    BlockPos hitPos = hit.getBlockPos();
    // The ray reached the harp column (the slab, the block above it, or right beside it) rather
    // than a wall.
    return Math.abs(hitPos.getX() - harpSlab.getX()) <= 1
        && Math.abs(hitPos.getZ() - harpSlab.getZ()) <= 1
        && hitPos.getY() >= harpSlab.getY()
        && hitPos.getY() <= harpSlab.getY() + 4;
  }

  /** Keep a taken tile off-limits for ~10s so we don't thrash straight back onto it. */
  void blacklist(BlockPos pos, int now) {
    if (pos != null) {
      blacklistExpiry.put(pos.asLong(), now + BLACKLIST_TICKS);
    }
  }

  /** True if any OTHER player occupies {@code pos} (same column, within 1 block of Y). */
  boolean isPlayerOnBlock(ClientLevel level, LocalPlayer self, BlockPos pos) {
    if (level == null || pos == null) {
      return false;
    }
    for (Player player : level.players()) {
      if (player == self || !player.isAlive()) {
        continue;
      }
      if (player.getBlockX() == pos.getX()
          && player.getBlockZ() == pos.getZ()
          && Math.abs(player.getBlockY() - pos.getY()) <= 1) {
        return true;
      }
    }
    return false;
  }

  /**
   * True if another player is standing on the harp slab, so a right-click would open their menu,
   * not the harp.
   */
  boolean isPlayerOnHarp(ClientLevel level, LocalPlayer self, BlockPos harpSlab) {
    if (level == null || harpSlab == null) {
      return false;
    }
    for (Player player : level.players()) {
      if (player == self || !player.isAlive()) {
        continue;
      }
      if (player.getBlockX() == harpSlab.getX()
          && player.getBlockZ() == harpSlab.getZ()
          && player.getBlockY() >= harpSlab.getY()
          && player.getBlockY() <= harpSlab.getY() + 2) {
        return true;
      }
    }
    return false;
  }

  void clear() {
    blacklistExpiry.clear();
  }

  private boolean isUsable(BlockPos pos, LocalPlayer self, ClientLevel level, int now) {
    return !isBlacklisted(pos, now)
        && isStandable(level, pos)
        && !isPlayerOnBlock(level, self, pos);
  }

  private boolean isBlacklisted(BlockPos pos, int now) {
    Integer expiry = blacklistExpiry.get(pos.asLong());
    return expiry != null && expiry > now;
  }

  private void purge(int now) {
    blacklistExpiry.entrySet().removeIf(entry -> entry.getValue() <= now);
  }

  private static boolean isStandable(ClientLevel level, BlockPos pos) {
    return !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()
        && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
        && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
  }
}
