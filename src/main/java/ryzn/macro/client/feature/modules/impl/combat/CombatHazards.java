package ryzn.macro.client.feature.modules.impl.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import ryzn.macro.client.util.entity.MacroEntities;

/**
 * Hazard-zone tracker, generalized from V5's Graveyard "blackhole" avoidance. A Graveyard blackhole
 * is a decorative skull worn by an armor stand; this scans nearby armor stands for one of a set of
 * known skull texture hashes and remembers each as a circular avoid-zone with a short memory.
 * {@link #isSafe} lets the targeting and the search reject any position inside a zone, so the bot
 * never locks onto - or paths to - a mob standing in a hazard.
 *
 * <p>It deliberately does NOT steer the native pathfinder around a zone mid-route (Ryzn's
 * pathfinder is left untouched per the project rule); it only gates the route endpoints (target +
 * search destinations), exactly like V5. A computed route may still clip a zone's edge - endpoint
 * gating is the documented limit.
 */
public final class CombatHazards {

  private static final double AVOID_RADIUS =
      8.5; // a position within this XZ distance of a zone is unsafe
  private static final int SCAN_INTERVAL_TICKS =
      10; // armor-stand scans are throttled to every N ticks
  private static final double SCAN_RADIUS = 30.0; // only scan armor stands within this XZ distance
  private static final double SCAN_Y_RANGE = 20.0; // ... and this vertical distance
  private static final long MEMORY_MS = 60_000L; // keep a zone this long after it was last seen
  private static final double FORGET_CHECK_RADIUS =
      20.0; // ... unless still this close (then keep until we walk away)
  private static final double MERGE_RADIUS =
      2.5; // a fresh detection within this distance of a known zone updates it
  private static final double AVOID_RADIUS_SQ = AVOID_RADIUS * AVOID_RADIUS;
  private static final double FORGET_CHECK_RADIUS_SQ = FORGET_CHECK_RADIUS * FORGET_CHECK_RADIUS;
  private static final double MERGE_RADIUS_SQ = MERGE_RADIUS * MERGE_RADIUS;

  /** Texture hashes of the Graveyard blackhole skulls (decoded from V5's profile blobs). */
  private static final Set<String> HAZARD_SKULLS =
      Set.of(
          "55b70f094e01679508dd5bca39640ed9ec5c6c6792fbd8f57c03b3a12f9c0a92",
          "d61b87f1a1040a8b922ca51be9c0bc6d6fc71ba5d745c6bf659cbd0d9a9cf4fc",
          "8d3456e2d90f412c570925b528bb5a3e4e1e6a3f24ef820a6b1ce42ac8e00602",
          "f438c6bc501985cba0796d817971e686793be08be42b5385d7d0b93d88152019",
          "129078137ea0971944c377418671726166b751bd8395e47146019522c7578229",
          "cb832fc97d38ccca8bd118bfbda2fa57c5208e1fbbd6b8ea428f70c7ca1656f4");

  private static final class Zone {
    double posX;
    double posY;
    double posZ;
    long lastSeen;

    Zone(double x, double y, double z, long lastSeen) {
      this.posX = x;
      this.posY = y;
      this.posZ = z;
      this.lastSeen = lastSeen;
    }
  }

  private final List<Zone> zones = new ArrayList<>();
  private int scanTicker = 0;

  public void reset() {
    zones.clear();
    scanTicker = 0;
  }

  /**
   * Throttled per-tick scan: discover / refresh hazard zones from nearby armor-stand skulls, then
   * forget stale ones.
   *
   * @param player the local player the scan is centred on
   * @param level the level whose armor stands are read
   */
  public void scan(LocalPlayer player, ClientLevel level) {
    if (++scanTicker % SCAN_INTERVAL_TICKS != 0) {
      return;
    }
    final long now = System.currentTimeMillis();
    final double px = player.getX();
    final double py = player.getY();
    final double pz = player.getZ();

    for (Entity e : level.entitiesForRendering()) {
      if (!(e instanceof ArmorStand stand)) {
        continue;
      }
      if (Math.abs(stand.getX() - px) > SCAN_RADIUS
          || Math.abs(stand.getZ() - pz) > SCAN_RADIUS
          || Math.abs(stand.getY() - py) > SCAN_Y_RANGE) {
        continue;
      }
      final String skull = MacroEntities.skullTextureId(stand);
      if (skull == null || !HAZARD_SKULLS.contains(skull)) {
        continue;
      }
      record(stand.getX(), stand.getY(), stand.getZ(), now);
    }
    forgetStale(px, pz, now);
  }

  private void record(double x, double y, double z, long now) {
    for (Zone zone : zones) {
      if (distFlatSq(zone.posX, zone.posZ, x, z) <= MERGE_RADIUS_SQ) {
        zone.posX = x;
        zone.posY = y;
        zone.posZ = z;
        zone.lastSeen = now;
        return;
      }
    }
    zones.add(new Zone(x, y, z, now));
  }

  private void forgetStale(double px, double pz, long now) {
    zones.removeIf(
        z ->
            now - z.lastSeen > MEMORY_MS
                && distFlatSq(px, pz, z.posX, z.posZ) > FORGET_CHECK_RADIUS_SQ);
  }

  /**
   * True when no hazard zone is known, or {@code (x,z)} sits outside every zone's avoid radius.
   *
   * @param x the block X to test
   * @param y the block Y, ignored by the avoid test
   * @param z the block Z to test
   * @return true when the position is outside every known hazard zone
   */
  public boolean isSafe(double x, double y, double z) {
    for (Zone zone : zones) {
      if (distFlatSq(zone.posX, zone.posZ, x, z) < AVOID_RADIUS_SQ) {
        return false;
      }
    }
    return true;
  }

  public AABB box(int index, double offsetX, double offsetY, double offsetZ) {
    final Zone zone = zones.get(index);
    return new AABB(
        zone.posX - AVOID_RADIUS + offsetX,
        zone.posY - 1.0 + offsetY,
        zone.posZ - AVOID_RADIUS + offsetZ,
        zone.posX + AVOID_RADIUS + offsetX,
        zone.posY + 3.0 + offsetY,
        zone.posZ + AVOID_RADIUS + offsetZ);
  }

  private static double distFlatSq(double x1, double z1, double x2, double z2) {
    final double dx = x1 - x2;
    final double dz = z1 - z2;
    return dx * dx + dz * dz;
  }
}
