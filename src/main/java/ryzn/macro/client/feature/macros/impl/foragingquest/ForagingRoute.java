package ryzn.macro.client.feature.macros.impl.foragingquest;

import static ryzn.macro.client.util.entity.MacroEntities.entityNameLower;
import static ryzn.macro.client.util.entity.MacroEntities.findNearestNamedEntity;
import static ryzn.macro.client.util.entity.MacroEntities.isNearBlockCenter;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.util.entity.MacroEntities;

public final class ForagingRoute {

  /**
   * @description Creates a stateless route query collaborator for the Ryan overlay and macros.
   */
  public ForagingRoute() {}

  record Target(int x, int y, int z, String label) {}

  record Npc(String label, String needle, String skinId, int x, int y, int z, double radius) {
    Target target() {
      return new Target(x, y, z, label);
    }
  }

  // skinId (from /skinid) pins the real clickable player-NPC by its skin - Hypixel NPCs carry a
  // scrambled
  // entity name, so name-matching used to lock onto the separate "Lumber Jack" name-tag instead.
  // Empty = name
  // match only. Capture the others' skins with /skinid and fill them in the same way if their
  // detection drifts.
  static final Npc LUMBER_MERCHANT =
      new Npc("Lumber Merchant", "lumber merchant", "", -125, 73, -41, 8.0);
  static final Npc LUMBER_JACK =
      new Npc(
          "Lumber Jack",
          "lumber jack",
          "6825030e7752faf7f816ac2a20b11432af4b2cde4932dc0e920831e621808ee8",
          -123,
          73,
          -30,
          8.0);
  static final Npc CHARLIE =
      new Npc(
          "Charlie",
          "charlie",
          "5766eb097bb486b3ea707ac1313a1bfff8846a2f55460082cef212f189130f1c",
          -277,
          80,
          -18,
          8.0);
  static final Npc KELLY = new Npc("Kelly", "kelly", "", -351, 94, 32, 8.0);
  static final Npc RYAN = new Npc("Ryan", "ryan", "", -364, 102, -91, 8.0);
  static final Npc MELODY = new Npc("Melody", "melody", "", -412, 109, 70, 8.0);
  static final Npc MOLBERT = new Npc("Molbert", "molbert", "", -467, 120, -42, 9.0);
  static final Npc FINAL_MOLBERT = new Npc("Molbert", "molbert", "", -450, 120, -64, 26.0);

  static final Target OAK_COLLECTION_START = new Target(-148, 73, -27, "Oak collection start");
  static final Target BIRCH_TRANSFER = new Target(-224, 73, -16, "Birch transfer");
  static final Target SPRUCE_TRANSFER = new Target(-314, 81, -10, "Spruce transfer");
  static final Target DARK_TRANSFER = new Target(-364, 90, -18, "Dark Oak transfer");
  static final Target ACACIA_TRANSFER = new Target(-400, 98, -35, "Acacia transfer");
  static final Target JUNGLE_TRANSFER = new Target(-439, 111, -17, "Jungle transfer");
  static final Target CAMPFIRE_ADJACENT = new Target(-363, 102, -95, "campfire edge");
  static final Target CAMPFIRE_CENTER = new Target(-363, 102, -94, "campfire");
  static final Target RYAN_EAST_SOUTH_APPROACH =
      new Target(-378, 99, -92, "Ryan approach waypoint");
  static final Target LEAVE_A = new Target(-463, 121, -31, "leave marker");
  static final Target TRAP_ONE = new Target(-466, 119, -54, "trap 1");
  static final Target TRAP_TWO = new Target(-449, 120, -65, "trap 2");
  static final Target TRAP_THREE = new Target(-440, 122, -92, "trap 3");

  /** Sidebar names of the park areas, in transfer order: index i is entered by transfer i. */
  private static final String[] parkAreas = {
    "birch park", "spruce woods", "dark thicket", "savanna woodland", "jungle island"
  };

  private static final double EXACT_ARRIVAL_DIST_SQ = 1.05 * 1.05;

  /**
   * Lowest y that can only be Molbert's island. The park climbs in plateaus (birch 73, spruce 81,
   * dark oak 90, acacia 98) and the Jungle transfer at y 111 is the last step before the island
   * floor at y 119-122, so anything at or above this is across and anything below is still on the
   * approach side.
   */
  private static final double JUNGLE_ISLAND_MIN_Y = 116.0;

  private Entity cachedNpc;
  private Npc cachedNpcKey;

  Entity npc(ClientLevel level, Npc npc) {
    if (cachedNpcKey == npc
        && cachedNpc != null
        && cachedNpc.isAlive()
        && isNearBlockCenter(cachedNpc, npc.x(), npc.y(), npc.z(), npc.radius())
        && matches(cachedNpc, npc)) {
      return cachedNpc;
    }
    cachedNpcKey = npc;
    cachedNpc = find(level, npc);
    return cachedNpc;
  }

  /**
   * Ryan's actual player model, not the separate armor-stand name tag.
   *
   * @param level the level to search
   * @param localPlayer the local player, never returned
   * @return Ryan's player model, or null when he is not loaded
   */
  public Player ryanPlayer(ClientLevel level, Entity localPlayer) {
    final Vec3 center = new Vec3(RYAN.x() + 0.5, RYAN.y() + 0.5, RYAN.z() + 0.5);
    Player player = nearestPlayer(level, localPlayer, center, RYAN.radius(), true);
    if (player != null) {
      return player;
    }

    final Entity label = npc(level, RYAN);
    if (label instanceof Player namedPlayer && namedPlayer != localPlayer) {
      return namedPlayer;
    }
    return label == null ? null : nearestPlayer(level, localPlayer, label.position(), 2.5, false);
  }

  private static Player nearestPlayer(
      ClientLevel level, Entity localPlayer, Vec3 center, double radius, boolean npcOnly) {
    Player best = null;
    double bestDistanceSq = radius * radius;
    for (Entity entity : level.entitiesForRendering()) {
      if (!(entity instanceof Player player) || player == localPlayer || !player.isAlive()) {
        continue;
      }
      if (npcOnly && !isHypixelNpcUuid(player.getUUID())) {
        continue;
      }
      final double distanceSq = player.position().distanceToSqr(center);
      if (distanceSq <= bestDistanceSq) {
        best = player;
        bestDistanceSq = distanceSq;
      }
    }
    return best;
  }

  static boolean isHypixelNpcUuid(UUID uuid) {
    return uuid != null && uuid.version() == 2;
  }

  static boolean ryanNeedsWaypoint(float yaw) {
    final Vec3 forward = Vec3.directionFromRotation(0.0f, yaw);
    return Math.abs(forward.x) > Math.abs(forward.z) ? forward.x > 0.0 : forward.z > 0.0;
  }

  /**
   * Match by skin id when the NPC has one (pins the real player-NPC past its name tag), else by
   * name.
   */
  private static boolean matches(Entity entity, Npc npc) {
    if (!npc.skinId().isEmpty() && npc.skinId().equals(MacroEntities.playerSkinHash(entity))) {
      return true;
    }
    return entityNameLower(entity).contains(npc.needle());
  }

  private static Entity find(ClientLevel level, Npc npc) {
    if (!npc.skinId().isEmpty()) {
      final Entity bySkin =
          MacroEntities.findNearestPlayerBySkin(
              level, npc.skinId(), npc.x(), npc.y(), npc.z(), npc.radius());
      if (bySkin != null) {
        return bySkin;
      }
    }
    return findNearestNamedEntity(level, npc.needle(), npc.x(), npc.y(), npc.z(), npc.radius());
  }

  boolean isAtTarget(LocalPlayer player, Target target) {
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
      Target target) {
    double dx = target.x() + 0.5 - playerX;
    double dz = target.z() + 0.5 - playerZ;
    return playerBlockX == target.x()
        && playerBlockZ == target.z()
        && Math.abs(playerY - target.y()) <= 2.5
        && dx * dx + dz * dz <= EXACT_ARRIVAL_DIST_SQ;
  }

  /**
   * Whether we are back on Molbert's island. The transfer pad is the only way up there and it fires
   * on contact, so height alone answers it - and it has to be answered by position, because "new
   * area discovered" only ever prints the first time we cross. Airborne does not count: the "Leave
   * the area" launcher arcs the player straight up through this band on the way out (log 2026-08-28
   * 13:12:44, y 127).
   */
  static boolean isOnJungleIsland(LocalPlayer player) {
    return player.onGround() && player.getY() >= JUNGLE_ISLAND_MIN_Y;
  }

  static boolean hasLeftJungleIsland(LocalPlayer player) {
    return player.onGround() && player.getY() < JUNGLE_ISLAND_MIN_Y;
  }

  /**
   * @param area the sidebar location line
   * @return the park area's index in transfer order, or -1 outside the park's areas
   */
  static int parkAreaIndex(String area) {
    if (area == null) {
      return -1;
    }
    final String clean = normalizeSearchText(area);
    for (int i = 0; i < parkAreas.length; i++) {
      if (clean.contains(parkAreas[i])) {
        return i;
      }
    }
    return -1;
  }

  void resetNpc() {
    cachedNpc = null;
    cachedNpcKey = null;
  }
}
