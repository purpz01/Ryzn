package ryzn.macro.client.feature.macros.impl.goldshovel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import ryzn.macro.client.MacroInput;

/**
 * Keeps thrown experience away from other players. Orbs go to whoever is nearest, so someone
 * standing on the bot while it throws walks off with the levels it paid for. This only reads the
 * world: it reports a real player inside the guard radius and picks a standable spot clear of
 * everyone for the macro to walk to before it throws again.
 */
final class GoldShovelCrowdGuard {

  /** A real player this close while bottles are out means the experience is not safely ours. */
  static final double GUARD_RADIUS = 5.0;

  /** A retreat spot must be at least this far from every real player. */
  private static final double CLEAR_DISTANCE = 6.5;

  /** Retreats stay this close to the Librarian so the walk back is short. */
  private static final double LEASH = 26.0;

  private static final int[] RINGS = {7, 9, 11, 13, 6};
  private static final int DIRECTIONS = 16;
  private static final double PLAYER_SCAN_RADIUS = 40.0;

  /** Spots the pathfinder refused this visit; cleared when a new run starts. */
  private final Set<BlockPos> rejected = new HashSet<>();

  void reset() {
    rejected.clear();
  }

  void reject(BlockPos spot) {
    if (spot != null) {
      rejected.add(spot);
    }
  }

  /** The nearest other real player within the guard radius, or null when the area is clear. */
  Player intruder(Minecraft mc, ClientLevel level, LocalPlayer self) {
    Player nearest = null;
    double best = GUARD_RADIUS * GUARD_RADIUS;
    for (Player other : level.players()) {
      if (other == self || !other.isAlive() || !MacroInput.isRealPlayer(mc, other)) {
        continue;
      }
      final double distance = other.distanceToSqr(self);
      if (distance <= best) {
        best = distance;
        nearest = other;
      }
    }
    return nearest;
  }

  /**
   * A standable block near the player that is clear of every real player and still on the leash
   * around {@code anchor}; the farthest-from-others candidate wins, nearer ones breaking ties.
   */
  BlockPos findRetreat(Minecraft mc, ClientLevel level, LocalPlayer self, BlockPos anchor) {
    final List<Player> others = new ArrayList<>();
    for (Player other : level.players()) {
      if (other != self
          && other.isAlive()
          && other.distanceToSqr(self) <= PLAYER_SCAN_RADIUS * PLAYER_SCAN_RADIUS
          && MacroInput.isRealPlayer(mc, other)) {
        others.add(other);
      }
    }
    BlockPos best = null;
    double bestScore = Double.NEGATIVE_INFINITY;
    for (int ring : RINGS) {
      for (int step = 0; step < DIRECTIONS; step++) {
        final double angle = Math.PI * 2.0 * step / DIRECTIONS;
        final BlockPos spot =
            standableNear(
                level,
                (int) Math.floor(self.getX() + Math.cos(angle) * ring),
                self.getBlockY(),
                (int) Math.floor(self.getZ() + Math.sin(angle) * ring));
        if (spot == null || rejected.contains(spot) || horizontal(spot, anchor) > LEASH) {
          continue;
        }
        final double clearance = clearance(spot, others);
        if (clearance < CLEAR_DISTANCE) {
          continue;
        }
        final double score =
            Math.min(clearance, 14.0) - 0.2 * ring - 0.05 * horizontal(spot, anchor);
        if (score > bestScore) {
          bestScore = score;
          best = spot;
        }
      }
    }
    return best;
  }

  private static double clearance(BlockPos spot, List<Player> others) {
    double nearest = Double.MAX_VALUE;
    for (Player other : others) {
      nearest =
          Math.min(
              nearest,
              Math.sqrt(other.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5)));
    }
    return nearest;
  }

  private static double horizontal(BlockPos a, BlockPos b) {
    final double dx = a.getX() - b.getX();
    final double dz = a.getZ() - b.getZ();
    return Math.sqrt(dx * dx + dz * dz);
  }

  /** The feet block of the nearest standable column position within two blocks of {@code y}. */
  private static BlockPos standableNear(ClientLevel level, int x, int y, int z) {
    for (int offset : new int[] {0, -1, 1, -2, 2}) {
      final BlockPos feet = new BlockPos(x, y + offset, z);
      if (isOpen(level, feet) && isOpen(level, feet.above()) && !isOpen(level, feet.below())) {
        return feet;
      }
    }
    return null;
  }

  private static boolean isOpen(ClientLevel level, BlockPos pos) {
    return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
  }
}
