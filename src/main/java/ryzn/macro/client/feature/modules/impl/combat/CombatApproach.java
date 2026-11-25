package ryzn.macro.client.feature.modules.impl.combat;

/**
 * The only place sight decides anything.
 *
 * <p>The rule this file exists to enforce: <b>sight never decides WHAT to fight, only HOW to reach
 * it.</b> Picking a target is by mob id and name alone, so the bot still finds a zombie standing
 * behind a pillar. Sight enters here and nowhere else, to answer one question: can I walk straight
 * at it, or do I need the pathfinder to take me round something?
 *
 * <p>This replaces a five-way chase decision (direct, bridge, flow, stall-bridge, route) whose
 * conditions overlapped and whose names described the bug each was added for rather than what it
 * does. Four of those five already required sight; the fifth not requiring it WAS the pillar bug,
 * because it was also the one checked first. Collapsing them removes the bug rather than patching
 * it.
 *
 * <p>Pure: no Minecraft types, so the whole decision is unit-testable with plain numbers.
 */
final class CombatApproach {

  /** How the body should get to the mob this tick. */
  enum Approach {
    /** Hold the movement keys straight at it. */
    WALK_STRAIGHT,
    /** Stand still and let the aim turn onto it first; walking now would carry the bot sideways. */
    TURN_FIRST,
    /** Let the pathfinder drive: too far, too high, or something is in the way. */
    FOLLOW_ROUTE
  }

  private CombatApproach() {}

  /**
   * Decide how to close on the mob.
   *
   * @param distance blocks to the mob
   * @param heightAbove mob Y minus player Y (positive means the mob is above)
   * @param bearingError absolute degrees between the current facing and the mob's bearing
   * @param canSee clear line of sight from the eyes to the mob's upper body
   * @param routeRunning a pathfinder route is currently live
   * @param recovering the stuck watchdog wants a forward push this tick
   */
  static Approach decide(
      double distance,
      double heightAbove,
      double bearingError,
      boolean canSee,
      boolean routeRunning,
      boolean recovering) {
    if (!canWalkStraight(distance, heightAbove, canSee, routeRunning, recovering)) {
      return Approach.FOLLOW_ROUTE;
    }
    // Walking is possible, but not while the mob is behind us: pressing forward would carry the
    // body away
    // from it, and strafing toward the bearing is the sideways shuffle that used to read as
    // backstepping.
    return bearingError > CombatTuning.FORWARD_COMMIT_FOV_DEG
        ? Approach.TURN_FIRST
        : Approach.WALK_STRAIGHT;
  }

  private static boolean canWalkStraight(
      double distance,
      double heightAbove,
      boolean canSee,
      boolean routeRunning,
      boolean recovering) {
    // Perched above a jump's reach: walking just grinds the wall underneath. Level or below is
    // walkable,
    // since dropping off an edge is only walking forward.
    if (heightAbove > CombatTuning.WALK_STRAIGHT_MAX_CLIMB) {
      return false;
    }
    if (distance > CombatTuning.WALK_STRAIGHT_MAX_RANGE) {
      return false;
    }

    // CLOSE: walk, and keep walking, whether or not the sightline is clear this instant.
    //
    // Handing close range to the pathfinder is what makes the body stutter: the route is replanned
    // every
    // time the mob shuffles a block, and each replan releases the movement keys. A sightline that
    // flickers
    // (the mob strafes behind a gravestone, terrain clips the ray for a tick) would flip the bot
    // between
    // walking and routing several times a second, which reads far worse than the problem it solves.
    //
    // Something genuinely in the way is handled where it belongs, by CombatObstacle: it feels the
    // collision, sees whether the obstruction is a step or a wall, and slides round a wall
    // laterally.
    // That reaches the mob behind a pillar rather than merely refusing to grind into it. If the bot
    // truly
    // cannot get there, the no-progress watchdog gives up and the route takes over.
    if (recovering || distance <= CombatTuning.WALK_STRAIGHT_RANGE) {
      return true;
    }

    // FURTHER OUT: sight decides. Committing to a long walk at something we cannot see is how the
    // bot ends
    // up jogging into a building; out here the pathfinder is the right tool and there is no thrash
    // risk,
    // because a distant mob does not invalidate the route every time it takes a step.
    if (!canSee) {
      return false;
    }
    // A live route keeps driving unless the mob has swung well off the route's heading.
    return !routeRunning;
  }

  /**
   * Whether the body should hold still at melee range rather than keep closing.
   *
   * @param sameLevel the mob is within a step of the player's own level
   * @param canHit the crosshair or attack ray is already on it
   * @param gap horizontal gap between the two hitboxes
   * @param stopGap gap at which to stop, including the braking allowance for current speed
   * @param pauseAvailable the short human pause before the first swing has not been spent on this
   *     mob
   * @param neverStop one-tap / flow modes walk through the mob rather than settling on it
   */
  static Hold hold(
      boolean sameLevel,
      boolean canHit,
      double gap,
      double stopGap,
      boolean pauseAvailable,
      boolean neverStop) {
    if (neverStop) {
      return Hold.KEEP_MOVING;
    }
    final boolean arrived = sameLevel && (canHit || gap <= stopGap);
    if (arrived && pauseAvailable) {
      return Hold.PAUSE_BEFORE_SWING;
    }
    // Stop as soon as the swing would land, not when the hitboxes are touching. Closing the last
    // half
    // block after the mob is already hittable is what reads as the bot shoving its face into
    // things: it
    // gains nothing, and it keeps the body pushing while the swing is happening.
    if (arrived) {
      return Hold.TOUCHING;
    }
    return Hold.KEEP_MOVING;
  }

  /** Why the body is or is not holding still in melee. */
  enum Hold {
    /** Close the remaining distance. */
    KEEP_MOVING,
    /** Arrived: take the brief pause a person takes before swinging. */
    PAUSE_BEFORE_SWING,
    /** Standing on the mob already; do not shove into it. */
    TOUCHING;

    boolean stops() {
      return this != KEEP_MOVING;
    }
  }
}
