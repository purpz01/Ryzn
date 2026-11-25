package ryzn.macro.client.feature.modules.impl.combat;

/**
 * Every number that decides how the bot fights, in one place.
 *
 * <p>If you want to change the bot's behaviour and you are not adding a new behaviour, the change
 * belongs here. Constants that are pure implementation detail of a single file (buffer sizes,
 * scan-box dimensions, palette lengths) deliberately stay in that file: centralising those would
 * add a prefix to every use site and buy nothing, because nobody tunes them.
 *
 * <p>Names say what the number means, not what bug it fixed. Where a value is load-bearing because
 * of a specific in-game failure, that failure is named in the comment, because it is the only
 * record of why the number cannot simply be "improved".
 */
final class CombatTuning {

  private CombatTuning() {}

  // ---- Choosing how to get to the mob
  // -------------------------------------------------------------

  /**
   * Within this distance the bot walks straight at the mob instead of asking the pathfinder for a
   * route. Replanning A* toward a mob that shuffles a block every half second used to tear the
   * route down and release the movement keys each time, so the body stuttered in place. Walking
   * answers the mob every tick.
   */
  static final double WALK_STRAIGHT_RANGE = 4.5;

  /**
   * The furthest the bot will walk straight at a mob it can see on roughly its own level. Beyond
   * this the pathfinder takes over regardless. Keeps an obvious nearby zombie flowing without a
   * route round trip.
   */
  static final double WALK_STRAIGHT_MAX_RANGE = 24.0;

  /**
   * A mob more than this far above the player cannot be reached by walking forward: walking just
   * grinds the wall below it, so the pathfinder handles the climb. Anything level or below is
   * walkable, since dropping off an edge is just walking forward.
   */
  static final double WALK_STRAIGHT_MAX_CLIMB = 1.25;

  /**
   * Once the mob is within this many degrees of the current facing, the body commits forward (holds
   * W) even before the head has finished turning, so it starts moving early. Outside the cone the
   * body holds still and lets the aim turn it on first: strafing toward an off-axis bearing is what
   * read as backstepping.
   */
  static final double FORWARD_COMMIT_FOV_DEG = 100.0;

  // ---- Melee spacing
  // ------------------------------------------------------------------------------

  /**
   * Ticks of current speed added to the stop gap as a braking allowance, so a fast approach stops
   * in time.
   */
  static final double MELEE_BRAKE_TICKS = 2.0;

  /**
   * Inside melee, lateral errors under this many degrees press no side key, so the bot holds its
   * ground instead of side-gliding around a point-blank mob. Wide on purpose.
   */
  static final double MELEE_STRAFE_DEADZONE_DEG = 65.0;

  /**
   * A rival already in attack range and this many blocks closer takes over immediately, even
   * mid-fight, so the bot fights the threat standing on it rather than the one it started walking
   * toward.
   */
  static final double MELEE_TAKEOVER_MARGIN = 1.5;

  /**
   * A short human pause on arrival before the first swing, randomised between these bounds (ticks).
   */
  static final int PAUSE_BEFORE_SWING_MIN_TICKS = 2;

  static final int PAUSE_BEFORE_SWING_MAX_TICKS = 4;

  // ---- Getting unstuck
  // ----------------------------------------------------------------------------

  /**
   * Below this horizontal speed (blocks/tick) on the ground, the bot counts as not actually moving.
   */
  static final double STUCK_SPEED = 0.05;

  /** Consecutive ticks of wall contact before trying a hop over it. */
  static final int WALL_HOP_TICKS = 3;

  /** Minimum ticks between hops, so contact with a wall does not become a pogo stick. */
  static final int HOP_COOLDOWN_TICKS = 3;

  // ---- Noticing the bot is getting nowhere
  // --------------------------------------------------------

  /** Closing this many blocks counts as real progress and resets the no-progress timer. */
  static final double PROGRESS_EPS = 0.25;

  /**
   * Ticks without progress, while tracking a mob it cannot hit, before the bot concludes it is
   * stuck.
   */
  static final int NO_PROGRESS_TICKS = 25;

  /** Consecutive failed routes to one mob before it is skipped and selection moves on. */
  static final int MAX_ROUTE_FAILURES = 3;

  // ---- Choosing which mob
  // -------------------------------------------------------------------------

  /**
   * Extra blocks past scan range before a mob already being fought is dropped, so one sitting
   * exactly at the boundary does not flicker lost/reacquired as it shuffles across it (each flicker
   * tore the route down).
   */
  static final double RETAIN_RANGE_BUFFER = 4.0;

  /**
   * How much cheaper a rival must be to steal the current target mid-approach. Cost is roughly
   * distance*10 + angle*0.5, so 40 is about "four blocks closer". Big enough that two similar mobs
   * never flip-flop, small enough that a zombie spawning next to the bot interrupts a cross-map
   * walk.
   */
  static final double UPGRADE_MARGIN = 40.0;

  /**
   * Ticks a freshly picked mob is held before any cost-based swap may happen, so selection cannot
   * churn. Death, invalidity, being stuck, and a hittable melee threat all still switch
   * immediately.
   */
  static final int TARGET_COMMIT_TICKS = 14;

  // ---- Looking at the mob
  // -------------------------------------------------------------------------

  /**
   * Hold an existing head lock this long after the mob ducks behind cover, so brief occlusion does
   * not flicker the aim off it.
   */
  static final long SIGHT_GRACE_MS = 1200L;

  /**
   * Height up the hitbox the bot looks at and tests sight against, as a fraction. Upper body, so
   * the aim point and the "can I see it" test agree.
   */
  static final double AIM_HEIGHT_FRACTION = 0.62;

  // ---- Swinging
  // -----------------------------------------------------------------------------------

  /** Attack-strength recovery (0-1) required before a swing lands full damage. */
  static final float ATTACK_READY = 0.92f;
}
