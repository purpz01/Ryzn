package ryzn.macro.client.feature.macros.impl.wheat;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * Counts a zone's mature wheat clusters from wherever the player is, as soon as the zone's chunks
 * are loaded, so a zone other players already broke is skipped before walking there. Uses the same
 * field flood and cluster rules as the harvest, so "broken" means exactly what ends a harvest.
 */
final class WheatZoneProbe {
  static final int UNKNOWN = -1;

  private static final int PROBE_INTERVAL_TICKS = 10;
  private static final int CHUNK_MARGIN = 10;
  private static final double ZONE_RADIUS_SQ = 5.0 * 5.0;

  private final WheatFieldRoute fieldRoute = new WheatFieldRoute();
  private final WheatClusterTracker tracker = new WheatClusterTracker(fieldRoute);
  private int cooldown;

  void reset() {
    cooldown = 0;
  }

  /** Live cluster count for the zone, or {@link #UNKNOWN} when throttled or not loaded yet. */
  int poll(ClientLevel level, BlockPos center, int scanRadius) {
    if (cooldown > 0) {
      cooldown--;
      return UNKNOWN;
    }
    cooldown = PROBE_INTERVAL_TICKS;
    return count(level, center, scanRadius);
  }

  int count(ClientLevel level, BlockPos center, int scanRadius) {
    if (level == null || !isLoaded(level, center)) {
      return UNKNOWN;
    }
    fieldRoute.rebuild(level, center, scanRadius, 2);
    tracker.reset();
    tracker.captureInitial(level, center, ZONE_RADIUS_SQ);
    final int live = tracker.liveCount();
    tracker.reset();
    fieldRoute.clear();
    return live;
  }

  private static boolean isLoaded(ClientLevel level, BlockPos center) {
    return level.hasChunkAt(center.offset(-CHUNK_MARGIN, 0, -CHUNK_MARGIN))
        && level.hasChunkAt(center.offset(CHUNK_MARGIN, 0, -CHUNK_MARGIN))
        && level.hasChunkAt(center.offset(-CHUNK_MARGIN, 0, CHUNK_MARGIN))
        && level.hasChunkAt(center.offset(CHUNK_MARGIN, 0, CHUNK_MARGIN));
  }
}
