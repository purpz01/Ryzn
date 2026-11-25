package ryzn.swift.cache;

/**
 * Holds live chunk ingestion after a world-session boundary until the TAB "Area:" line for the NEW
 * world has been read at least once.
 *
 * <p>A warp clears that line and Hypixel re-sends it 0-3 s later (measured over 141 warps in this
 * client's own logs: 78% arrive at or after the old fixed one-second settle). {@link
 * CachedWorld#maybeRekey} keeps the previous area sticky across the gap on purpose - a one-tick TAB
 * blip must not thrash the cache - so every chunk streamed in that window was filed under the OLD
 * area's key. Hub and Private Island are both overworld with overlapping coordinates, so the Hub
 * file ended up holding another island's geometry: solid rock where the Lumber Merchant's floor is,
 * and a foraging quest that re-routed for six minutes because the goal cell read solid. The timer
 * alone cannot close this - only the area name says which world the chunks belong to.
 *
 * <p>The cap exists so a world that never publishes an area (a lobby, another server) still
 * ingests.
 */
final class AreaConfirmGate {

  /**
   * ~5 s: past the worst measured TAB lag with room to spare, short enough that an area-less world
   * waits once.
   */
  private static final int MAX_HOLD_TICKS = 100;

  // Written from the netty thread (boundary) and the client thread (reads/tick), like the settle
  // counter.
  private volatile boolean holding = false;
  private volatile int holdTicks = 0;

  /**
   * A login/respawn landed: the live world is already the new one, its area name is not known yet.
   */
  void onWorldSessionBoundary() {
    holding = true;
    holdTicks = MAX_HOLD_TICKS;
  }

  /**
   * The per-tick TAB read, taken BEFORE any sticky-area substitution - a non-null area is the new
   * world's.
   */
  void onAreaRead(String area) {
    if (area != null) {
      holding = false;
    }
  }

  void tick() {
    if (!holding) {
      return;
    }
    if (--holdTicks <= 0) {
      holding = false;
    }
  }

  /**
   * True while live chunks must not be filed: the key they would land under may still be the old
   * area.
   */
  boolean isHolding() {
    return holding;
  }

  /** The key has just been (re)loaded, so it now matches the live world - ingestion may resume. */
  void clear() {
    holding = false;
    holdTicks = 0;
  }
}
