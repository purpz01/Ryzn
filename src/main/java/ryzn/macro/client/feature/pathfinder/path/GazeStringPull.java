package ryzn.macro.client.feature.pathfinder.path;

import java.util.List;

/**
 * Picks the trail node the head looks at by pulling a string along the route.
 *
 * <p>Anchor the string at the cursor node and let it out down the trail while the eye can still see
 * the next node. It goes taut on the first node the eye cannot reach, and that last visible node is
 * what the head aims at - the furthest thing actually in view, which is what a person looks at down
 * an open hall. Nothing here touches the route: the body still walks every node, this only chooses
 * where the eyes go.
 *
 * <p>The string also goes taut on a node the body has to physically ARRIVE at before the route
 * continues - a riser it must climb or a lip it must step off. That node is the next thing the body
 * does, so it is the thing to look at, and pulling past it is the "staring up the staircase while
 * walking at the bottom step" that this replaces. Level with it, the commitment is discharged and
 * the string lets out again, which is how the gaze walks a flight one tread at a time.
 *
 * <p>Airborne the pick is LATCHED. Height is measured against the feet, and a jump lifts the feet
 * over the step mid-arc, so a fresh pick would discharge the commitment in the air and hand the
 * head to a node past the one being jumped onto - with strafe stripped in flight, that flies the
 * body into the wall.
 */
final class GazeStringPull {

  /**
   * Rise the body clears by walking (vanilla auto-step). Anything more is a hop it must commit to.
   */
  static final double STEP_UP_COMMIT = 0.6;

  /** Drop that is a step down rather than a ledge. Past this the body has to leave the ground. */
  static final double STEP_DOWN_COMMIT = 1.5;

  /** Flat distance inside which a gaze node is too close for its bearing to mean anything. */
  static final double NODE_HANDOFF_BLOCKS = 1.5;

  /** Share of the leg to the approaching turn node walked before the gaze moves on to the next. */
  static final double GAZE_HANDOFF_PROGRESS = 0.7;

  /** Once handed on, the gaze only comes back if progress on the leg falls below this again. */
  static final double GAZE_RELEASE_PROGRESS = 0.5;

  /** Ticks before reaching a turn node at which the gaze moves on to the next one. */
  static final double GAZE_LEAD_TICKS = 9.0;

  /**
   * Per-index probes into the live trail. The planner backs these with the world; tests fake them.
   */
  interface Terrain {
    /** Feet Y of a body standing on node {@code index}. */
    double footY(int index);

    /** Flat player-to-node distance, in blocks. */
    double flatGap(int index);

    /** Clear line of sight from the eye to node {@code index}. */
    boolean visible(int index);
  }

  /** Last pick, held across airborne ticks. Negative until the first grounded pick of a route. */
  private int held = -1;

  /**
   * The node to look at.
   *
   * @param size trail length
   * @param cursor node the body is walking at; the pick never falls behind it
   * @param feetY player feet Y
   * @param grounded false suspends the pull entirely - see the class note on jumps
   * @param maxRange flat blocks past which the string stops being let out
   */
  int update(
      int size, int cursor, double feetY, boolean grounded, double maxRange, Terrain terrain) {
    if (size <= 0) {
      return -1;
    }
    int start = Math.clamp(cursor, 0, size - 1);
    if (!grounded) {
      // Airborne the pull does not run at all, because a body in the air has ARRIVED nowhere - no
      // commitment has been discharged, whatever height the arc happens to be passing through. Hold
      // the pick the takeoff made; if the body has flown past it (a jump-boosted arc clears several
      // nodes, and the flat cursor tests retire them), look at the node the route is now heading to
      // rather than one beyond it.
      //
      // The fall-through is narrow on purpose. On open ground the pick is already many nodes ahead,
      // so constant jumping never reaches it and the head does not bob down to the next node on
      // every
      // hop; it only fires when the cursor has genuinely overtaken the gaze.
      held = held >= 0 && start <= held + 1 ? held : start;
      return held;
    }
    held = pull(size, start, feetY, maxRange, terrain);
    return held;
  }

  /** Approaching node the gaze was handed on from, or -1 while it is still on that node. */
  private int handedOffFrom = -1;

  /**
   * Node-to-node pick: the turn node being approached, handed on to the one after it (or the goal)
   * once the body has walked {@link #GAZE_HANDOFF_PROGRESS} of the leg from the previous turn node.
   * Progress is the distance from the previous node over that plus the distance to the approaching
   * one, so it reads sensibly off the line too. The handoff is latched until the body passes the
   * node, or progress falls below {@link #GAZE_RELEASE_PROGRESS}, so the head never flicks between
   * the two. While handed on, the body keeps walking to the approaching node with whatever keys
   * point at it, so it strafes the corner without sprinting. A node the body has to commit to (a
   * riser to climb or a lip to drop off) keeps the gaze until the body stands level with it on the
   * ground.
   *
   * <p>It also hands on once the body is {@link #GAZE_LEAD_TICKS} from the node at its current
   * speed, whichever comes first. A person walking a route starts turning the head that long before
   * the body turns (median of 74 recorded corners); at 70% of a long leg alone the bot started at 5
   * ticks, so the body met the corner with the head still turning and strafed it instead.
   *
   * @param nodeMarks trail indices of the route's turn nodes, ascending
   * @param feetY player feet Y, for the commitment test
   * @param grounded false blocks a new handoff, so a jump arc cannot discharge a commitment
   * @param speed flat body speed in blocks per tick
   */
  int updateNodes(
      int size,
      int cursor,
      List<Integer> nodeMarks,
      double feetY,
      boolean grounded,
      double speed,
      Terrain terrain) {
    if (size <= 0) {
      return -1;
    }
    final int start = Math.clamp(cursor, 0, size - 1);
    int previous = 0;
    int approaching = -1;
    int following = -1;
    for (int mark : nodeMarks) {
      if (mark >= size) {
        continue;
      }
      if (mark < start) {
        previous = mark;
      } else if (approaching < 0) {
        approaching = mark;
      } else {
        following = mark;
        break;
      }
    }
    if (approaching < 0) {
      approaching = size - 1;
    } else if (following < 0 && approaching != size - 1) {
      following = size - 1;
    }
    if (following < 0) {
      handedOffFrom = -1;
      held = approaching;
      return held;
    }
    final double toNode = terrain.flatGap(approaching);
    final double fromPrevious = previous < approaching ? terrain.flatGap(previous) : 0.0;
    final double legSum = fromPrevious + toNode;
    final double progress = legSum > 1.0e-6 ? fromPrevious / legSum : 1.0;
    final boolean handOff =
        handedOffFrom == approaching
            ? progress >= GAZE_RELEASE_PROGRESS
            : grounded
                && (progress >= GAZE_HANDOFF_PROGRESS || toNode <= speed * GAZE_LEAD_TICKS)
                && !commits(terrain.footY(approaching), feetY);
    handedOffFrom = handOff ? approaching : -1;
    held = handOff ? following : approaching;
    return held;
  }

  void reset() {
    held = -1;
    handedOffFrom = -1;
  }

  private static int pull(int size, int start, double feetY, double maxRange, Terrain terrain) {
    if (commits(terrain.footY(start), feetY)) {
      return start;
    }

    int furthest = start;
    for (int index = start + 1; index < size; index++) {
      if (commits(terrain.footY(index), feetY)) {
        return index;
      }
      if (terrain.flatGap(index) > maxRange) {
        break;
      }
      if (!terrain.visible(index)) {
        break;
      }
      furthest = index;
    }
    return furthest;
  }

  /**
   * Whether standing on {@code nodeFootY} is something the body has to arrive at, not walk through.
   */
  private static boolean commits(double nodeFootY, double feetY) {
    double rise = nodeFootY - feetY;
    return rise > STEP_UP_COMMIT || -rise > STEP_DOWN_COMMIT;
  }
}
