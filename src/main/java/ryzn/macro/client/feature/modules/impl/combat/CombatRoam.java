package ryzn.macro.client.feature.modules.impl.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * Mob-memory search, adapted from V5's CombatBot. When no live target is in sight the bot should
 * not just stop - it should walk back toward where mobs were last seen / killed and re-acquire.
 * This keeps a small ring of recent mob positions and, on request, picks the most promising one
 * (near + roughly in front of the player) as a destination. A chosen destination is held until the
 * player arrives or it times out, then the spot is forgotten so the search rotates through the
 * remembered clusters instead of fixating on one.
 *
 * <p>Pure state + selection: it never touches the pathfinder. {@link CombatModule} reads {@link
 * #chooseDestination} and drives {@code WalkingNavigation}, so navigation has a single owner.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class CombatRoam {

  private final CombatHazards hazards;

  private static final int MEMORY_MAX = 8; // ring size
  private static final long MEMORY_EXPIRY_MS =
      120_000L; // a remembered spot is dropped after this long unseen
  private static final double MERGE_DIST =
      3.0; // a new memory within this distance of an old one refreshes it
  private static final double ARRIVE_DIST =
      2.5; // a search destination counts as reached within this XZ distance
  private static final long SEARCH_TIMEOUT_MS =
      15_000L; // abandon a search spot after this long (can't get there)
  private static final double FORWARD_BIAS =
      6.0; // prefer remembered spots ahead of the player (blocks of bias)
  private static final double WANDER_MIN_DIST =
      8.0; // short local patrol hops when no memory exists
  private static final double WANDER_MAX_DIST = 10.0;
  private static final double WANDER_MIN_TURN_DEG = 60.0;
  private static final double WANDER_MAX_TURN_DEG = 120.0;
  private static final int WANDER_TRIES = 10;
  private static final double MERGE_DIST_SQ = MERGE_DIST * MERGE_DIST;
  private static final double ARRIVE_DIST_SQ = ARRIVE_DIST * ARRIVE_DIST;

  private record Memory(double x, double y, double z, long time) {}

  record WanderPoint(double x, double y, double z) {}

  private final List<Memory> memory = new ArrayList<>();

  private double searchX;
  private double searchY;
  private double searchZ;
  private boolean hasSearch = false;
  private long searchSetTime = 0L;
  private double wanderHeadingDeg = Double.NaN;
  private boolean currentIsWander = false;

  /** Full wipe (memory + active search) - used when the module is disabled. */
  public void reset() {
    memory.clear();
    clearSearch();
    wanderHeadingDeg = Double.NaN;
  }

  /** Drop only the active search destination; remembered positions are kept. */
  public void clearSearch() {
    hasSearch = false;
    searchSetTime = 0L;
    currentIsWander = false;
  }

  /**
   * True while a search destination is being walked.
   *
   * @return true while a search destination is active
   */
  public boolean isSearching() {
    return hasSearch;
  }

  /** A real target was acquired - abandon the current roam (remembered spots stay). */
  public void onTargetAcquired() {
    clearSearch();
  }

  /**
   * The path to the current destination failed - it is unreachable, forget it and rotate to the
   * next.
   */
  public void onSearchPathFailed() {
    if (!hasSearch) {
      return;
    }
    if (currentIsWander) {
      rotateWanderHeading();
    } else {
      forget(searchX, searchY, searchZ);
    }
    clearSearch();
  }

  /**
   * Record a mob position (e.g. where a target just died or was last seen). Hazard spots are
   * skipped.
   *
   * @param x the remembered X
   * @param y the remembered Y
   * @param z the remembered Z
   * @param avoidDanger whether spots inside hazard zones are skipped
   */
  public void remember(double x, double y, double z, boolean avoidDanger) {
    if (avoidDanger && !hazards.isSafe(x, y, z)) {
      return;
    }
    final long now = System.currentTimeMillis();
    prune(now);
    for (int i = 0; i < memory.size(); i++) {
      final Memory m = memory.get(i);
      if (distSq(m.x, m.y, m.z, x, y, z) < MERGE_DIST_SQ) {
        memory.set(
            i, new Memory(m.x, m.y, m.z, now)); // refresh timestamp, keep the original anchor
        return;
      }
    }
    memory.add(new Memory(x, y, z, now));
    if (memory.size() > MEMORY_MAX) {
      memory.remove(0);
    }
  }

  /**
   * Choose / refresh the block to search toward, or {@code null} when there is nothing worth
   * searching. The active destination is held across calls until the player reaches it, it times
   * out, or it turns unsafe; on any of those it is forgotten and the next-best remembered spot is
   * chosen.
   *
   * @param player the local player
   * @param avoidDanger whether spots inside hazard zones are skipped
   * @return the block to search toward, or null when nothing is worth searching
   */
  public BlockPos chooseDestination(LocalPlayer player, boolean avoidDanger) {
    return chooseDestination(player, avoidDanger, "");
  }

  public BlockPos chooseDestination(LocalPlayer player, boolean avoidDanger, String boundsCsv) {
    final long now = System.currentTimeMillis();
    prune(now);
    final CombatBounds bounds = CombatBounds.parse(boundsCsv);

    if (hasSearch) {
      final boolean arrived =
          distFlatSq(player.getX(), player.getZ(), searchX, searchZ) <= ARRIVE_DIST_SQ;
      final boolean timedOut = now - searchSetTime > SEARCH_TIMEOUT_MS;
      final boolean unsafe = avoidDanger && !hazards.isSafe(searchX, searchY, searchZ);
      final boolean outOfBounds = !CombatBounds.allows(bounds, searchX, searchY, searchZ);
      if (arrived || timedOut || unsafe) {
        if (currentIsWander) {
          rotateWanderHeading();
        } else {
          forget(searchX, searchY, searchZ);
        }
        clearSearch();
      } else if (outOfBounds) {
        clearSearch();
      }
    }

    if (!hasSearch) {
      final Memory next = pickBest(player, avoidDanger, bounds);
      if (next != null) {
        searchX = next.x;
        searchY = next.y;
        searchZ = next.z;
        currentIsWander = false;
      } else {
        final WanderPoint wander = pickWander(player, avoidDanger, bounds);
        if (wander == null) {
          return null;
        }
        searchX = wander.x;
        searchY = wander.y;
        searchZ = wander.z;
        currentIsWander = true;
      }
      hasSearch = true;
      searchSetTime = now;
    }
    return BlockPos.containing(searchX, searchY, searchZ);
  }

  /** Lowest-score remembered spot: close and roughly in front of the player wins. */
  private Memory pickBest(LocalPlayer player, boolean avoidDanger, CombatBounds bounds) {
    if (memory.isEmpty()) {
      return null;
    }
    final double px = player.getX();
    final double pz = player.getZ();
    final float yawRad = (float) Math.toRadians(player.getYRot());
    final double fx = -Mth.sin(yawRad);
    final double fz = Mth.cos(yawRad); // player forward in XZ
    Memory best = null;
    double bestScore = Double.MAX_VALUE;
    for (Memory m : memory) {
      if (avoidDanger && !hazards.isSafe(m.x, m.y, m.z)) {
        continue;
      }
      if (!CombatBounds.allows(bounds, m.x, m.y, m.z)) {
        continue;
      }
      final double dx = m.x - px;
      final double dz = m.z - pz;
      final double d = Math.sqrt(dx * dx + dz * dz);
      final double dot = d > 0 ? (dx * fx + dz * fz) / d : 1.0;
      final double score = d - dot * FORWARD_BIAS; // nearer + more in-front -> lower -> preferred
      if (score < bestScore) {
        bestScore = score;
        best = m;
      }
    }
    return best;
  }

  private WanderPoint pickWander(LocalPlayer player, boolean avoidDanger, CombatBounds bounds) {
    if (Double.isNaN(wanderHeadingDeg)) {
      wanderHeadingDeg = player.getYRot();
    }
    for (int i = 0; i < WANDER_TRIES; i++) {
      final double distance =
          ThreadLocalRandom.current().nextDouble(WANDER_MIN_DIST, WANDER_MAX_DIST);
      final WanderPoint point =
          projectWanderPoint(
              player.getX(), player.getY(), player.getZ(), wanderHeadingDeg, distance);
      final boolean safe = !avoidDanger || hazards.isSafe(point.x(), point.y(), point.z());
      final boolean inBounds = CombatBounds.allows(bounds, point.x(), point.y(), point.z());
      if (safe && inBounds) {
        return point;
      }
      rotateWanderHeading();
    }
    return null;
  }

  static WanderPoint projectWanderPoint(
      double x, double y, double z, double headingDeg, double distance) {
    final double yawRad = Math.toRadians(headingDeg);
    return new WanderPoint(x - Math.sin(yawRad) * distance, y, z + Math.cos(yawRad) * distance);
  }

  private void rotateWanderHeading() {
    final ThreadLocalRandom rng = ThreadLocalRandom.current();
    final double turn = rng.nextDouble(WANDER_MIN_TURN_DEG, WANDER_MAX_TURN_DEG);
    wanderHeadingDeg = wrapDegrees(wanderHeadingDeg + (rng.nextBoolean() ? turn : -turn));
  }

  private static double wrapDegrees(double degrees) {
    double wrapped = degrees % 360.0;
    if (wrapped >= 180.0) {
      wrapped -= 360.0;
    }
    if (wrapped < -180.0) {
      wrapped += 360.0;
    }
    return wrapped;
  }

  private void forget(double x, double y, double z) {
    memory.removeIf(m -> distSq(m.x, m.y, m.z, x, y, z) <= MERGE_DIST_SQ);
  }

  private void prune(long now) {
    memory.removeIf(m -> now - m.time() > MEMORY_EXPIRY_MS);
  }

  private static double distSq(double x1, double y1, double z1, double x2, double y2, double z2) {
    final double dx = x1 - x2;
    final double dy = y1 - y2;
    final double dz = z1 - z2;
    return dx * dx + dy * dy + dz * dz;
  }

  private static double distFlatSq(double x1, double z1, double x2, double z2) {
    final double dx = x1 - x2;
    final double dz = z1 - z2;
    return dx * dx + dz * dz;
  }

  /**
   * The other way to roam: a managed session can hand over a fixed list of waypoints to walk in a
   * loop instead of chasing remembered mobs. This is the cursor into that list.
   *
   * <p>It is here, and it is one object, because the index was previously derived in two places -
   * once to decide where to walk, and again to decide which direction the target cone points down -
   * so the body and the targeting could reason about different waypoints.
   */
  static final class Patrol {

    private static final double ARRIVE_DISTANCE_SQ = 2.5 * 2.5;
    private static final double ARRIVE_HEIGHT = 3.0;
    private static final double TARGET_CONE_COS_SQ = 0.0301536896; // cos(80 degrees)^2

    private int index = -1;

    void reset() {
      index = -1;
    }

    boolean resumeAt(BlockPos point, List<BlockPos> points) {
      final int next = points.indexOf(point);
      if (next < 0) {
        return false;
      }
      index = next;
      return true;
    }

    /**
     * The waypoint currently being walked to. Settles the cursor onto the nearest point when unset.
     */
    BlockPos waypoint(double x, double y, double z, List<BlockPos> points) {
      if (points.isEmpty()) {
        return null;
      }
      if (index < 0 || index >= points.size()) {
        index = nearest(x, y, z, points);
      }
      return points.get(index);
    }

    /** Step to the next waypoint and return it. */
    BlockPos advance(List<BlockPos> points) {
      if (points.isEmpty()) {
        index = -1;
        return null;
      }
      index = (Math.max(0, index) + 1) % points.size();
      return points.get(index);
    }

    /**
     * Where the body is heading, WITHOUT moving the cursor - the leg the target cone is measured
     * along. Standing on the current point means the next one is the heading.
     *
     * <p>Deliberately no "stop here on arrival" rule (unlike the walk, which parks on the point
     * while a managed session waits for a spawn): a session that suppresses targets never selects
     * one, so the cone is not consulted at all in the only case where the two would differ.
     */
    BlockPos lookahead(double x, double y, double z, List<BlockPos> points) {
      if (points.isEmpty()) {
        return null;
      }
      final int at = index >= 0 && index < points.size() ? index : nearest(x, y, z, points);
      final BlockPos point = points.get(at);
      return reached(x, y, z, point) ? points.get((at + 1) % points.size()) : point;
    }

    static int nearest(double x, double y, double z, List<BlockPos> points) {
      int bestIndex = 0;
      double bestDistance = Double.POSITIVE_INFINITY;
      for (int i = 0; i < points.size(); i++) {
        BlockPos point = points.get(i);
        double dx = x - (point.getX() + 0.5);
        double dy = y - point.getY();
        double dz = z - (point.getZ() + 0.5);
        double distance = dx * dx + dy * dy + dz * dz;
        if (distance < bestDistance) {
          bestDistance = distance;
          bestIndex = i;
        }
      }
      return bestIndex;
    }

    static boolean reached(double x, double y, double z, BlockPos point) {
      double dx = x - (point.getX() + 0.5);
      double dz = z - (point.getZ() + 0.5);
      return dx * dx + dz * dz <= ARRIVE_DISTANCE_SQ && Math.abs(y - point.getY()) <= ARRIVE_HEIGHT;
    }

    /**
     * True when a candidate mob sits inside the cone around the current patrol leg (or the leg is
     * ~zero).
     */
    static boolean targetAhead(
        double selfX, double selfZ, double pathX, double pathZ, double targetX, double targetZ) {
      double pathDx = pathX - selfX;
      double pathDz = pathZ - selfZ;
      double targetDx = targetX - selfX;
      double targetDz = targetZ - selfZ;
      double dot = pathDx * targetDx + pathDz * targetDz;
      double pathLengthSq = pathDx * pathDx + pathDz * pathDz;
      double targetLengthSq = targetDx * targetDx + targetDz * targetDz;
      return pathLengthSq < 0.25
          || targetLengthSq < 1.0e-6
          || dot > 0.0 && dot * dot >= TARGET_CONE_COS_SQ * pathLengthSq * targetLengthSq;
    }
  }
}
