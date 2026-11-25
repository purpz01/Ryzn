package ryzn.macro.client.feature.pathfinder.path;

import java.util.ArrayList;
import java.util.List;
import lombok.Setter;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.groups.WalkingSettings.Control;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.modules.impl.combat.CombatModule;
import ryzn.macro.client.feature.modules.impl.combat.RecordedCombatMovement;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.feature.rotation.impl.Angle;
import ryzn.macro.client.feature.rotation.impl.AngleUtil;
import ryzn.macro.client.input.MacroKeys;
import ryzn.macro.client.util.time.Clock;

/**
 * Carries the player across a sequence of planner turn nodes. A Catmull-Rom curve is laid over the
 * nodes, vanilla movement keys are held to travel, and the head is steered by a proportional
 * controller under mouse-GCD quantization. It hops stairs and walls, notices wedging, and
 * interpolates the view between ticks. A single active path arrives via setPath; standability is
 * resolved through WalkCheck.
 */
public class PathExecutor {

  // movement feel knobs
  private static final float WIDE_FACING_GATE_DEG = 135f;
  private static final float HOP_FACING_GATE_DEG = 45f;
  private static final float CLIMB_HOP_GATE_DEG = 90f;

  /** A macro gaze hint is only followed while it stays this close to the route heading. */
  private static final float GAZE_HINT_MAX_DEGREES = 60f;

  private static final double NEAR_NODE_PEEK = 3.5;
  static final double CRAWL_SPEED_FLOOR = 0.05;
  static final long WEDGE_TIMEOUT_MS = 500;
  // XZ net-displacement stuck watch (jump-boost-proof). The speed wedge above is fooled when a high
  // server
  // jump-boost has the player bouncing against a wall: instantaneous/vertical speed stays high so
  // the wedge
  // keeps resetting, yet the body never advances. This keys off NET horizontal translation instead
  // - if the
  // player fails to cover PROGRESS_MIN_BLOCKS in XZ within PROGRESS_STALL_MS, it has stopped
  // advancing along
  // the route -> replan. ponytail: assumes every real climb this bot makes also advances XZ (it
  // hops ONTO the
  // next block, never a pure-vertical ladder), so plain XZ is enough - no separate
  // vertical-progress guard.
  // Threshold and window are scaled together: the false-fire risk is the RATIO (0.67 blocks/s
  // tolerated),
  // so halving both keeps sensitivity identical while cutting the stall-to-replan wait in half.
  static final double PROGRESS_MIN_BLOCKS = 1.0;
  static final long PROGRESS_STALL_MS = 1500;
  static final double WATER_PROGRESS_MIN_BLOCKS = 0.6;
  static final long WATER_PROGRESS_STALL_MS = 3500;
  // Replanning from a spot the body cannot leave returns the same route, so the loop is silent and
  // endless
  // (logged: 78/121 -> 79/122 -> 78/121 ... for 40+ seconds). After this many from one cell, poison
  // the
  // node we cannot reach so the search is FORCED to answer differently. See tickProgressWatch.
  // One, at the user's request: the very first reroute from a stall already goes another way. At
  // two the body walked the same failed route twice before anything changed.
  static final int STUCK_REPLANS_BEFORE_AVOID = 1;

  /** Progress-stall reroutes from one spot before the route is dropped as having no way on. */
  static final int STUCK_AVOID_RADIUS = 2;

  static final double STUCK_AVOID_PENALTY = 90.0;
  static final int STUCK_AVOID_TTL_SEARCHES = 3;
  static final long HOP_FAIL_TIMEOUT_MS = 1500;
  static final int HOP_FAIL_MIN_TRIES = 3;
  static final int HOP_COOLDOWN_TICKS = 3;
  private static final int UNEXPECTED_HOP_ARM_TICKS = 6; // 300ms at 20 tps
  private static final int PLANNED_JUMP_LEAD_TICKS = 6; // start real planned jumps before the riser
  private static final double PLANNED_JUMP_LEAD_MIN_BLOCKS = 0.8;
  private static final double PLANNED_JUMP_LEAD_MAX_BLOCKS = 1.7;
  private static final double CONSTANT_JUMP_BUFFER_BLOCKS = 5.0;
  // Wall-grind failsafe: how many ticks the body has to stay blocked (forward held, grounded, and
  // either physically colliding or stalled at ~0 speed) before a single hop is forced. No facing
  // gate,
  // no step probe. People walk into a thing, hesitate, then hop; copying that frees up the
  // obstacles
  // the planner-side hop logic reads wrong.
  private static final int WALL_HOP_TICKS = UNEXPECTED_HOP_ARM_TICKS;

  /** Y gain per tick that counts as vanilla auto-step doing the climbing for us. */
  private static final double AUTO_STEP_RISE_EPSILON = 1.0e-4;

  private static final int STALL_HOP_TICKS = UNEXPECTED_HOP_ARM_TICKS;

  private static final double NODE_REACH_RADIUS = 0.7;
  // A node more than this far ABOVE the feet has not been climbed yet, however close it is
  // horizontally.
  // Matches auto-step height so walkable steps (<=0.6) never gate; only real risers do.
  private static final double CLIMB_REACH_GATE_Y = 0.6;

  /**
   * Ceiling on the climb pin. A step the body can actually make is one block - auto-step clears 0.6
   * and a jump 1.25 - so a node further overhead than this was never a step at all: it is a node
   * the body has FALLEN away from, which happens whenever a search starts just before a drop and
   * the route lands after it. Without the ceiling the pin latches on that node and the cursor can
   * never advance past it (the pin breaks out ahead of every advance test), so the body targets a
   * point it cannot reach while the ledge and deadzone extensions flip the target two blocks either
   * way at their own boundaries - the walk back-and-forth. Logged 11:16:24: route planned at feet
   * y=115, an 8-tick fall put the body at y=110, and cur stayed 0/4 for five seconds with `go`
   * alternating z=-13.5/-15.5 ~3x a second. Both vertical watches missed it: the oscillation itself
   * keeps clearing the flat progress bar, and the pin's own patience resets on every auto-step
   * onGround blip and every new horizontal minimum.
   */
  private static final double CLIMB_PIN_MAX_RISE = 1.5;

  /** Remaining air ticks from which a combat brake no longer needs a safe braked landing. */
  private static final int COMBAT_BRAKE_FREE_TICKS = 8;

  /** Vanilla's horizontal impulse for a jump taken while sprinting. */
  private static final double SPRINT_JUMP_BOOST = 0.2;

  /** How far past the combat target's centre a hop may still land. */
  private static final double COMBAT_HOP_OVERSHOOT = 0.5;

  private static final int COMBAT_HOP_SIM_TICKS = 60;

  /**
   * Water this deep under the feet floats the body instead of letting it stand. Vanilla's own
   * threshold for "swimming rather than walking" in practice, and the line between a wade and a
   * swim here.
   */
  private static final int SWIM_FLOAT_DEPTH = 2;

  // How long either vertical cursor gate may hold the cursor before the body is declared off-route
  // in Y and
  // a replan is asked for. Sits well clear of a single step-up hop (a dozen ticks at worst) but a
  // full
  // half-second inside PROGRESS_STALL_MS, which is what used to have to notice these.
  static final int VERT_PIN_REPLAN_TICKS = 20;

  /**
   * Blocks of closing on the pinned node that resets the pin's patience. A pin is held for the
   * WHOLE walk up to its node, not just once the body is on it, so counting raw pinned ticks counts
   * ordinary approach as stall - and the drop brakes make that approach slow on purpose (sprint off
   * 6 blocks out, forward released 3 blocks out), which is exactly how a healthy step-off blew a
   * 20-tick budget. Tracked monotonically, so wobble cannot keep buying patience: only real ground
   * gained does.
   */
  private static final double VERT_PIN_CLOSING_EPSILON = 0.05;

  /** A node this far BELOW the feet is a ledge drop rather than a step down. */
  private static final double LEDGE_DROP_MIN_Y = 1.5;

  /**
   * Tallest drop that can be run through on a straight route (under fall damage). See
   * flowsThroughDrop.
   */
  private static final double SHORT_DROP_MAX_Y = 2.5;

  /** Flat reach either side of a landing used to read the route's direction through it. */
  private static final double DROP_RUN_BLOCKS = 1.5;

  /** Cosine (about 35 deg) the route may bend through a landing and still count as straight. */
  private static final double DROP_RUN_COS = 0.82;

  /** How far past the landing node to steer the body while still standing on the lip. */
  private static final double LEDGE_AIM_OVERSHOOT = 2.0;

  /**
   * Horizontal gap under which the body target is projected along the route instead of aimed at the
   * node. Closer than this the player->node vector is sub-block, so its atan2 is dominated by body
   * wobble. Sized just over the reach radius so it only covers nodes the cursor is pinned on.
   */
  private static final double HEADING_DEADZONE = 1.0;

  /** Steepest walking look-down, degrees; water keeps its own pitch, which does steer. */
  private static final float MAX_WALK_LOOK_DOWN = 55f;

  /** Flat reach within which a hot-swapped route's start points may count as already passed. */
  private static final double PASSED_START_RANGE = 4.0;

  /** Ticks between replans asked for by a wall knocking the body off the line. */
  private static final int KNOCK_REPLAN_COOLDOWN_TICKS = 40;

  /**
   * Blocks below the takeoff height at which a free fall stops needing forward drive. Sized so the
   * body has already cleared the lip (~6 ticks of fall, well over the one block moveDescend needs
   * horizontally).
   */
  private static final double FALL_RELEASE_DROP = 1.5;

  private static final double RESYNC_RANGE = 8.0;

  /**
   * Blocks off the route line before the body stops steering at its node and rejoins the route
   * instead. Well under {@link #OFF_PATH_CAP}, so this cheap repair always gets first refusal at a
   * drift the replan watch would otherwise have to answer with a whole new search.
   */
  private static final double REJOIN_OFF_LINE = 0.45;

  /**
   * How far ALONG the route ahead of the body's own projection the rejoin target sits. This is the
   * damping: steer at the line itself and a binary strafe key crosses it, reverses and crosses
   * back; steer at a point a stride further on and the correction converges instead of ringing.
   */
  private static final double REJOIN_LEAD = 3.0;

  /** Nodes ahead the projection is searched over. Bounded so a long route costs nothing. */
  private static final int REJOIN_SCAN_NODES = 12;

  static final int FREE_TICKS_TO_CLEAR = 3;
  private static final int TRACE_EVERY_N = 10;

  /**
   * Ticks a body-target gate must hold its new reading before it is believed. See {@link
   * StickyGate}. Three is under one block of travel at sprint speed, so a real transition is still
   * picked up promptly.
   */
  private static final int GATE_HOLD_TICKS = 3;

  // safety nets carried over from the main driver (replan-on-wedge,
  // strafe-off while airborne, drift-triggered replan)
  // Drift-replan sensitivity: 3D blocks from the nearest point on the planned path, measured at
  // foot
  // height (see nearestPathDistance). The old value carried a rationale that did not hold -
  // Catmull-Rom
  // bulge is part of the TRAIL the distance is measured against, so it contributes no drift at all
  // and
  // never needed clearing. What the cap has to stay under is the clearance the planner actually
  // promises:
  // the simplifier only guarantees ANY_ANGLE_WALL_MARGIN (1 block) of room around the swept body,
  // so
  // tolerating 1.5 let the body leave the corridor that was checked for it. Lower = reroutes sooner
  // when
  // steering loses the path, at the cost of more replan stand-downs (each releases keys and waits
  // for a
  // new route, so too-low stutters); 1.0 keeps a full block of slack for slab/stair Y variance.
  static final double OFF_PATH_CAP = 1.0;
  static final int DRIFT_PATIENCE_TICKS = 5; // sustained off-path ticks before replanning (~0.25s)
  private static final int LANDING_STRAFE_HOLD = 2; // suppress strafe this many ticks after landing

  // Fall steering (see FallPredictor). The planner drops a route STRAIGHT down off a lip; Minecraft
  // keeps
  // the takeoff momentum, so the body lands blocks past the node. These aim the fall instead of
  // only
  // damping it: predict the touchdown each air tick, hold the one key that shrinks the gap.
  /**
   * Predicted landing error worth spending a mid-air key on. Under this the landing is inside the
   * drift watch's own tolerance, and a key would only leave sideways momentum to bleed off on
   * touchdown.
   */
  private static final double FALL_STEER_DEADZONE = 0.4;

  /**
   * Air ticks left below which a correction buys nothing - 0.02 a tick needs time to become a
   * block.
   */
  private static final int FALL_STEER_MIN_TICKS = 4;

  /**
   * Flat blocks a tracked landing may sit from the lip. The native plans a drop as one cell
   * sideways then straight down, so a real landing is a block or two out; further is a different
   * part of the route that merely happens to be lower.
   */
  private static final double FALL_TARGET_MAX_REACH = 2.5;

  // Both brake distances are TICKS of the player's own measured speed, not blocks. Fixed block
  // distances
  // were the bug: they were sized against vanilla sprint (0.28/tick), and this server hands out
  // movement
  // speed well past that - a 14-tick fall was logged covering 3.8 blocks horizontally, which needs
  // about
  // 0.467/tick, roughly 1.7x vanilla. At that speed a 1.25-block brake zone is under three ticks of
  // coasting and the body reaches the lip still moving. Same standing rule as everywhere else in
  // this
  // driver: key off measured velocity, never a vanilla movement constant.
  private static final double SPRINT_BRAKE_TICKS = 10.0;
  private static final double SPRINT_BRAKE_MIN_BLOCKS = 2.0;
  private static final double SPRINT_BRAKE_MAX_BLOCKS = 6.0;

  /**
   * Trail nodes scanned ahead of the cursor for an upcoming drop. Bounded so a lagging cursor
   * cannot make this walk the whole route, and so a drop far down the path never brakes the body
   * here.
   */
  private static final int DROP_SCAN_NODES = 24;

  /**
   * Coast, forward released, for this many ticks of travel before the lip. Ground friction with no
   * input is 0.546 a tick, so four ticks takes any speed down to a crawl and the body leaves the
   * lip near enough straight down - which is what the planner assumed when it put the landing
   * directly below.
   */
  private static final double STEP_OFF_BRAKE_TICKS = 5.0;

  private static final double STEP_OFF_BRAKE_MIN_BLOCKS = 0.9;
  private static final double STEP_OFF_BRAKE_MAX_BLOCKS = 3.0;

  /**
   * Speed under which the brake lets go again, so it brakes into a drop rather than parking on the
   * lip.
   */
  private static final double STEP_OFF_SPEED_FLOOR = 0.06;

  // Live obstruction look-ahead: how far along the committed trail to re-check against the CURRENT
  // world
  // each grounded tick, and how finely to sample it. The four stall watchers above are all reactive
  // - they
  // only fire AFTER the body has wedged against a new block (500ms-3s). This catches a block that
  // GREW across
  // the route (a chopped tree respawning over a gap the path was planned through) one step early
  // and replans
  // before the body hits it. Sample step stays under a 1-wide trunk so a thin obstruction can't
  // slip a gap.
  static final double OBSTRUCT_LOOKAHEAD_BLOCKS = 6.0;

  private static final double ARRIVE_RADIUS = 0.45;

  /** Past this the final node counts as behind the gaze, not merely off to one side of it. */
  private static final float OVERSHOT_GOAL_DEGREES = 90f;

  /** How close a floating body must get to a land node before the cursor retires it. */
  private static final double AFLOAT_REACH = 0.5;

  private static final double AFLOAT_LEVEL_SLACK = 0.25;

  private static final double ARRIVE_MAX_RISE = 0.6;

  /** Vanilla horizontal speed kept per tick on ordinary ground (slipperiness 0.6 x 0.91). */
  private static final double GROUND_DRAG = 0.546;

  /** cos(65 deg): gentler bends keep sprint. */
  static final double OBSTRUCT_SAMPLE_STEP = 0.5;

  // process-wide instance
  public static final PathExecutor instance = new PathExecutor();
  final Minecraft client = Minecraft.getInstance();
  final PathTrailBuilder trailBuilder = new PathTrailBuilder(client);
  private final PathSpatialQueries spatialQueries = new PathSpatialQueries(client);
  private final PathMovementController movementController =
      new PathMovementController(client, spatialQueries);
  private final PathHopTerrain hopTerrain = new PathHopTerrain(client);
  private final PathLaneKeeper laneKeeper = new PathLaneKeeper(client, spatialQueries, hopTerrain);
  final PathGazePlanner gazePlanner = new PathGazePlanner(client, spatialQueries);
  private final PathRotationController rotationController =
      new PathRotationController(PathLook.of(client));
  private final TransmissionPathExecutor transmission = new TransmissionPathExecutor(client);
  private final PathExecutorTrace trace = new PathExecutorTrace(client);
  private final WaterMovementController waterController = new WaterMovementController();

  // active route + cursor
  private final List<PathNode> turnNodes = new ArrayList<>();
  final List<Vec3> trail = new ArrayList<>();
  private List<Vec3> routeFootPoints = List.of();
  private final List<PathNode.Medium> trailMedia = new ArrayList<>();
  private final List<Integer> turnLaneMarks = new ArrayList<>();
  private final List<Integer> gazeMarks = new ArrayList<>();
  int trailCursor = 0;

  /** In water now, or off the ground ever since leaving it. Standing on dry ground clears it. */
  private boolean overWater;

  private int loggedKeyMask = -1;

  private BlockPos requestedGoal = null;

  // Plan-time corridor baseline for the obstruction watch: every body-cell that was already solid
  // along the
  // route when it was set. The native planner certified the route, so these are terrain it
  // deliberately
  // passes (step risers, slabs, overhangs) - the watch ignores them and fires only on a cell that
  // turns
  // solid LATER. Rebuilt on every setPath; empty + not-ready stands the watch down (can't tell
  // terrain apart).
  final java.util.HashSet<Long> plannedCorridorBlocks = new java.util.HashSet<>();
  // Chunks that were LOADED when the baseline was taken. An unloaded chunk reads as air, so its
  // cells can't be
  // baselined honestly - the watch stands down over any stretch whose chunk was unloaded at plan
  // time (a long
  // route's far end) rather than flag its blocks as "new" the moment it streams in under the
  // player.
  final java.util.HashSet<Long> plannedLoadedChunks = new java.util.HashSet<>();
  boolean corridorBaselineReady = false;
  // The stretch of the route planned from the DISK cache (chunks unloaded at plan time). Checked
  // against the
  // live world as each chunk streams in; see UnseenChunkWatch.
  final UnseenChunkWatch unseenChunks = new UnseenChunkWatch();

  boolean bailed = false;
  boolean won = false;

  // wedge / hop clocks
  final Clock wedgeClock = new Clock();
  final Clock hopClock = new Clock();

  // XZ-progress anchor (jump-boost-proof stuck watch)
  final Clock progressClock = new Clock();
  double progressAnchorX = 0.0;
  double progressAnchorZ = 0.0;
  // Consecutive no-progress replans that produced a route from the SAME spot. See
  // tickProgressWatch.
  int stuckReplans = 0;
  int stuckReplansHere = 0;
  BlockPos stuckReplanCell = null;

  // hop bookkeeping
  int hopTries = 0;
  double hopBaseY = 0.0;
  double hopBaseX;
  double hopBaseZ;

  /** A hop counts as done once it lands this much higher, or this far from where it started. */
  static final double HOP_LANDED_RISE = 0.4;

  static final double HOP_LANDED_TRAVEL = 0.6;
  int hopCooldown = 0;

  // wedge counters
  int movingStreak = 0;
  private int wallGrindTicks = 0;
  private int wallStallTicks = 0;
  private int knockReplanCooldown = 0;

  /** Y at the end of last tick, to tell an auto-step climb apart from grinding a wall. */
  private double previousY = Double.NaN;

  // air-phase for strafe gating + drift replan
  int airTicks = 0;

  /** Y of the last grounded tick - the lip a fall started from. */
  private double takeoffY = 0.0;

  /**
   * Whether this airborne phase began with a jump press (a hop) rather than walking off an edge.
   */
  private boolean airFromJump = false;

  private boolean landingNeedsStrafeHold = true;

  private int landTicks = 3;

  /**
   * Where the planner meant the current fall to land, snapshotted at takeoff. Taken once, because
   * the cursor advances PAST the landing node mid-fall - and an overshooting fall is exactly the
   * one that skips it, so re-deriving this in the air would chase the error instead of measuring
   * it.
   */
  private Vec3 fallTarget = null;

  /** Last steer asked for, so the fall log records decisions rather than one line per tick. */
  private FallPredictor.Steer lastFallSteer = FallPredictor.Steer.NONE;

  private FallPredictor.Steer lastCombatAirSteer = FallPredictor.Steer.NONE;
  private int combatAirTarget = -1;
  private boolean combatHopHeld;

  private final WalkingPolicy.DropBrake dropBrake = new WalkingPolicy.DropBrake();

  int driftStreak = 0;
  private int traceCounter = 0;

  private boolean running = false;
  private boolean waitingForJumpReplan;
  private final RimRecoveryState rimRecovery = new RimRecoveryState();

  /**
   * Support cell whose escape search came back empty, so the "no escape" line logs once per rim,
   * not per tick.
   */
  private BlockPos rimEscapeFailedAt = null;

  private State phase = State.STARTING_PATH;
  private boolean initialFacingReady;

  /** Walk or hop; picked at start and changed mid-route only with the feet on the ground. */
  private GroundMovementMode movementMode = WalkMovementMode.INSTANCE;

  /** Hold crouch for the whole route (and never sprint). Callers re-assert it every tick. */
  @Setter private boolean sneak;

  /**
   * World yaw toward the node the route is currently walking at, published for whoever owns the
   * head. A macro tracking a mob mid-route needs this: with the head off the path the key set
   * becomes BODY-relative, so a node behind the current facing resolves to S and the bot walks a
   * route backwards. Nobody paths backwards - S is a correction key. Reading this lets the head
   * owner hand the head back before that happens, so the body turns and travels forward like a
   * person would. {@code Float.NaN} when not running.
   */
  private float routeHeadingYaw = Float.NaN;

  private double livePeek = NEAR_NODE_PEEK;
  private double liveReach = NODE_REACH_RADIUS;
  int vertPinTicks = 0; // consecutive ticks a vertical gate has held the cursor on one node

  /**
   * True while the DROP half of the vertical cursor pin is holding. The pin is raw (it must engage
   * on the first tick or the cursor retires a descent from the lip), but its partner ledge
   * extension is debounced - so without this the two disagree for GATE_HOLD_TICKS every time
   * onGround blips, and a pinned cursor aims straight down at its own node: yaw noise, heading
   * flips, the shuffle. Pin and extension are one mechanism (see stepCursorForward); this is the
   * wire between them.
   */
  boolean dropPinned = false;

  /**
   * Closest the body has been to the currently pinned node, in flat blocks. Ratchets down while the
   * pin holds; resets when the pin lifts. See {@link #VERT_PIN_CLOSING_EPSILON}.
   */
  double vertPinGap = Double.MAX_VALUE;

  // Debounced versions of the three gates that PICK THE BODY TARGET. Read clean each tick they
  // chatter
  // against geometry (see StickyGate), and every flip jumps the target 2 blocks or onto another
  // node -
  // which crosses a key cone and rewrites the whole key set. That is the corner shuffle.
  private final StickyGate peekGate = new StickyGate(GATE_HOLD_TICKS, false);
  private final StickyGate ledgeGate = new StickyGate(GATE_HOLD_TICKS, false);
  private final StickyGate deadzoneGate = new StickyGate(GATE_HOLD_TICKS, false);

  private final PathStallWatch stallWatch = new PathStallWatch(this);
  private final PathCorridorWatch corridorWatch = new PathCorridorWatch(this);

  private PathExecutor() {}

  /** Stop any live route, then set the goal, install {@code nodes}, and start following them. */
  public void startPath(List<BlockPos> nodes, BlockPos goal) {
    startPrecisePath(centeredNodes(nodes), goal);
  }

  public void startPrecisePath(List<PathNode> nodes, BlockPos goal) {
    transmission.beginRoute(goal, running);
    stop();
    if (nodes == null || nodes.isEmpty()) {
      return;
    }
    if (goal != null) {
      setGoal(goal);
    }
    setPath(nodes);
    start();
    PathTelemetry.route(client, nodes, trail, turnLaneMarks, goal, false);
  }

  public void hotSwapPrecisePath(List<PathNode> nodes, BlockPos goal) {
    if (!isActive()) {
      startPrecisePath(nodes, goal);
      return;
    }
    if (nodes == null || nodes.isEmpty()) {
      return;
    }
    if (goal != null) {
      setGoal(goal);
    }
    transmission.beginRoute(goal, true);
    setPath(nodes); // live swap: keeps running + keys held, just retargets the trail
    skipPassedStart();
    PathTelemetry.route(client, nodes, trail, turnLaneMarks, goal, true);
  }

  /**
   * A replan is searched from where the body stood when it was asked for, and at speed the body is
   * a few blocks on by the time it lands. Left on that start, the cursor sat behind the body and
   * the bot turned round to walk back to it before carrying on the way it was already going. Moves
   * the cursor to the first nearby trail point ahead of the body's motion, if the body can walk
   * straight to it.
   */
  private void skipPassedStart() {
    if (client.player == null || trail.size() < 2) {
      return;
    }
    Vec3 velocity = client.player.getDeltaMovement();
    double speed = velocity.horizontalDistance();
    if (speed < CRAWL_SPEED_FLOOR) {
      return;
    }
    double x = client.player.getX();
    double z = client.player.getZ();
    for (int i = 0; i < trail.size() - 1; i++) {
      Vec3 point = trail.get(i);
      if (flatDist(x, z, point.x, point.z) > PASSED_START_RANGE) {
        return;
      }
      if ((point.x - x) * velocity.x + (point.z - z) * velocity.z >= 0.0) {
        if (i > 0 && hopTerrain.bodyLineWalkable(point.x, point.z)) {
          detail("hot swap: skipped %d start points the body had already passed", i);
          trailCursor = i;
        }
        return;
      }
    }
  }

  /**
   * The bearing to the macro's gaze hint, or null when there is none or it lies too far off the
   * route heading for the body to keep walking the route while looking at it.
   */
  private Angle gazeHintAim(float routeYaw) {
    Vec3 focus = PathGazeHint.focus();
    if (focus == null || client.player == null) {
      return null;
    }
    Angle aim = AngleUtil.getRotation(client.player.getEyePosition(1.0f), focus);
    return Math.abs(AngleUtil.normalizeAngle(aim.getYaw() - routeYaw)) <= GAZE_HINT_MAX_DEGREES
        ? aim
        : null;
  }

  // view interpolation hooks (the mixin pulls these)

  public boolean isInterpolatingRotation() {
    return rotationController.isInterpolating(running);
  }

  /** Checks a direct approach with the same footprint, floor and hazard rules as route walking. */
  public boolean canWalkDirectlyTo(double x, double z) {
    return hopTerrain.safeWalkingLine(x, z);
  }

  public void onClientTick(Minecraft client) {
    onTick();
    PathTelemetry.tick(client, running, won, bailed);
  }

  // outward-facing entry points

  /** Stash the precise requested destination so EXACT goal mode knows when it has landed. */
  private void setGoal(BlockPos goal) {
    this.requestedGoal = goal;
  }

  /** Swap in a new set of turn nodes and begin following them. */
  private void setPath(List<PathNode> nodes) {
    waitingForJumpReplan = false;
    dropBrake.reset();
    turnNodes.clear();
    turnNodes.addAll(nodes);
    rotationController.onPathSet();
    buildTrail();
    transmission.reset();
    corridorWatch
        .snapshotCorridorBaseline(); // record the terrain this route passes so the obstruction
    // watch
    // only fires on NEW blocks
    trailCursor = 0;
    stallWatch.clearStallWatches(); // fresh route -> fresh stall windows (covers hot-swaps too)
    if (rimRecovery.isWaitingForRoute()) {
      rimRecovery.clear();
    }
    // A hot swap keeps the driver running, so the gates would otherwise carry the old route's
    // readings
    // onto the new one and steer the first few ticks off a target that no longer exists.
    peekGate.reset(false);
    ledgeGate.reset(false);
    deadzoneGate.reset(false);
    movementController.reset();
    waterController.reset();
    log("Route loaded: %d turns, %d steps", nodes.size(), trail.size());
  }

  private void start() {
    phase = State.STARTING_PATH;
    initialFacingReady = false;
    movementMode = GroundMovementMode.forSetting(AutomaticHop.enabled(client));
    running = true;
    log("Walking the route (%s)", movementMode.label());
  }

  public void stop() {
    if (running) { // redundant stops on an already-halted driver used to spam this line
      log(
          "Stopped walking at step %d/%d (%s%s)",
          trailCursor, trail.size(), phase, bailed ? ", gave up" : "");
    }

    running = false;
    waitingForJumpReplan = false;
    initialFacingReady = false;
    rimRecovery.clear();
    rimEscapeFailedAt = null;
    bailed = false;
    routeHeadingYaw = Float.NaN;
    turnNodes.clear();
    trail.clear();
    routeFootPoints = List.of();
    trailMedia.clear();
    turnLaneMarks.clear();
    gazeMarks.clear();
    plannedCorridorBlocks.clear();
    plannedLoadedChunks.clear();
    unseenChunks.clear();
    corridorBaselineReady = false;
    trailCursor = 0;
    requestedGoal = null;
    phase = State.END;
    hopTries = 0;
    hopBaseY = 0.0;
    hopClock.reset();
    progressClock.reset();
    hopCooldown = 0;
    movingStreak = 0;
    wallGrindTicks = 0;
    wallStallTicks = 0;
    knockReplanCooldown = 0;
    waterController.reset();
    stallWatch.clearStuckReplans();
    airTicks = 0;
    lastCombatAirSteer = FallPredictor.Steer.NONE;
    combatAirTarget = -1;
    landTicks = 3;
    landingNeedsStrafeHold = true;
    fallTarget = null;
    dropBrake.reset();
    driftStreak = 0;
    peekGate.reset(false);
    ledgeGate.reset(false);
    deadzoneGate.reset(false);
    movementController.reset();
    laneKeeper.reset();
    rotationController.reset();
    gazePlanner.reset();
    transmission.reset();
    KeyBindUtil.releaseAllExcept();
  }

  public boolean isEnabled() {
    return running;
  }

  public boolean isActive() {
    return isEnabled();
  }

  public boolean failed() {
    return !running && bailed;
  }

  public int getWaypointIndex() {
    return trailCursor;
  }

  public int getPathSize() {
    return trail.size();
  }

  public List<Vec3> getRouteFootPoints() {
    return routeFootPoints;
  }

  /** See {@link #routeHeadingYaw}. {@code Float.NaN} when no route is driving. */
  public float getRouteHeadingYaw() {
    return running ? routeHeadingYaw : Float.NaN;
  }

  /**
   * Heading of the route CHORD the head is looking down - cursor node to the node the string pull
   * settled on. {@code Float.NaN} with no route, or on a tick the pull did not choose one.
   *
   * <p>For macros deciding whether to hold the head off the path. The route heading is a bearing
   * from the BODY to the single node the cursor sits on, so it carries body wobble and swings the
   * whole way through a corner; a gate measured against it inherits that as take/release chatter.
   * This is two fixed points on the route, so it holds perfectly still until the pull moves - which
   * is what those gates mean by "the way we are going".
   */
  public float getLookPathHeadingYaw() {
    int gazeNode = gazePlanner.gazeNodeIndex();
    if (!running || gazeNode <= trailCursor || gazeNode >= trail.size()) {
      return Float.NaN;
    }
    Vec3 from = trail.get(trailCursor);
    Vec3 to = trail.get(gazeNode);
    if (flatDist(from.x, from.z, to.x, to.z) < 0.5) {
      return Float.NaN;
    }
    return AngleUtil.getRotationYaw(new Vec3(from.x, 0, from.z), new Vec3(to.x, 0, to.z));
  }

  public Vec3 getCurrentMovementTarget() {
    if (trailCursor >= 0 && trailCursor < trail.size()) {
      return trail.get(trailCursor);
    }
    return null;
  }

  // per-tick driver

  public boolean onTick() {
    if (!running) {
      return false;
    }
    if (client.player == null || client.level == null) {
      return false;
    }

    if (waitingForJumpReplan) {
      routeHeadingYaw = Float.NaN;
      KeyBindUtil.releaseAllExcept();
      if (WalkingNavigation.isSearchPending()) {
        return true;
      }
      stop();
      bailed = true;
      won = false;
      return false;
    }

    if (rimRecovery.isWaitingForRoute()) {
      if (WalkingNavigation.isSearchPending()) {
        routeHeadingYaw = Float.NaN;
        KeyBindUtil.releaseAllExcept();
        return true;
      }
      log("Stuck on a ledge with no way off - dropping the route");
      abortRimRecovery();
      return false;
    }

    if (tickRimRecovery()) {
      return true;
    }

    refreshMovementMode();
    refreshSpeedProfile(); // refresh turn/lookahead/advance + sprint from the live speed preset
    if (replanUnavailableJump()) {
      routeHeadingYaw = Float.NaN;
      KeyBindUtil.releaseAllExcept();
      return false;
    }

    int transmissionCursor = transmission.observe(trail, trailCursor);
    if (transmissionCursor >= 0) {
      trailCursor = Math.max(trailCursor, transmissionCursor);
      phase = State.TRAVERSING;
      wedgeClock.reset();
      progressClock.reset();
      driftStreak = 0;
      movingStreak = 0;
      wallGrindTicks = 0;
      wallStallTicks = 0;
      stallWatch.clearHopFailsafe();
    }

    if (!turnNodes.isEmpty()) {
      stepCursorForward();
      resnapCursor();
    } else {
      if (wedgeClock.isScheduled()) {
        wedgeClock.reset();
      }
      KeyBindUtil.releaseAllExcept();
      return true;
    }

    if (trailCursor >= trail.size()) {
      log("Arrived - route finished (%d steps)", trail.size());
      PathTelemetry.arrival();
      won = true;
      bailed = false;
      phase = State.END;
      running = false;
      transmission.reset();
      WalkingNavigation.notifyArrived();
      KeyBindUtil.releaseAllExcept();
      return false;
    }

    final Vec3 liveNode = trail.get(trailCursor);
    boolean onGround = client.player.onGround();
    boolean inWater = client.player.isInWater();
    PathNode.Medium liveMedium = mediumAt(trailCursor);
    boolean plannedWater =
        liveMedium != PathNode.Medium.LAND
            || inWater && mediumAt(trailCursor - 1) != PathNode.Medium.LAND;
    int liveDepth =
        WaterPathQueries.depthAt(
            client.level, client.player.getX(), client.player.getY(), client.player.getZ());
    // Floating is a MEASUREMENT, not a label. A cell with a solid floor under two blocks of water
    // is an
    // ordinary walkable node to the planner, so it arrives tagged WADE - but vanilla floats the
    // body in
    // it, and the swim handling (the only thing that holds jump, and so the only thing that can
    // lift the
    // body over a bank) used to run on SWIM_SURFACE alone. That is how a "wade" through 2-deep
    // water bobs
    // at a 1-block bank forever: the route wants feet on the floor, the body sits a block above it,
    // and
    // nothing in the controller is allowed to swim. Depth decides now, on either side of a
    // transition.
    boolean plannedSwim = plannedWater && liveDepth >= SWIM_FLOAT_DEPTH;
    boolean upcomingLand = inWater && hasLandAhead(2);
    WaterMovementController.State previousWaterState = waterController.state();
    WaterMovementController.Decision waterDecision =
        waterController.tick(
            new WaterMovementController.Input(
                inWater,
                client.player.isUnderWater(),
                client.player.isSwimming(),
                plannedWater,
                plannedSwim,
                upcomingLand,
                RyznBootstrap.rotationManager().externalOwnsLook(),
                client.player.getAirSupply() <= Math.max(1, client.player.getMaxAirSupply() / 3),
                Math.hypot(client.player.getDeltaMovement().x, client.player.getDeltaMovement().z),
                client.player.getDeltaMovement().y));
    if (waterDecision.state() != previousWaterState) {
      detail(
          "[WATER] %s -> %s medium=%s depth=%d planSwim=%b swim=%b air=%d/%d",
          previousWaterState,
          waterDecision.state(),
          liveMedium,
          liveDepth,
          plannedSwim,
          client.player.isSwimming(),
          client.player.getAirSupply(),
          client.player.getMaxAirSupply());
      // Deliberately does NOT re-anchor the progress watch. A body bobbing at a bank flips
      // DRY <-> EXITING about twice a second, and re-anchoring on every flip meant the one watchdog
      // that can rescue a water stall never matured - logged at 40+ seconds parked in a puddle.
    }
    tickAirState(onGround || inWater);
    if (hopCooldown > 0) {
      hopCooldown--;
    }

    // Sustained side-drift guard: replan when the body is pushed well off the live
    // segment for a while on the ground. Held off mid-hop, since the arc naturally leaves the
    // segment.
    if (!waterDecision.controlled()
        && !transmission.suppressesWatchdogs()
        && stallWatch.tickDriftWatch()) {
      return false;
    }

    var walking = RyznSettings.get().pathfinder().walking();
    double flatGapTgt =
        flatDist(client.player.getX(), client.player.getZ(), liveNode.x, liveNode.z);

    Vec3 aimNode = liveNode;
    int aimIndex = trailCursor;
    int previewIndex = trailCursor;
    if (onGround
        && !dropPinned
        && !gazePlanner.isCornerNode(turnLaneMarks, trailCursor)
        && mediumAt(trailCursor) == PathNode.Medium.LAND
        && Math.abs(trailFootY(trailCursor) - client.player.getY()) < 0.30) {
      double reach = walking.get(Control.PREVIEW);
      if (walking.enabled(Control.SPEED_PREVIEW)) {
        reach =
            Math.clamp(
                reach * .65 + client.player.getDeltaMovement().horizontalDistance() * 7, 1, 10);
      }
      double distance = flatGapTgt;
      for (int candidate = trailCursor + 1;
          candidate < Math.min(trail.size() - 1, trailCursor + 9);
          candidate++) {
        Vec3 next = trail.get(candidate);
        Vec3 previous = trail.get(candidate - 1);
        distance += Math.hypot(next.x - previous.x, next.z - previous.z);
        if (distance > reach
            || mediumAt(candidate) != PathNode.Medium.LAND
            || Math.abs(trailFootY(candidate) - client.player.getY()) >= 0.30
            || gazePlanner.isCornerNode(turnLaneMarks, candidate)
            || !spatialQueries.forwardLaneOpen(next)
            || !hopTerrain.safeWalkingLine(next.x, next.z)) {
          break;
        }
        previewIndex = candidate;
      }
    }
    if (peekGate.update(previewIndex != trailCursor) && previewIndex != trailCursor) {
      aimIndex = previewIndex;
      aimNode = trail.get(aimIndex);
    }

    // EXACT goal mode: the final node may be a wall-inset center sitting
    // as much as ~0.5 blocks off the asked-for coordinate, and the search can stop
    // one node shy of it. Until the player's blockpos equals
    // requestedGoal, steer the last node toward the goal block center
    // so the player covers the final sliver onto it. stepCursorForward()
    // ends the route on that same blockpos match.
    if (requestedGoal != null
        && PathfinderConfig.goalMode == PathfinderConfig.GoalMode.EXACT
        && trailCursor == trail.size() - 1) {
      BlockPos pp = client.player.blockPosition();
      if (pp.getX() != requestedGoal.getX() || pp.getZ() != requestedGoal.getZ()) {
        aimNode =
            new Vec3(requestedGoal.getX() + 0.5, requestedGoal.getY(), requestedGoal.getZ() + 0.5);
      }
    }

    double aimX = aimNode.x;
    double aimZ = aimNode.z;

    // Ledge drop: steer PAST the landing node instead of at it. moveDescend puts the landing about
    // one
    // block beyond the lip and several below, so from the edge the vector to it is nearly vertical
    // - a
    // near-zero horizontal heading whose yaw is noise, and which flips sign the instant the body
    // crosses
    // the node's XZ. That is what makes it shuffle along the lip at speed instead of stepping off.
    // The push direction comes from the ROUTE (previous node -> landing), not from the body, so it
    // stays
    // stable however close the body gets. Only the BODY heading is extended; the gaze below still
    // gets the
    // real aimNode, so the head keeps looking at the landing. Nothing is pinned, so unlike the
    // reverted
    // cursor gate this cannot deadlock - the cursor advances normally the whole time.
    //
    // Debounced: onGround flips every few ticks while auto-step bounces the feet on a lip (10% of
    // all
    // block steps in the logs are a same-XZ Y change), and each flip moves the target a full 2
    // blocks.
    boolean ledgeRaw =
        onGround && aimIndex > 0 && trailFootY(aimIndex) < client.player.getY() - LEDGE_DROP_MIN_Y;
    // Update unconditionally to keep the gate fed, then OR in the pin: a pinned cursor is not going
    // to
    // another node, so the only choice left is at-node (noisy sub-block yaw) vs two-blocks-past.
    // There is
    // nothing to debounce between those, and at-node is the flip the pin's own comment says was
    // reverted.
    boolean ledgeOn = ledgeGate.update(ledgeRaw) | dropPinned;
    if (ledgeOn && aimIndex > 0) {
      Vec3 fromNode = trail.get(aimIndex - 1);
      double dirX = aimNode.x - fromNode.x;
      double dirZ = aimNode.z - fromNode.z;
      double dirLen = Math.hypot(dirX, dirZ);
      if (dirLen > 1.0E-3) {
        aimX += dirX / dirLen * LEDGE_AIM_OVERSHOOT;
        aimZ += dirZ / dirLen * LEDGE_AIM_OVERSHOOT;
      }
    }

    // Corner rejoin. Coming out of a sharp turn the body carries its old momentum wide of the line,
    // and steering at the node then walks it BACK onto a line it has already left - with a binary
    // strafe key and no damping term, so it crosses, over-corrects and crosses back. That is the
    // sway.
    //
    // Neither existing answer fits: under OFF_PATH_CAP nothing happens at all, and over it the
    // drift
    // watch throws away the whole route for a fresh search to the goal. The route is not wrong here
    // -
    // only the body's entry onto it is stale - so pick the target back up further along it and
    // carry
    // on. Costs no search and no replan budget.
    //
    // Skipped while a ledge or deadzone extension already owns the target (those exist for headings
    // this cannot improve), and whenever the body is off the ground, where there is no steering to
    // do.
    if (onGround && !ledgeOn && !deadzoneGate.get() && !waterDecision.controlled()) {
      Vec3 rejoin = rejoinTarget(aimX, aimZ);
      if (rejoin != null) {
        aimX = rejoin.x;
        aimZ = rejoin.z;
      }
    }

    // Near-node heading deadzone. Standing on top of the target makes the vector to it sub-block,
    // so its
    // atan2 is noise: the body heading flips up to 180 deg per tick, the key selector chatters
    // every
    // direction key at once, and the body shuffles between two blocks until a failsafe replans it
    // out
    // (in the log: moveErr 133->168 with lookErr ~2, cursor stuck at 0/184, ended by the 1500ms
    // progress
    // watch). Steer along the ROUTE through the node instead - an outgoing direction that holds
    // steady
    // however close the body gets. Same mechanism as the ledge extension above, which cannot cover
    // this:
    // it needs aimIndex > 0 and so is skipped at cursor 0, where the drop pin still fires. Measured
    // after
    // the ledge push, so where that already applies the gap is wide and this is a no-op. The last
    // node is
    // exempt - there is no outgoing direction, and arrival retires it inside the reach radius
    // anyway.
    //
    // Debounced for the same reason as the ledge push above: inching against a wall walks the gap
    // back
    // and forth across HEADING_DEADZONE, and the 2-block jump each way swings the bearing ~100
    // degrees
    // with the head perfectly still. This is the flip the log caught (moveErr 45.6 -> 146.5 at one
    // yaw).
    boolean deadzoneRaw =
        MovementGate.shouldProjectHeading(
            flatDist(client.player.getX(), client.player.getZ(), aimX, aimZ),
            aimIndex,
            trail.size(),
            HEADING_DEADZONE);
    if (deadzoneGate.update(deadzoneRaw) && aimIndex + 1 < trail.size()) {
      Vec3 nextNode = trail.get(aimIndex + 1);
      double dirX = nextNode.x - aimNode.x;
      double dirZ = nextNode.z - aimNode.z;
      double dirLen = Math.hypot(dirX, dirZ);
      if (dirLen > 1.0E-3) {
        aimX += dirX / dirLen * LEDGE_AIM_OVERSHOOT;
        aimZ += dirZ / dirLen * LEDGE_AIM_OVERSHOOT;
      }
    }

    // Corner rejoin. Coming out of a sharp turn the body carries its old momentum wide of the line,
    // and steering at the node then walks it BACK onto a line it has already left - with a binary
    // strafe key and no damping term, so it crosses, over-corrects and crosses back. That is the
    // sway.
    //
    // Neither existing answer fits: under OFF_PATH_CAP nothing happens at all, and over it the
    // drift
    // watch throws away the whole route for a fresh search to the goal. The route is not wrong here
    // -
    // only the body's entry onto it is stale - so pick the target back up further along it and
    // carry
    // on. Costs no search and no replan budget.
    //
    // Skipped while a ledge or deadzone extension already owns the target (those exist for headings
    // this cannot improve), and whenever the body is off the ground, where there is no steering to
    // do.
    if (onGround && !ledgeOn && !deadzoneGate.get() && !waterDecision.controlled()) {
      Vec3 rejoin = rejoinTarget(aimX, aimZ);
      if (rejoin != null) {
        aimX = rejoin.x;
        aimZ = rejoin.z;
      }
    }

    Vec3 selfPos3 = new Vec3(client.player.getX(), client.player.getY(), client.player.getZ());
    float faceYaw = AngleUtil.getRotationYaw360(selfPos3, new Vec3(aimX, 0.0, aimZ));
    routeHeadingYaw = faceYaw;
    float faceGap = Math.abs(AngleUtil.normalizeAngle(faceYaw - client.player.getYRot()));
    initialFacingReady =
        MovementGate.initialFacingReady(initialFacingReady, faceGap, HOP_FACING_GATE_DEG);
    double flatGapMove = flatDist(client.player.getX(), client.player.getZ(), aimX, aimZ);

    // Advanced unconditionally, NOT inside the mayOwnLook branch below. A macro holding the head
    // still
    // walks this route, and macros gate their own hold on the route heading (see RouteGaze), so the
    // pull
    // has to keep tracking the body the whole time it is held. Left in the branch it froze the
    // moment a
    // macro took the head and the heading it publishes went stale for the length of the hold.
    PathGazePlanner.Context gazeContext =
        new PathGazePlanner.Context(
            trail,
            this::trailFootY,
            trailCursor,
            airTicks,
            faceYaw,
            gazeMarks,
            airTicks > 0
                && !airFromJump
                && fallTarget == null
                && takeoffY - trailFootY(trailCursor) <= CLIMB_REACH_GATE_Y
                && client.player.getDeltaMovement().y < 0.0);
    gazePlanner.track(gazeContext);

    boolean mayOwnLook = !RyznBootstrap.rotationManager().externalOwnsLook();
    if (waterDecision.controlled()) {
      transmission.reset();
    }
    Angle transmissionAim =
        waterDecision.controlled()
            ? null
            : transmission.update(trail, trailCursor, this::trailFootY, faceYaw, mayOwnLook);
    boolean transmissionPreview = transmissionAim != null && transmission.isPreviewAim();
    Angle hintAim =
        waterDecision.controlled() || transmissionAim != null && !transmissionPreview
            ? null
            : gazeHintAim(faceYaw);
    boolean preciseLook = transmissionAim != null && !transmissionPreview || wantsPreciseAim();
    // Head-tracking yields the head: the executor stops writing yaw
    // (combat and the Chillshot hunt aim at their mob) but keeps driving the body toward the path
    // goal via
    // the strafe net below, so the bot pathfinds and tracks the mob at the same time.
    if (mayOwnLook) {
      rotationController.resumeAfterExternalLook();
      if (!waterDecision.controlled() && transmissionAim != null && !transmissionPreview) {
        rotationController.aimExact(transmissionAim, flatGapMove);
      } else {
        Angle routeLook = gazePlanner.angleFor(gazeContext, aimNode, preciseLook);
        // At a lip the gaze node is the landing straight below, and the head pitched 85 deg down
        // to it. The recorded person looked at most 34-51 deg down before the same 20-block drop
        // and 57 while falling. Walking pitch steers nothing, so cap it there.
        if (routeLook.getPitch() > MAX_WALK_LOOK_DOWN) {
          routeLook = new Angle(routeLook.getYaw(), MAX_WALK_LOOK_DOWN);
        }
        // Overshot the last node: the gaze keeps the heading it arrived on, the node is now behind
        // the body, and the backstep cap drops the only key that points at it - so it stood there
        // with no keys until something moved it. Turn the head round to the node instead.
        if (aimIndex == trail.size() - 1
            && Math.abs(AngleUtil.normalizeAngle(faceYaw - routeLook.getYaw()))
                > OVERSHOT_GOAL_DEGREES) {
          routeLook = new Angle(faceYaw, routeLook.getPitch());
        }
        // The hint only turns the head toward the macro's target. Its pitch stays the route's:
        // the hint drops in and out as small turns swing the target across the bearing window,
        // and taking its pitch nodded the crosshair down at the mob and back up on every one.
        Angle lookAngle =
            waterDecision.controlled()
                ? new Angle(faceYaw, waterDecision.pitch())
                : hintAim != null
                    ? new Angle(hintAim.getYaw(), routeLook.getPitch())
                    : transmissionPreview ? transmissionAim : routeLook;
        CombatModule combat = RyznBootstrap.moduleManager().getByClass(CombatModule.class);
        if (airTicks > 0
            && !waterDecision.controlled()
            && !preciseLook
            && combat.isCombatNavActive()
            && combat.usesRecordedMovement()
            && combat.getTarget() != null
            && combat.getTarget().isAlive()) {
          lookAngle = new Angle(lookAngle.getYaw(), client.player.getXRot());
        }
        rotationController.setAirbornePitchHold(airFromJump);
        rotationController.aimAt(
            lookAngle,
            flatGapMove,
            preciseLook,
            airTicks,
            gazePlanner.isVerticalGazeOpen(),
            onGround
                && !preciseLook
                && !dropPinned
                && !ledgeOn
                && hintAim == null
                && transmissionAim == null
                && !waterDecision.controlled()
                && !gazePlanner.trailClimbsAhead(trail, trailCursor));
      }
    } else {
      rotationController.yieldToExternalLook();
    }

    float aimGap =
        Math.abs(AngleUtil.normalizeAngle(rotationController.aimYaw() - client.player.getYRot()));
    // The head steps node to node, so its lag behind a fresh node is not a reason to stop or drop
    // sprint while the body already faces its route heading.
    float moveGap = Math.min(aimGap, faceGap);

    Vec3 selfPos = new Vec3(client.player.getX(), client.player.getY(), client.player.getZ());
    Vec3 goalPos = new Vec3(aimX, client.player.getY(), aimZ);

    boolean recordedWalk =
        !preciseLook
            && mayOwnLook
            && hintAim == null
            && transmissionAim == null
            && onGround
            && !ledgeOn
            && !deadzoneGate.get()
            && !waterDecision.controlled()
            && !holdStrafeOff()
            && fallTarget == null
            && !sneak;
    List<KeyMapping> keys =
        movementController.select(selfPos, goalPos, moveGap, flatGapMove, sneak, recordedWalk);
    if (onGround
        && !ledgeOn
        && !deadzoneGate.get()
        && !waterDecision.controlled()
        && !holdStrafeOff()
        && fallTarget == null) {
      laneKeeper.apply(keys, trail, trailCursor, recordedWalk);
    } else {
      laneKeeper.reset();
    }

    CombatModule movingCombat = RyznBootstrap.moduleManager().getByClass(CombatModule.class);
    boolean mobOwnedRouteLook =
        movingCombat != null
            && movingCombat.isCombatNavActive()
            && movingCombat.usesRecordedMovement()
            && movingCombat.ownsLook();
    // Recorded route hops retain directional diagonals over a wide, supported landing corridor.
    // Other air/landing movement keeps one key, with forward winning over lateral input.
    boolean recordedAirDiagonal =
        airTicks > 0
            && airFromJump
            && (mayOwnLook || mobOwnedRouteLook)
            && !preciseLook
            && hintAim == null
            && transmissionAim == null
            && !ledgeOn
            && !deadzoneGate.get()
            && !waterDecision.controlled()
            && fallTarget == null
            && !sneak
            && !client.player.onClimbable()
            && !isPlannedHighLanding(trailCursor)
            && keys.contains(client.options.keyUp)
            && !keys.contains(client.options.keyDown)
            && (keys.contains(client.options.keyLeft) != keys.contains(client.options.keyRight))
            && canKeepRecordedAirStrafe(aimX, aimZ, aimIndex);
    if (holdStrafeOff() && !waterDecision.steerByKeys() && !recordedAirDiagonal) {
      boolean forwardHeld = keys.contains(client.options.keyUp);
      boolean leftHeld = keys.contains(client.options.keyLeft);
      boolean rightHeld = keys.contains(client.options.keyRight);
      keys.remove(client.options.keyLeft);
      keys.remove(client.options.keyRight);
      if (MovementGate.airborneKeepsStrafe(forwardHeld, leftHeld || rightHeld)) {
        keys.add(leftHeld ? client.options.keyLeft : client.options.keyRight);
      }
    }
    // A TRACKED fall is steered from its very first air tick. fallingFreely() waits until the body
    // is
    // FALL_RELEASE_DROP (1.5) below the lip, which is about five ticks of a fall - five ticks of
    // forward
    // AND sprint still driving the body, right at the start where the air time left is greatest and
    // a
    // correction is worth most. Logged: a 14-tick fall covered 3.8 blocks horizontally when pure
    // drag
    // allows 2.28, so something was still powering it, and this is what. fallingFreely() still
    // covers
    // the untracked case, where releasing forward is all that can be done.
    if (fallTarget != null && airTicks > 0) {
      keys.remove(client.options.keyUp);
      applyFallSteer(keys);
    } else if (fallingFreely()) {
      keys.remove(client.options.keyUp);
    }

    if (walking.enabled(Control.ARRIVAL_COAST) && coastsToFinalPoint()) {
      keys.remove(client.options.keyUp);
    }

    boolean combatAirControl =
        airTicks > 0
            && airFromJump
            && fallTarget == null
            && !waterDecision.controlled()
            && !client.player.onClimbable()
            && !sneak
            && !ledgeOn
            && !deadzoneGate.get()
            && !preciseLook
            && hintAim == null
            && transmissionAim == null
            && applyCombatAirSteer(keys);
    if (!combatAirControl) {
      lastCombatAirSteer = FallPredictor.Steer.NONE;
      combatAirTarget = -1;
    }

    // Grounded, one stride from a planned drop, still carrying speed: let go and coast to the lip.
    // See
    // inDropBrakeRange - this is the half of the landing problem that mid-air steering cannot
    // reach.
    int dropWaypoint = upcomingDropWaypoint();
    double dropGap = Double.NaN;
    if (dropWaypoint >= 0) {
      Vec3 dropNode = trail.get(dropWaypoint);
      dropGap = flatDist(client.player.getX(), client.player.getZ(), dropNode.x, dropNode.z);
    }
    double dropSpeed = client.player.getDeltaMovement().horizontalDistance();
    boolean wasCoasting = dropBrake.isCoasting();
    if (dropBrake.holdForwardOff(
        dropWaypoint, inDropBrakeRange(dropGap, dropSpeed), dropSpeed > STEP_OFF_SPEED_FLOOR)) {
      keys.remove(client.options.keyUp);
      if (!wasCoasting) {
        detail("[FALL] braking into drop: gap %.2f speed %.3f", dropGap, dropSpeed);
      }
    }

    if (waterDecision.controlled() && waterDecision.steerByKeys()) {
      // The picked keys already follow the route, back included; forward is only the fallback.
      if (waterDecision.holdForward() && !holdsMovementKey(keys)) {
        keys.add(client.options.keyUp);
      }
    } else if (waterDecision.controlled()) {
      // Forward is forced here, and back would cancel it.
      keys.remove(client.options.keyDown);
      if (waterDecision.holdForward() && !keys.contains(client.options.keyUp)) {
        keys.add(client.options.keyUp);
      }
      if (waterDecision.suppressStrafe()) {
        keys.remove(client.options.keyLeft);
        keys.remove(client.options.keyRight);
      }
    }
    if (waterDecision.controlled()
        && waterDecision.holdJump()
        && !keys.contains(client.options.keyJump)) {
      keys.add(client.options.keyJump);
    }

    // Climbable escape. To vanilla, a vine/ladder/scaffolding cell is a ladder: forward impulse
    // inside
    // one means CLIMB UP. The airborne W hold (what carries a hop across its arc) therefore rides
    // the
    // wall back up, drops off the top, re-enters, and loops forever. Let go of forward while
    // clinging,
    // unless the route genuinely goes up from here - then climbing is the right move. Without W we
    // just
    // slide down and land, and the planner (which no longer routes through climbables at all) takes
    // it
    // from there. Catches what the plan couldn't know: regrowth, an unloaded chunk, starting on a
    // vine.
    if (client.player.onClimbable()
        && trailFootY(aimIndex) - client.player.getY() <= CLIMB_REACH_GATE_Y) {
      keys.remove(client.options.keyUp);
    }

    // Wall-grind failsafe: bump into something and stay blocked (forward held, grounded, and
    // either colliding or stalled near ~0 speed) for ~0.3s, then force one hop. No facing
    // gate, no step detection - the old instant collision-hop read as a bot and still skipped
    // the risers the planner gets wrong (the head sits up-diagonal at a node, so a look-aligned
    // step
    // probe finds nothing while the body grinds the block). Waiting the block out the way a person
    // would, then hopping no matter what the step probe said. If the hop fails to clear it, the
    // wedge timer / hop failsafe below recompute a route. Hops stay a last resort (walking is
    // preferred).
    boolean forwardHeld = keys.contains(client.options.keyUp);
    double obstructedVel =
        Math.hypot(client.player.getDeltaMovement().x, client.player.getDeltaMovement().z);
    boolean moving = forwardHeld && onGround && !waterDecision.controlled();
    // Auto-stepping up a stair or slab SETS horizontalCollision, so a clean climb is
    // indistinguishable
    // from grinding a wall by that flag alone, and the hop fired every few ticks all the way up a
    // staircase while vanilla was already carrying the body over it. Rising Y is the tell: if we
    // are
    // gaining height while touching something, the step is working and there is nothing to hop.
    // A genuine wall does not lift you, so the grind counter still accumulates there, and the
    // separate
    // stall counter below still covers a climb that has actually jammed.
    boolean autoStepping = client.player.getY() > previousY + AUTO_STEP_RISE_EPSILON;
    previousY = client.player.getY();
    boolean colliding = moving && client.player.horizontalCollision && !autoStepping;
    boolean stalled = moving && obstructedVel < CRAWL_SPEED_FLOOR;
    wallGrindTicks = colliding ? wallGrindTicks + 1 : 0;
    wallStallTicks = stalled ? wallStallTicks + 1 : 0;
    // A wall that knocks the body off the line gets a fresh route from where it stands, instead of
    // the head swinging back to a node the body has been shoved past and the steering hauling it
    // back onto the old line. Quiet, and at most once per cooldown so a long grind cannot spend the
    // replan budget.
    if (knockReplanCooldown > 0) {
      knockReplanCooldown--;
    } else if (colliding) {
      double offLine = nearestPathDistance();
      if (offLine > REJOIN_OFF_LINE
          && askReplan(String.format("knocked %.2f off the path by a wall", offLine), null)) {
        knockReplanCooldown = KNOCK_REPLAN_COOLDOWN_TICKS;
      }
    }
    boolean obstructionJump =
        walking.enabled(Control.RECOVERY_HOP)
            && hopCooldown == 0
            && (wallGrindTicks >= WALL_HOP_TICKS || wallStallTicks >= STALL_HOP_TICKS)
            && hopTerrain.wallToVaultAhead(aimNode);
    if (movingCombat != null
        && movingCombat.isCombatNavActive()
        && movingCombat.usesRecordedMovement()) {
      obstructionJump = false;
    }

    boolean isActuallyMoving =
        keys.contains(client.options.keyUp)
            || keys.contains(client.options.keyDown)
            || keys.contains(client.options.keyLeft)
            || keys.contains(client.options.keyRight);
    PlannedJump upcomingJump = nextPlannedJump();
    Vec3 velocity = client.player.getDeltaMovement();
    Vec3 finalNode = trail.getLast();
    boolean safeToHop =
        MovementGate.shouldHoldConstantJump(
            true,
            !inWater
                && !waterDecision.controlled()
                && !client.player.onClimbable()
                && Double.isNaN(dropGap)
                && fallTarget == null,
            sneak,
            isActuallyMoving,
            flatDist(client.player.getX(), client.player.getZ(), finalNode.x, finalNode.z),
            CONSTANT_JUMP_BUFFER_BLOCKS,
            upcomingJump == null ? Double.NaN : upcomingJump.flatGap(),
            CONSTANT_JUMP_BUFFER_BLOCKS,
            MovementGate.momentumAligned(
                velocity.x,
                velocity.z,
                aimX - client.player.getX(),
                aimZ - client.player.getZ(),
                CRAWL_SPEED_FLOOR,
                HOP_FACING_GATE_DEG),
            initialFacingReady);
    boolean constantJump = movementMode.holdsConstantJump(safeToHop);
    if (constantJump) {
      keys.add(client.options.keyJump);
    }

    // Crouching is asked for when something is watching (Ryan boots us the moment we look like we
    // are
    // not one of his), and a hop is the least sneaky thing the body can do. While sneaking, only
    // the
    // wall-grind fallback may jump: that one fires when we are ALREADY stuck, so it is the
    // necessary
    // kind. The planner's climb / vault hops stay suppressed - the sneak stretches are flat anyway.
    boolean shouldJump =
        !waterDecision.controlled()
            && ((!sneak && wantsHop(faceYaw, faceGap, flatGapMove, aimNode, aimIndex, aimX, aimZ))
                || obstructionJump);
    if (obstructionJump) {
      detail(
          "[HOP] wall-grind forced: collide=%dt stall=%dt spd=%.4f clipHit=%b",
          wallGrindTicks, wallStallTicks, obstructedVel, client.player.horizontalCollision);
    }
    boolean holdCombatHop = shouldJump && onGround && combatHopOvershoots();
    if (holdCombatHop) {
      shouldJump = false;
      keys.remove(client.options.keyUp);
    }
    if (holdCombatHop != combatHopHeld) {
      combatHopHeld = holdCombatHop;
      detail("[COMBAT-HOP] %s", holdCombatHop ? "shedding run-up before hop" : "released");
    }
    if (shouldJump && onGround) {
      keys.add(client.options.keyJump);
    }

    if (!waterDecision.controlled()) {
      transmission.shapeKeys(keys);
    }
    if ((constantJump || shouldJump) && onGround && keys.contains(client.options.keyJump)) {
      phase = State.JUMPING;
      airFromJump = true; // a hop: keep forward held across the arc (see fallingFreely)
    }
    if (sneak) {
      keys.add(client.options.keyShift);
    }
    if (onGround && !waterDecision.controlled() && !ledgeOn) {
      if (WalkingNavigation.isSearchPending()
          && (!hopTerrain.safeWalkingLine(aimX, aimZ) || client.player.horizontalCollision)) {
        keys.removeIf(
            key ->
                key == client.options.keyUp
                    || key == client.options.keyDown
                    || key == client.options.keyLeft
                    || key == client.options.keyRight
                    || key == client.options.keyJump);
      } else if (recordedWalk) {
        boolean keepStrafeForward =
            (movingCombat == null || !movingCombat.isCombatNavActive())
                && !client.player.onClimbable()
                && !holdCombatHop
                && dropWaypoint < 0
                && trailCursor < trail.size() - 1
                && WalkingPolicy.carryStrafeForward(
                    MacroKeys.isDown(client.options.keyUp),
                    movementController.bodyRelativeAngle(selfPos, goalPos));
        clearUnsupportedStrafe(keys, keepStrafeForward);
      }
    }
    boolean sprint = shouldHoldSprint();
    if (sprint) {
      keys.add(client.options.keySprint);
    }
    KeyBindUtil.holdThese(keys);
    logHeldKeys(keys);
    if (PathTelemetry.recording()) {
      PathTelemetry.decision(
          new PathTelemetry.Decision(
              phase.name(),
              trailCursor,
              aimIndex,
              aimX,
              aimZ,
              faceYaw,
              faceGap,
              aimGap,
              flatGapMove,
              gazePlanner.gazeNodeIndex(),
              rotationController.aimYaw(),
              rotationController.aimPitch(),
              PathTelemetry.keyLetters(client, keys),
              sprint,
              shouldJump,
              constantJump,
              obstructionJump,
              wallGrindTicks,
              wallStallTicks,
              hopCooldown,
              dropGap,
              sneak,
              waterDecision.state().name(),
              ledgeOn,
              deadzoneGate.get(),
              airTicks,
              mayOwnLook,
              recordedAirDiagonal,
              combatAirControl ? lastCombatAirSteer.name() : "OFF"));
    }
    if (!waterDecision.controlled() && !transmissionPreview) {
      transmission.tryUse(transmissionAim);
    }

    isActuallyMoving =
        keys.contains(client.options.keyUp)
            || keys.contains(client.options.keyDown)
            || keys.contains(client.options.keyLeft)
            || keys.contains(client.options.keyRight);

    traceCounter++;
    if (traceCounter >= TRACE_EVERY_N) {
      traceCounter = 0;
      // node= is the raw path node (what the head looks at); go= is where the BODY is actually
      // steered,
      // which differs at a ledge drop where the target is pushed past the landing. Logging only the
      // node
      // made that extension invisible and the ledge fix unverifiable from a log.
      detail(
          "cur=%d/%d node=(%.2f,%.2f,%.2f) go=(%.2f,%.2f) yaw %.1f->%.1f "
              + "(moveErr=%.1f lookErr=%.1f) mv=%b run=%b",
          trailCursor,
          trail.size(),
          aimNode.x,
          aimNode.y,
          aimNode.z,
          aimX,
          aimZ,
          client.player.getYRot(),
          rotationController.aimYaw(),
          faceGap,
          aimGap,
          isActuallyMoving,
          sprint);
    }

    trace.logBlockStep(trailCursor, trail.size());
    trace.logBigTurn(aimGap, rotationController.aimYaw());
    trace.logState(phase);

    if (transmission.suppressesWatchdogs()) {
      wedgeClock.reset();
      progressClock.reset();
      driftStreak = 0;
      return true;
    }

    stallWatch.tickHopFailsafe(shouldJump, onGround);
    if (!running) {
      return false;
    } // a failsafe (jump-stuck) may have suspended us for a replan

    stallWatch.tickWedgeWatch(isActuallyMoving, waterDecision.controlled());
    if (wedgeClock.isScheduled() && wedgeClock.passed()) {
      double spd =
          Math.hypot(client.player.getDeltaMovement().x, client.player.getDeltaMovement().z);
      wedgeClock.reset();
      // Rather than quitting when wedged, pull a new route from here. Give up for real only
      // once the replan budget runs out, so an unreachable goal cannot loop forever.
      if (askReplan(
          String.format("wedged %dms (spd=%.4f)", WEDGE_TIMEOUT_MS, spd), "getting wedged")) {
        return false;
      }
      error("Wedged against something and out of retries - stopping (speed %.4f)", spd);
      bailed = true;
      won = false;
      stop();
      return false;
    }

    if (stallWatch.tickVerticalPinWatch(isActuallyMoving && !waterDecision.controlled())) {
      return false;
    }

    if (stallWatch.tickProgressWatch(isActuallyMoving, waterDecision.controlled())) {
      return false;
    }

    if (corridorWatch.tickObstructionWatch()) {
      return false;
    }
    if (corridorWatch.tickUnseenChunks()) {
      return false;
    }

    return true;
  }

  /**
   * Feet Y of a body standing on trail point {@code index}.
   *
   * <p>Walk trail points are NOT at feet height. {@code PathManager.packPoints} subtracts one from
   * every node of a non-fly/non-swim path, so a land or wade point carries the SUPPORT block - one
   * below the body that stands on it. A SWIM_SURFACE point is the exception: {@code
   * PathNode.movementY} adds the block back, so it already is the feet cell.
   *
   * <p>Every vertical comparison against {@code player.getY()} has to come through here. They did
   * not, and the whole vertical layer was reading one block low: a 1-block step-up measured as a
   * 0.0 rise, so {@link #CLIMB_REACH_GATE_Y} (0.6) never saw a real riser and {@link
   * #plannedJumpAhead()} could not fire at all, while {@link #LEDGE_DROP_MIN_Y} (1.5, meant for a
   * 2-block drop) fired on every ordinary step down. The constants were always right; the operand
   * was not.
   */
  double trailFootY(int index) {
    Vec3 point = trail.get(index);
    if (mediumAt(index) == PathNode.Medium.SWIM_SURFACE) {
      return point.y;
    }
    return point.y + supportTopOffset(point);
  }

  /**
   * Height of the support block's top face, 0..1. The old flat {@code + 1.0} assumed every
   * standable block is a full cube, so a body standing on a shallow snow layer read as 0.625-0.875
   * BELOW its own node - over {@link #CLIMB_REACH_GATE_Y}, which pins the cursor for a climb that
   * is already done and shuffles the body back and forth forever. Chests, farmland and the like
   * were off by the same trick, just under the gate. Unloaded or shapeless support falls back to a
   * full block.
   */
  private double supportTopOffset(Vec3 point) {
    if (client.level == null) {
      return 1.0;
    }
    BlockPos pos = BlockPos.containing(point.x, point.y, point.z);
    VoxelShape shape = client.level.getBlockState(pos).getCollisionShape(client.level, pos);
    if (shape.isEmpty()) {
      return 1.0;
    }
    return Math.min(1.0, Math.max(0.0, shape.max(Direction.Axis.Y)));
  }

  private void buildTrail() {
    PathTrailBuilder.Result result = trailBuilder.build(turnNodes);
    trail.clear();
    trail.addAll(result.points());
    trailMedia.clear();
    trailMedia.addAll(result.media());
    List<Vec3> footPoints = new ArrayList<>(trail.size());
    for (int index = 0; index < trail.size(); index++) {
      Vec3 point = trail.get(index);
      footPoints.add(new Vec3(point.x, trailFootY(index), point.z));
    }
    routeFootPoints = List.copyOf(footPoints);
    turnLaneMarks.clear();
    turnLaneMarks.addAll(result.turnLaneMarks());
    // The gaze steps between the drawn node boxes only; a gap-filler keeps its dense step height,
    // so on a stair it sits off the key-node line and would pull the head down or up at it.
    gazeMarks.clear();
    for (int i = 0; i < Math.min(turnNodes.size(), turnLaneMarks.size()); i++) {
      if (!turnNodes.get(i).gapFiller()) {
        gazeMarks.add(turnLaneMarks.get(i));
      }
    }
    gazePlanner.reset();
  }

  private PathNode.Medium mediumAt(int index) {
    if (index < 0 || index >= trailMedia.size()) {
      return PathNode.Medium.LAND;
    }
    return trailMedia.get(index);
  }

  private boolean hasLandAhead(int lookahead) {
    int end = Math.min(trailMedia.size(), trailCursor + Math.max(1, lookahead) + 1);
    for (int i = Math.max(0, trailCursor); i < end; i++) {
      if (trailMedia.get(i) == PathNode.Medium.LAND) {
        return true;
      }
    }
    return false;
  }

  private static List<PathNode> centeredNodes(List<BlockPos> nodes) {
    if (nodes == null || nodes.isEmpty()) {
      return List.of();
    }
    return nodes.stream().map(PathNode::centered).toList();
  }

  // cursor stepping

  private void stepCursorForward() {
    if (client.player.isInWater()) {
      overWater = true;
    } else if (client.player.onGround()) {
      overWater = false;
    }
    int advances = 0;
    boolean vertPinned = false;
    boolean pinnedOnDrop = false;
    double pinGap = Double.MAX_VALUE;
    boolean atGoalMode =
        PathfinderConfig.goalMode == PathfinderConfig.GoalMode.EXACT && requestedGoal != null;
    while (trailCursor < trail.size() && advances < 5) {
      Vec3 nodePt = trail.get(trailCursor);
      double horizontalDist =
          flatDist(client.player.getX(), client.player.getZ(), nodePt.x, nodePt.z);
      boolean waterNode = mediumAt(trailCursor) != PathNode.Medium.LAND;

      boolean isLastWaypoint = trailCursor == trail.size() - 1;
      if (isLastWaypoint && !atGoalMode) {
        // The general advance rules retire a node within the look-ahead reach or once its plane is
        // crossed, which for the last one reported arrival a block short or on the wrong level.
        double rise = Math.abs(client.player.getY() - trailFootY(trailCursor));
        boolean settled = client.player.onGround() || waterNode;
        if (horizontalDist <= ARRIVE_RADIUS && rise <= ARRIVE_MAX_RISE && settled) {
          trailCursor++;
          advances++;
          continue;
        }
        break;
      }
      if (isLastWaypoint && atGoalMode) {
        BlockPos pp = client.player.blockPosition();
        if (pp.getX() == requestedGoal.getX()
            && pp.getZ() == requestedGoal.getZ()
            && Math.abs(client.player.getY() - trailFootY(trailCursor)) <= ARRIVE_MAX_RISE
            && (client.player.onGround() || waterNode)) {
          trailCursor++;
          advances++;
          continue;
        }
        break;
      }

      // Vertical reach gate: the advance checks below are flat (XZ only), so a climb node counts as
      // "reached" the instant the body is horizontally near it - while still at the bottom of the
      // step.
      // The cursor would then skip to the next (upper, further) node mid-hop, swing the gaze ahead,
      // and
      // the body would grind the riser face instead of climbing it (the wall-hug stall). Pin the
      // cursor
      // on a node still above the feet so the climb hop keeps pushing up into it. Same-level leaps
      // pass.
      // ponytail: relies on auto-step keeping feet-Y tracking on walkable ramps; failsafes replan
      // if a
      // climb truly can't be made, so the pin can never deadlock.
      // A planned boosted jump may pin up to its reachable landing; other nodes keep the step cap.
      // A riser blocks the body until it climbs, so a node already crossed along the run is not an
      // unclimbed step; its height is a spline lerp floating over terrain that dropped early.
      // Only on the run line, though: a body walking beside a ledge crosses its nodes unblocked.
      if (!waterNode
          && (!crossedOnRunLine(nodePt) || isPlannedHighLanding(trailCursor))
          && MovementGate.shouldPinForClimb(
              trailFootY(trailCursor) - client.player.getY(),
              CLIMB_REACH_GATE_Y,
              Math.max(CLIMB_PIN_MAX_RISE, plannedClimbReach()))) {
        vertPinned = true;
        pinGap = horizontalDist;
        break;
      }

      // The same gate, mirrored, for DROPS - and the reason it is safe this time.
      //
      // Without it the cursor retires a whole descent while the body is still on the lip, because
      // these
      // advance tests are flat: the landing sits ONE block sideways and many blocks down, so
      // walking that
      // one block reads as "reached". Measured in a real session: the body never left y=89 all run,
      // yet
      // the cursor sat at node 19/24 whose node was at y=80. The aim then pointed ten blocks down
      // through
      // the floor, the riser probe found the wall in between, and it threw 224 climb hops at it.
      //
      // An earlier attempt at this pin ping-ponged and had to be reverted: it fired from 0.6 down,
      // where
      // the aim was still AT the node, so once the body crossed that node's XZ the heading flipped
      // 180
      // and it oscillated (moveErr=179.6 with lookErr=0.4 in the log). The threshold here is
      // deliberately
      // the SAME LEDGE_DROP_MIN_Y the aim extension uses, so a pin can never exist without the
      // extension
      // that pushes the target two blocks beyond the node - which is exactly what stops the heading
      // from
      // reversing. Pin and extension are one mechanism; do not give them separate thresholds - or,
      // as the
      // debounce commit did by accident, separate TIMING: the extension went sticky and the pin did
      // not,
      // so every onGround blip on a lip bought GATE_HOLD_TICKS of pinned-with-no-extension, which
      // is that
      // reverted ping-pong exactly. dropPinned now forces the extension on, and stands the peek
      // down so
      // both read the same node.
      //
      // Grounded-only: a fall is ballistic and must advance freely. If a drop genuinely cannot be
      // made,
      // the progress and wedge failsafes replan out, same argument as the climb gate above.
      if (!waterNode
          && client.player.onGround()
          && client.player.getY() - trailFootY(trailCursor) > LEDGE_DROP_MIN_Y) {
        vertPinned = true;
        pinnedOnDrop = true;
        pinGap = horizontalDist;
        break;
      }

      // A floating body bobs level with the treads of a stair beside it, so the flat tests below
      // retire steps it has not mounted and the aim ends up on the far side of the wall. Afloat, a
      // land node has to be reached for real. The bob lifts the body clear of the water for a tick
      // or two each cycle, so this follows overWater rather than isInWater; only a body wading on
      // the bed toward a node at its own level keeps the ordinary rules.
      boolean wadingOnBed =
          client.player.onGround()
              && trailFootY(trailCursor) - client.player.getY() <= AFLOAT_LEVEL_SLACK;
      boolean afloat = !waterNode && overWater && !wadingOnBed;
      double reach =
          waterNode
              ? Math.max(liveReach, 1.0)
              : afloat ? Math.min(liveReach, AFLOAT_REACH) : liveReach;
      if (horizontalDist <= reach) {
        trailCursor++;
        advances++;
        continue;
      }
      if (afloat) {
        break;
      }

      if (trailCursor + 1 < trail.size()) {
        Vec3 aheadNode = trail.get(trailCursor + 1);
        double gapNext =
            flatDist(client.player.getX(), client.player.getZ(), aheadNode.x, aheadNode.z);
        if (gapNext < horizontalDist) {
          trailCursor++;
          advances++;
          continue;
        }
      }

      // Drop a node the player has slid past along its travel axis, so the live target (and
      // the gaze with it) is always truly ahead, never the node just crossed.
      // Speed-gated: a body mashed into a wall (~0 speed) can look "past" the node plane
      // without ever getting there - skipping then would wrongly discard a node it still needs.
      // The horizontalDist<2.0 gate stops grounded switchbacks from cutting corners; in the air
      // there is no
      // steering choice (the arc is ballistic) and a sprint-hop overshoots by 3+ blocks, so
      // the gate is dropped airborne, or the head ends up staring back at the node just flown over.
      double speedNow =
          Math.hypot(client.player.getDeltaMovement().x, client.player.getDeltaMovement().z);
      boolean nearOrAirborne = horizontalDist < 2.0 || airTicks > 0;
      if (nearOrAirborne && speedNow > CRAWL_SPEED_FLOOR && pastNodeAlongRun(nodePt)) {
        trailCursor++;
        advances++;
        continue;
      }
      break;
    }
    // Airborne ticks never count. A long fall leaves the live node far overhead, which trips the
    // climb
    // gate for the whole descent - counting that would replan from a cell the body is still falling
    // past,
    // and the route would be stale before it arrived. A body in the air has no steering to correct
    // with
    // anyway; wait until it lands and replan from where it actually is.
    // Patience is spent only while the body is NOT closing on the node it is pinned on. A pin
    // engages the
    // moment the cursor points at a node off the body's level and holds for the entire walk up to
    // it, so
    // the old raw count made "approaching a ledge" indistinguishable from "stuck beside one" - and
    // the
    // approach is the slow part by design. Ground gained resets the budget; a body that has stopped
    // gaining is the stale-route case the watch was written for.
    if (!vertPinned || !client.player.onGround()) {
      vertPinTicks = 0;
      vertPinGap = Double.MAX_VALUE;
    } else if (pinGap < vertPinGap - VERT_PIN_CLOSING_EPSILON) {
      vertPinGap = pinGap;
      vertPinTicks = 0;
    } else {
      vertPinTicks++;
    }
    dropPinned = pinnedOnDrop;
    if (advances > 0) {
      phase = State.TRAVERSING;
    }
  }

  private boolean pastNodeAlongRun(Vec3 nodePt) {
    if (trailCursor <= 0) {
      return false;
    }
    Vec3 backNode = trail.get(trailCursor - 1);
    double runX = nodePt.x - backNode.x;
    double runZ = nodePt.z - backNode.z;
    if (runX * runX + runZ * runZ <= 0.01) {
      return false;
    }
    return (client.player.getX() - nodePt.x) * runX + (client.player.getZ() - nodePt.z) * runZ > 0;
  }

  private boolean crossedOnRunLine(Vec3 nodePt) {
    if (!pastNodeAlongRun(nodePt)) {
      return false;
    }
    Vec3 backNode = trail.get(trailCursor - 1);
    double runX = nodePt.x - backNode.x;
    double runZ = nodePt.z - backNode.z;
    double offX = client.player.getX() - nodePt.x;
    double offZ = client.player.getZ() - nodePt.z;
    return Math.abs(offX * runZ - offZ * runX) / Math.hypot(runX, runZ) <= REJOIN_OFF_LINE;
  }

  private void resnapCursor() {
    if (trailCursor >= trail.size()) {
      return;
    }
    if (isPlannedHighLanding(trailCursor)
        && trailFootY(trailCursor) > client.player.getY() + CLIMB_REACH_GATE_Y) {
      return;
    }
    Vec3 curNode = trail.get(trailCursor);
    double dist = flatDist(client.player.getX(), client.player.getZ(), curNode.x, curNode.z);
    if (dist <= RESYNC_RANGE) {
      return;
    }

    int pickSlot = trailCursor;
    double bestDist = dist;
    int scanEnd = Math.min(trailCursor + 20, trail.size());
    for (int i = trailCursor + 1; i < scanEnd; i++) {
      if (isPlannedHighLanding(i) && trailFootY(i) > client.player.getY() + CLIMB_REACH_GATE_Y) {
        break;
      }
      Vec3 nodePt = trail.get(i);
      double d = flatDist(client.player.getX(), client.player.getZ(), nodePt.x, nodePt.z);
      if (d < bestDist) {
        bestDist = d;
        pickSlot = i;
      }
    }
    if (pickSlot != trailCursor) {
      detail("cursor resnap: %d -> %d (gap %.1f -> %.1f)", trailCursor, pickSlot, dist, bestDist);
      trailCursor = pickSlot;
    }
  }

  // hop decisions (stair-aware)

  private boolean wantsHop(
      float faceYaw,
      float faceGap,
      double flatGapMove,
      Vec3 aimNode,
      int aimIndex,
      double steerX,
      double steerZ) {
    PlannedJump plannedJump = plannedJumpAhead();
    CombatModule combat = RyznBootstrap.moduleManager().getByClass(CombatModule.class);
    if (plannedJump == null
        && combat != null
        && combat.isCombatNavActive()
        && combat.usesRecordedMovement()) {
      return false;
    }

    // Climb hop: fires when the surface the body is ABOUT TO ENTER stands between auto-step height
    // and
    // what a jump can actually clear. Measured one probe-length along the heading, not at a path
    // node -
    // nodes sit up to livePeek (3.5-7 blocks) away and on a 0.5 staircase each is another
    // half-block
    // higher, so node-vs-feet read 1.0+ the whole way up a run vanilla walks for free. That kept
    // this
    // armed continuously and left the real decision to horizontalCollision, which auto-step also
    // sets
    // (see the wall-grind failsafe above) - hence the random hops on slab/block staircases and
    // stair+slab
    // mixes. An honest per-point rise needs no such tie-break: >0.6 is a riser auto-step cannot
    // take.
    boolean bigDropAhead =
        aimNode != null && trailFootY(aimIndex) < client.player.getY() - LEDGE_DROP_MIN_Y;

    boolean stepUpAhead = false;
    // A route that DESCENDS never wants a climb hop. Observed: the body at y=90 with its aim node
    // at
    // y=80 - ten blocks down, on the far side of a wall - spent 224 hops ramming that wall, because
    // the
    // probe only ever asked "is something in front of me?" and a wall always answers yes. When the
    // route
    // goes down, a riser ahead is geometry to route AROUND, not a step to climb; hopping it can
    // only ever
    // add height the route is trying to shed. The wall-grind failsafe and the drift watch handle a
    // genuine
    // dead end from here, which is what should happen when the way on is not over the thing in
    // front.
    // ...and neither does a body that is not standing on anything. The probe measures the riser
    // against
    // the CURRENT feet Y, and a floating body sits about a block above the wade node the route
    // planned
    // for it, so a bank the route means to pass beside reads as a 0.8 step to climb. The hop it
    // then asks
    // for can never happen - the key is only pressed `shouldJump && onGround`, and vanilla turns
    // jump in
    // water into a swim impulse anyway - so it just re-arms the hop watch every tick while the body
    // holds
    // W into the bank. Logged as 138 [HOP] climb requests and zero jumps at one bank. Standing on
    // ground
    // is the precondition for a step-up; the swim states below are what get the body over water.
    if (trailCursor < trail.size()
        && !bigDropAhead
        && client.player.onGround()
        && !client.player.isInWater()) {
      Vec3 nodePt = trail.get(trailCursor);
      if (trailCursor + 1 < trail.size() && flatGapMove < livePeek) {
        nodePt = trail.get(trailCursor + 1);
      }
      double riserTopY = hopTerrain.surfaceAhead(nodePt);
      double rise = riserTopY - client.player.getY();
      // The probe reads one point 0.8 ahead against the feet, so a full block just past a half
      // step reads as a 1.0 riser even though the body walks up the half step first. Hop only
      // when the footprint really cannot walk the line it is being steered along.
      if (riserTopY != Double.NEGATIVE_INFINITY
          && rise > CLIMB_REACH_GATE_Y
          && rise <= PathHopTerrain.MAX_HOP_RISE
          && hopTerrain.bodyLineWalkable(steerX, steerZ)) {
        detail(
            "[HOP?] riser ahead top=%.2f rise=%.2f, but the steer line is walkable - no hop",
            riserTopY, rise);
      } else if (riserTopY != Double.NEGATIVE_INFINITY
          && rise > CLIMB_REACH_GATE_Y
          && rise <= PathHopTerrain.MAX_HOP_RISE) {
        stepUpAhead = true;
        detail(
            "[HOP?] riser ahead top=%.2f selfY=%.2f rise=%.2f",
            riserTopY, client.player.getY(), rise);
      } else if (riserTopY != Double.NEGATIVE_INFINITY && rise > PathHopTerrain.MAX_HOP_RISE) {
        detail("[HOP?] riser %.2f too tall to jump - treating as wall", rise);
      }
    }

    boolean vaultAhead = false;
    // ...but never vault a walkable ramp. The obstacle probe (wallToVaultAhead) flags anything
    // it cannot walk THROUGH that rises > 0.6 as a wall to clear - which grabs deep snow layers
    // (LAYERS >= 2 block passage) and any run the surface really climbs. When the surface
    // to the node is walkable (snow slope, slabs, stairs, half-steps), just walk it.
    if (!stepUpAhead
        && !bigDropAhead
        && aimNode != null
        && client.player.onGround()
        && flatGapMove <= 2.5
        && !hopTerrain.rampWalkableTo(aimNode)
        && !hopTerrain.bodyLineWalkable(steerX, steerZ)) {
      vaultAhead = hopTerrain.wallToVaultAhead(aimNode);
    }

    boolean hopNeeded = plannedJump != null || stepUpAhead;
    if (vaultAhead) {
      hopNeeded = true;
    }

    float hopGate =
        MovementGate.jumpAlignmentThresholdDeg(
            false,
            plannedJump != null || stepUpAhead || vaultAhead,
            HOP_FACING_GATE_DEG,
            CLIMB_HOP_GATE_DEG,
            WIDE_FACING_GATE_DEG);
    boolean hopFires = MovementGate.shouldJump(faceGap, hopCooldown, hopNeeded, hopGate);
    if (hopFires) {
      if (plannedJump != null) {
        detail(
            "[HOP] planned jump: waypoint=%d gap=%.2f lead=%.2f",
            plannedJump.waypointIndex(), plannedJump.flatGap(), plannedJumpLeadBlocks());
      } else if (vaultAhead) {
        detail("[HOP] vault block: aimErr=%.1f gap=%.2f", faceGap, flatGapMove);
      } else {
        detail("[HOP] climb: gap=%.2f", flatGapMove);
      }
    }
    return hopFires;
  }

  private double distanceToFinalPoint() {
    if (client.player == null || trail.isEmpty() || trailCursor != trail.size() - 1) {
      return Double.NaN;
    }
    Vec3 last = trail.getLast();
    return flatDist(client.player.getX(), client.player.getZ(), last.x, last.z);
  }

  /**
   * Grounded with enough measured speed to glide onto the final point: let go of forward now. On
   * ordinary ground each tick keeps {@link #GROUND_DRAG} of the speed, so the glide covers {@code v
   * / (1 - drag)}. Stopping on the point instead of driving past it and backing up is what removes
   * the circling and repeated final corrections.
   */
  private boolean coastsToFinalPoint() {
    double gap = distanceToFinalPoint();
    if (Double.isNaN(gap)) {
      return false;
    }
    Vec3 velocity = client.player.getDeltaMovement();
    Vec3 goal =
        requestedGoal != null && PathfinderConfig.goalMode == PathfinderConfig.GoalMode.EXACT
            ? Vec3.atCenterOf(requestedGoal)
            : trail.getLast();
    if (velocity.x * (goal.x - client.player.getX()) + velocity.z * (goal.z - client.player.getZ())
        <= 0.0) {
      return false;
    }
    double speed = velocity.horizontalDistance();
    double glide = speed / (1.0 - GROUND_DRAG);
    double drop = client.player.getY() - trailFootY(trail.size() - 1);
    if (!airFromJump
        && fallTarget == null
        && (client.player.onGround()
            || takeoffY - trailFootY(trail.size() - 1) <= CLIMB_REACH_GATE_Y)
        && velocity.y < 0.0
        && drop > 1.0e-3
        && drop <= CLIMB_REACH_GATE_Y) {
      Vec3 position = client.player.position();
      double groundStride = 0.0;
      if (client.player.onGround()) {
        // Release at the last tread, before the half-step fall carries the remaining momentum.
        double surface =
            hopTerrain.footprintSurfaceY(
                position.x + velocity.x, position.z + velocity.z, position.y);
        if (surface >= position.y - 1.0e-3 || surface < trailFootY(trail.size() - 1) - 1.0e-3) {
          return gap <= glide + ARRIVE_RADIUS * 0.5;
        }
        position = position.add(velocity);
        groundStride = speed;
        velocity =
            new Vec3(
                velocity.x * GROUND_DRAG,
                (velocity.y - FallPredictor.GRAVITY) * FallPredictor.VERTICAL_DRAG,
                velocity.z * GROUND_DRAG);
      }
      FallPredictor.Landing landing =
          FallPredictor.simulate(position, velocity, trailFootY(trail.size() - 1));
      if (!landing.reached()) {
        return false;
      }
      glide =
          groundStride
              + flatDist(position.x, position.z, landing.x(), landing.z())
              + velocity.horizontalDistance()
                  * Math.pow(FallPredictor.HORIZONTAL_DRAG, landing.ticks())
                  / (1.0 - GROUND_DRAG);
    } else if (!client.player.onGround()) {
      return false;
    }
    return WalkingPolicy.coastReaches(
        goal.x - client.player.getX(),
        goal.z - client.player.getZ(),
        velocity.x,
        velocity.z,
        glide,
        ARRIVE_RADIUS);
  }

  private PlannedJump plannedJumpAhead() {
    PlannedJump jump = nextPlannedJump();
    if (jump == null || jump.flatGap() > plannedJumpLeadBlocks()) {
      return null;
    }
    return jump;
  }

  private double plannedClimbReach() {
    return isPlannedHighLanding(trailCursor)
        ? Math.max(CLIMB_PIN_MAX_RISE, trailFootY(trailCursor) - client.player.getY())
        : CLIMB_PIN_MAX_RISE;
  }

  private boolean isPlannedHighLanding(int waypoint) {
    int count = Math.min(turnNodes.size(), turnLaneMarks.size());
    for (int i = 1; i < count; i++) {
      if (turnLaneMarks.get(i) == waypoint) {
        return turnNodes.get(i).jumpUp()
            && trailFootY(waypoint) - trailFootY(turnLaneMarks.get(i - 1)) > CLIMB_PIN_MAX_RISE;
      }
    }
    return false;
  }

  private boolean replanUnavailableJump() {
    if (!client.player.onGround() || client.player.isInWater() || client.player.onClimbable()) {
      return false;
    }
    int count = Math.min(turnNodes.size(), turnLaneMarks.size());
    double reach = Double.NaN;
    for (int i = 1; i < count; i++) {
      int waypoint = turnLaneMarks.get(i);
      if (waypoint < trailCursor
          || !turnNodes.get(i).jumpUp()
          || trailFootY(waypoint) <= client.player.getY() + CLIMB_REACH_GATE_Y) {
        continue;
      }
      double rise = trailFootY(waypoint) - trailFootY(turnLaneMarks.get(i - 1));
      if (rise <= CLIMB_PIN_MAX_RISE) {
        continue;
      }
      if (Double.isNaN(reach)) {
        reach = JumpCapability.height();
      }
      if (rise > reach) {
        if (askReplan(
            String.format("jump reach changed (%.2f needed, %.2f available)", rise, reach),
            "jump boost changed")) {
          waitingForJumpReplan = true;
        } else {
          stop();
          bailed = true;
          won = false;
        }
        return true;
      }
    }
    return false;
  }

  private PlannedJump nextPlannedJump() {
    if (client.player == null || trail.isEmpty() || turnNodes.isEmpty()) {
      return null;
    }
    int count = Math.min(turnNodes.size(), turnLaneMarks.size());
    for (int nodeIndex = 0; nodeIndex < count; nodeIndex++) {
      PathNode node = turnNodes.get(nodeIndex);
      if (!node.jumpUp()) {
        continue;
      }
      int waypointIndex = turnLaneMarks.get(nodeIndex);
      if (waypointIndex < trailCursor || waypointIndex >= trail.size()) {
        continue;
      }
      Vec3 target = trail.get(waypointIndex);
      if (trailFootY(waypointIndex) - client.player.getY() <= CLIMB_REACH_GATE_Y) {
        continue;
      }
      double flatGap = flatDist(client.player.getX(), client.player.getZ(), target.x, target.z);
      return new PlannedJump(waypointIndex, target, flatGap);
    }
    return null;
  }

  private double plannedJumpLeadBlocks() {
    if (client.player == null) {
      return PLANNED_JUMP_LEAD_MIN_BLOCKS;
    }
    double speed =
        Math.hypot(client.player.getDeltaMovement().x, client.player.getDeltaMovement().z);
    double lead = PLANNED_JUMP_LEAD_MIN_BLOCKS + speed * PLANNED_JUMP_LEAD_TICKS;
    return Math.clamp(lead, PLANNED_JUMP_LEAD_MIN_BLOCKS, PLANNED_JUMP_LEAD_MAX_BLOCKS);
  }

  private boolean wantsPreciseAim() {
    return client.options.keyAttack.isDown() || client.options.keyUse.isDown();
  }

  private record PlannedJump(int waypointIndex, Vec3 target, double flatGap) {}

  // small helpers

  static double flatDist(double x1, double z1, double x2, double z2) {
    return Math.hypot(x1 - x2, z1 - z2);
  }

  // ---- safety nets carried over from the main driver ----

  private void tickAirState(boolean onGround) {
    if (onGround) {
      landTicks++;
      if (airTicks > 0) {
        landingNeedsStrafeHold =
            airFromJump
                || fallTarget != null
                || airTicks > 2
                || Math.abs(takeoffY - client.player.getY()) > CLIMB_REACH_GATE_Y;
      }
      // Touchdown on a tracked fall: report planned against actual before the target is dropped.
      // This
      // is the number the whole feature is judged on, and guessing at it from the next capture is
      // what
      // cost two rounds of shipping unverified fixes.
      if (airTicks > 0 && fallTarget != null) {
        double missX = client.player.getX() - fallTarget.x;
        double missZ = client.player.getZ() - fallTarget.z;
        PathTelemetry.landingOffset(Math.hypot(missX, missZ));
        log(
            "[FALL] landed %.2f from plan (target %.2f,%.2f actual %.2f,%.2f) after %d air ticks",
            Math.hypot(missX, missZ),
            fallTarget.x,
            fallTarget.z,
            client.player.getX(),
            client.player.getZ(),
            airTicks);
      }
      airTicks = 0;
      takeoffY = client.player.getY(); // last grounded height: the lip we step off
      airFromJump = false;
      fallTarget = null;
      lastFallSteer = FallPredictor.Steer.NONE;
    } else {
      airTicks++;
      landTicks = 0;
      // First air tick of a plain fall (airFromJump is still last tick's, set when the hop key went
      // down while we were grounded) - snapshot what the planner wanted before the cursor moves on.
      if (airTicks == 1 && !airFromJump && !client.player.isInWater()) {
        captureFallTarget();
      }
    }
  }

  /**
   * Pick the node this drop is meant to land on, at takeoff.
   *
   * <p>Reads TURN nodes, not raw trail points, because a drop is exactly one turn-node pair: lip
   * then landing. {@code PathTrailBuilder.buildCurvedSpan} bails out for {@code dy < -1} and lays a
   * straight span across it, so every trail point between the two is interpolation with no standing
   * room - the first turn node below the lip is the floor the planner actually chose.
   *
   * <p>The first version scanned raw trail points and took the lowest before the route climbed
   * again, which is wrong on any terraced descent: on a route that keeps stepping down, "lowest
   * within 32 points" runs off the end of the scan. Logged from a real drop, it took a landing at
   * y=173 for a fall that touched down at y=180 - a 7-block error in the landing PLANE, which then
   * predicted a touchdown far downrange, blew the reachability check, and stood the steering down
   * for the whole fall. That is why the first in-game test behaved exactly as it did before the
   * feature existed.
   *
   * <p>Capped horizontally as well: the native plans a drop as one cell sideways then straight
   * down, so a genuine landing is a block or two out. Anything further is a later part of the route
   * that happens to be lower, not this step-off, and is left untracked rather than steered at.
   */
  private void captureFallTarget() {
    fallTarget = null;
    if (trail.isEmpty() || turnNodes.isEmpty()) {
      return;
    }
    double takeoffFootY = client.player.getY();
    int count = Math.min(turnNodes.size(), turnLaneMarks.size());
    for (int nodeIndex = 0; nodeIndex < count; nodeIndex++) {
      int waypoint = turnLaneMarks.get(nodeIndex);
      if (waypoint < trailCursor || waypoint >= trail.size()) {
        continue;
      }
      if (mediumAt(waypoint) != PathNode.Medium.LAND) {
        return;
      }
      double footY = trailFootY(waypoint);
      if (footY > takeoffFootY - LEDGE_DROP_MIN_Y) {
        continue;
      } // still on the lip's own level
      Vec3 node = trail.get(waypoint);
      double flatGap = flatDist(client.player.getX(), client.player.getZ(), node.x, node.z);
      if (flatGap > FALL_TARGET_MAX_REACH) {
        return;
      }
      if (flowsThroughDrop(waypoint, takeoffFootY)) {
        detail("[FALL] short step-down on a straight run, not steering it");
        return;
      }
      fallTarget = new Vec3(node.x, footY, node.z);
      detail(
          "[FALL] tracking landing (%.2f,%.2f,%.2f) from y=%.2f (drop %.1f, out %.1f, speed %.3f)",
          node.x,
          footY,
          node.z,
          takeoffFootY,
          takeoffFootY - footY,
          flatGap,
          Math.hypot(client.player.getDeltaMovement().x, client.player.getDeltaMovement().z));
      return;
    }
  }

  private boolean applyCombatAirSteer(List<KeyMapping> keys) {
    CombatModule combat = RyznBootstrap.moduleManager().getByClass(CombatModule.class);
    if (combat == null
        || !combat.isCombatNavActive()
        || !combat.usesRecordedMovement()
        || !combat.ownsLook()
        || isPlannedHighLanding(trailCursor)) {
      return false;
    }
    var target = combat.getTarget();
    if (target == null || !target.isAlive()) {
      return false;
    }
    double landingY = target.getY();
    double targetGap =
        flatDist(client.player.getX(), client.player.getZ(), target.getX(), target.getZ());
    double standOff = Math.min(targetGap, RecordedCombatMovement.STOP_RANGE);
    double landingX = target.getX();
    double landingZ = target.getZ();
    if (targetGap > 1.0e-4) {
      landingX += (client.player.getX() - target.getX()) * standOff / targetGap;
      landingZ += (client.player.getZ() - target.getZ()) * standOff / targetGap;
    }
    if (client.player.getY() <= landingY + 1.0e-4
        || Math.abs(landingY - takeoffY) > CLIMB_PIN_MAX_RISE
        || targetGap > RecordedCombatMovement.ENGAGE_RANGE
        || !hopTerrain.safeCombatAirLine(landingY, landingX, landingZ)) {
      return false;
    }
    FallPredictor.Landing landing = hopTerrain.combatAirLanding(landingY);
    boolean brakeOnly =
        !landing.reached()
            || landing.ticks() < 1
            || !hopTerrain.safeCombatAirLine(landingY, landing.x(), landing.z());
    if (brakeOnly) {
      landing =
          FallPredictor.simulate(
              client.player.position(), client.player.getDeltaMovement(), landingY);
      if (!landing.reached() || landing.ticks() < 1) {
        return false;
      }
    }
    if (combatAirTarget != target.getId()) {
      lastCombatAirSteer = FallPredictor.Steer.NONE;
    }
    double errorX = landingX - landing.x();
    double errorZ = landingZ - landing.z();
    FallPredictor.Steer steer =
        FallPredictor.steer(
            errorX,
            errorZ,
            client.player.getYRot(),
            RecordedCombatMovement.AIR_LANDING_RANGE,
            lastCombatAirSteer);
    // A long arc that coasts on with W lands even farther past the mob than one that brakes, so
    // only a short arc must show the braked landing is safe before the route gives up its keys.
    if (brakeOnly
            && (!brakesCombatMomentum(steer)
                || landing.ticks() < COMBAT_BRAKE_FREE_TICKS
                    && !hopTerrain.safeCombatBrakeLanding(steer, landingY))
        || !hopTerrain.safeCombatAirInput(steer, landingY)) {
      return false;
    }
    if (combatAirTarget != target.getId() || steer != lastCombatAirSteer) {
      detail(
          "[COMBAT-AIR] target=%d steer=%s miss=%.2f ticks=%d brakeOnly=%s",
          target.getId(), steer, Math.hypot(errorX, errorZ), landing.ticks(), brakeOnly);
    }
    combatAirTarget = target.getId();
    lastCombatAirSteer = steer;
    keys.remove(client.options.keyUp);
    keys.remove(client.options.keyDown);
    keys.remove(client.options.keyLeft);
    keys.remove(client.options.keyRight);
    switch (steer) {
      case FORWARD -> keys.add(client.options.keyUp);
      case BACK -> keys.add(client.options.keyDown);
      case LEFT -> keys.add(client.options.keyLeft);
      case RIGHT -> keys.add(client.options.keyRight);
      case NONE -> {}
    }
    return true;
  }

  /**
   * Whether a hop launched now would coast past the combat target. Air drag only takes 9% a tick
   * and a key adds 0.02, so a fast run-up near the mob flies over it whatever the air steer does;
   * ground friction sheds that speed in a couple of ticks instead.
   */
  private boolean combatHopOvershoots() {
    CombatModule combat = RyznBootstrap.moduleManager().getByClass(CombatModule.class);
    if (combat == null
        || !combat.isCombatNavActive()
        || !combat.usesRecordedMovement()
        || isPlannedHighLanding(trailCursor)) {
      return false;
    }
    var target = combat.getTarget();
    if (target == null || !target.isAlive()) {
      return false;
    }
    Vec3 velocity = client.player.getDeltaMovement();
    double yaw = Math.toRadians(client.player.getYRot());
    double boost = client.player.isSprinting() ? SPRINT_JUMP_BOOST : 0.0;
    double vx = velocity.x - Math.sin(yaw) * boost;
    double vz = velocity.z + Math.cos(yaw) * boost;
    double speed = Math.hypot(vx, vz);
    if (speed < CRAWL_SPEED_FLOOR) {
      return false;
    }
    double dirX = vx / speed;
    double dirZ = vz / speed;
    double rise = target.getY() - client.player.getY();
    double x = client.player.getX();
    double z = client.player.getZ();
    double y = 0.0;
    double vy = JumpCapability.velocity();
    for (int tick = 0; tick < COMBAT_HOP_SIM_TICKS; tick++) {
      x += vx;
      y += vy;
      z += vz;
      if (vy < 0.0 && y <= rise) {
        return (x - target.getX()) * dirX + (z - target.getZ()) * dirZ > COMBAT_HOP_OVERSHOOT;
      }
      vy = (vy - FallPredictor.GRAVITY) * FallPredictor.VERTICAL_DRAG;
      vx *= FallPredictor.HORIZONTAL_DRAG;
      vz *= FallPredictor.HORIZONTAL_DRAG;
    }
    return false;
  }

  private boolean brakesCombatMomentum(FallPredictor.Steer steer) {
    Vec3 velocity = client.player.getDeltaMovement();
    double yaw = Math.toRadians(client.player.getYRot());
    double forward = -Math.sin(yaw) * velocity.x + Math.cos(yaw) * velocity.z;
    double right = -Math.cos(yaw) * velocity.x - Math.sin(yaw) * velocity.z;
    return switch (steer) {
      case NONE -> true;
      case FORWARD -> forward < 0.0;
      case BACK -> forward > 0.0;
      case LEFT -> right > 0.0;
      case RIGHT -> right < 0.0;
    };
  }

  /**
   * Steer the fall onto the tracked landing: predict where this arc ends up with no further input,
   * then hold the one key that shrinks the gap. Re-run every air tick, so once enough sideways
   * velocity has been added the prediction slides onto the target and this stops asking by itself -
   * no PID, no overshoot bookkeeping.
   *
   * <p>Exactly one key, and the others are cleared first: a diagonal impulse in the air curves the
   * arc, which is the nudge the airborne single-key gate exists to stop.
   */
  private void applyFallSteer(List<KeyMapping> keys) {
    if (fallTarget == null) {
      return;
    }
    FallPredictor.Landing landing =
        FallPredictor.simulate(
            client.player.position(), client.player.getDeltaMovement(), fallTarget.y);
    if (!landing.reached()) {
      return;
    }
    if (landing.ticks() < FALL_STEER_MIN_TICKS) {
      return;
    }

    double errorX = fallTarget.x - landing.x();
    double errorZ = fallTarget.z - landing.z();
    // NO reachability bail. The first version stood down when the error exceeded what the remaining
    // air
    // ticks could buy, on the theory that an unclosable gap only adds landing momentum. That
    // reasoning
    // is backwards, and it disabled the correction on exactly the drops that need it: air control
    // buys
    // 0.85 blocks over a 3-block drop while a sprint step-off carries 1.90, so the bail fired on
    // every
    // short ledge and the body fell as if the feature were not there. A partial correction still
    // halves
    // the miss, and halving it is what keeps the landing inside OFF_PATH_CAP and out of a replan.
    FallPredictor.Steer steer =
        FallPredictor.steer(
            errorX, errorZ, client.player.getYRot(), FALL_STEER_DEADZONE, lastFallSteer);
    KeyMapping key =
        switch (steer) {
          case FORWARD -> client.options.keyUp;
          case BACK -> client.options.keyDown;
          case LEFT -> client.options.keyLeft;
          case RIGHT -> client.options.keyRight;
          case NONE -> null;

          default -> throw new MatchException(null, null);
        };
    if (key == null) {
      lastFallSteer = FallPredictor.Steer.NONE;
      return;
    }
    if (steer != lastFallSteer) {
      lastFallSteer = steer;
      detail(
          "[FALL] steer %s: predicted (%.2f,%.2f) target (%.2f,%.2f) err %.2f, %d ticks left",
          steer,
          landing.x(),
          landing.z(),
          fallTarget.x,
          fallTarget.z,
          Math.hypot(errorX, errorZ),
          landing.ticks());
    }
    keys.remove(client.options.keyUp);
    keys.remove(client.options.keyDown);
    keys.remove(client.options.keyLeft);
    keys.remove(client.options.keyRight);
    keys.add(key);
  }

  /**
   * True when a plain drop is close enough ahead that the body should stop sprinting into it.
   *
   * <p>Momentum taken off a lip is momentum {@link FallPredictor} then has to fight with 0.02 a
   * tick of air control, and it mostly loses. Not sprinting is the cheap half of the fix, and the
   * half a person does without thinking: you walk up to the edge of a drop, you do not run at it.
   *
   * <p>Held off when a jump is planned ahead - that one WANTS the momentum, it is what carries the
   * body across the gap.
   */
  private int upcomingDropWaypoint() {
    if (trail.isEmpty() || !client.player.onGround() || plannedJumpAhead() != null) {
      return -1;
    }
    return upcomingDropWaypoint(client.player.getY());
  }

  private int upcomingDropWaypoint(double playerY) {
    double reach = sprintBrakeDistance();
    int scanEnd = Math.min(trail.size(), Math.max(0, trailCursor) + DROP_SCAN_NODES);
    int nearest = -1;
    double nearestGap = Double.POSITIVE_INFINITY;
    // Scans a bounded RUN of nodes rather than stopping at the first one out of range. Breaking
    // early
    // on distance assumed the cursor sits under the body and the trail marches away from it; when
    // the
    // cursor lags, node[cursor] is already past the brake reach and the loop ended before ever
    // seeing
    // the drop. Logged: two falls took off at full speed (0.502, 0.492) with no brake line at all,
    // and they are the only two in the run that missed by more than half a block.
    for (int i = Math.max(0, trailCursor); i < scanEnd; i++) {
      Vec3 node = trail.get(i);
      double gap = flatDist(client.player.getX(), client.player.getZ(), node.x, node.z);
      if (gap > reach) {
        continue;
      }
      if (mediumAt(i) != PathNode.Medium.LAND) {
        continue;
      }
      if (trailFootY(i) >= playerY - LEDGE_DROP_MIN_Y) {
        continue;
      }
      if (flowsThroughDrop(i, playerY)) {
        continue;
      }
      if (gap < nearestGap) {
        nearest = i;
        nearestGap = gap;
      }
    }
    return nearest;
  }

  /**
   * Release forward for the last stride into a drop, so the body steps off it rather than
   * launching.
   *
   * <p>Dropping sprint alone is not enough and the physics says so plainly: walking off carries
   * about 1.5 blocks over a short fall while air control can only claw back 0.85, so a walked
   * step-off still misses by more than {@link #OFF_PATH_CAP}. Ground friction with no input is
   * 0.546 a tick, so three ticks of nothing takes a sprint down to a crawl - the body then leaves
   * the lip almost straight down, which is exactly what the planner assumed when it put the landing
   * directly below.
   *
   * <p>Only while genuinely moving: once the speed is off, forward goes back on, so this brakes
   * rather than parks. A stall on the lip is the failure this must not cause.
   */
  private boolean inDropBrakeRange(double dropGap, double speed) {
    return dropGap
        <= Math.clamp(
            speed
                * STEP_OFF_BRAKE_TICKS
                * RyznSettings.get().pathfinder().walking().get(Control.DROP_LEAD)
                / 100.0,
            STEP_OFF_BRAKE_MIN_BLOCKS,
            STEP_OFF_BRAKE_MAX_BLOCKS);
  }

  /**
   * True for a short step down the route runs straight on from - a staircase or terraced slope.
   * Landing a little long there only lands further along the route, so braking into it and steering
   * the fall back onto the exact landing (S held mid-air) cost speed for nothing. Tall drops, and
   * drops where the route turns or ends at the landing, keep the careful treatment.
   */
  private boolean flowsThroughDrop(int landingIndex, double fromFootY) {
    if (fromFootY - trailFootY(landingIndex) > SHORT_DROP_MAX_Y) {
      return false;
    }
    Vec3 landing = trail.get(landingIndex);
    Vec3 before = null;
    for (int i = landingIndex - 1; i >= 0; i--) {
      if (flatDist(trail.get(i).x, trail.get(i).z, landing.x, landing.z) >= DROP_RUN_BLOCKS) {
        before = trail.get(i);
        break;
      }
    }
    Vec3 after = null;
    for (int i = landingIndex + 1; i < trail.size(); i++) {
      if (flatDist(trail.get(i).x, trail.get(i).z, landing.x, landing.z) >= DROP_RUN_BLOCKS) {
        after = trail.get(i);
        break;
      }
    }
    if (after == null) {
      return false;
    }
    double inX = before == null ? landing.x - client.player.getX() : landing.x - before.x;
    double inZ = before == null ? landing.z - client.player.getZ() : landing.z - before.z;
    double outX = after.x - landing.x;
    double outZ = after.z - landing.z;
    double lengths = Math.hypot(inX, inZ) * Math.hypot(outX, outZ);
    return lengths > 1.0e-6 && (inX * outX + inZ * outZ) / lengths >= DROP_RUN_COS;
  }

  /** How far out to drop sprint, in ticks of the body's own travel. See the brake constants. */
  private double sprintBrakeDistance() {
    double speed =
        Math.hypot(client.player.getDeltaMovement().x, client.player.getDeltaMovement().z);
    return Math.clamp(speed * SPRINT_BRAKE_TICKS, SPRINT_BRAKE_MIN_BLOCKS, SPRINT_BRAKE_MAX_BLOCKS);
  }

  private boolean tickRimRecovery() {
    BlockPos roundedSupport = client.player.blockPosition().below();
    if (rimRecovery.isEscaping()) {
      BlockPos target = rimRecovery.target();
      if (RimRecovery.safelyAtTarget(client.level, client.player, target)) {
        rimRecovery.markWaitingForRoute();
        clearRimRecoveryState();
        KeyBindUtil.releaseAllExcept();
        log("Stepped off the ledge at %s - asking for a fresh route", target);
        if (WalkingNavigation.requestReplan("landing on a ledge")) {
          return true;
        }
        error("Stepped off the ledge but could not ask for a new route");
        abortRimRecovery();
        return true;
      }

      RimRecoveryState.TickDecision decision =
          rimRecovery.observe(client.player.getX(), client.player.getZ(), client.player.onGround());
      tickAirState(client.player.onGround());
      if (decision == RimRecoveryState.TickDecision.PAUSE_AIRBORNE) {
        routeHeadingYaw = Float.NaN;
        KeyBindUtil.releaseAllExcept();
        return true;
      }
      if (decision == RimRecoveryState.TickDecision.ABORT) {
        log("Ledge escape to %s went nowhere - giving up on it", target);
        return failRimRecovery("rim escape aborted: target=" + target, "a failed ledge escape");
      }
      return holdRimRecovery();
    }

    boolean onUnsafeRim =
        client.player.onGround()
            && RimRecovery.shouldRecover(
                client.player.mainSupportingBlockPos,
                roundedSupport,
                client
                    .player
                    .mainSupportingBlockPos
                    .map(support -> RimRecovery.hasBodyHeightObstruction(client.level, support))
                    .orElse(false));
    if (!onUnsafeRim) {
      return false;
    }

    BlockPos support = client.player.mainSupportingBlockPos.orElseThrow();
    // Already searched this rim and found nothing - don't redo the 25-candidate sweep every tick.
    // The latch
    // clears itself as soon as the support cell changes, which it will now that the route keeps
    // driving.
    if (support.equals(rimEscapeFailedAt)) {
      return false;
    }

    Vec3 routeTarget =
        trailCursor >= 0 && trailCursor < trail.size() ? trail.get(trailCursor) : null;
    BlockPos escapeTarget =
        RimRecovery.findEscapeTarget(client.level, client.player, support, routeTarget)
            .orElse(null);
    // No escape cell: drive the route instead of killing it. The bot walked onto this rim, so the
    // route it
    // was already following is a way off. Bailing here stalled forever - the rim test is
    // positional, so the
    // caller's replan re-armed into the identical state ~1/s and the cursor never left 0.
    if (escapeTarget == null) {
      rimEscapeFailedAt = support.immutable();
      log(
          "Standing on a ledge with no safe step off (feet %s, "
              + "block %s) - walking the route anyway",
          roundedSupport, support);
      return false;
    }
    rimEscapeFailedAt = null;

    rimRecovery.begin(escapeTarget, client.player.getX(), client.player.getZ());
    clearRimRecoveryState();
    log(
        "obstructed rim recovery: feet=%s support=%s escape=%s",
        roundedSupport, support, escapeTarget);
    return holdRimRecovery();
  }

  private boolean shouldHoldSprint() {
    return WalkingPolicy.sprint(RyznSettings.get().pathfinder().isSprint(), sneak);
  }

  private boolean holdRimRecovery() {
    routeHeadingYaw = Float.NaN;
    RimRecovery.MovementIntent intent = recoveryIntent();
    if (!RimRecovery.nextMovementStepIsSafe(client.level, client.player, intent)) {
      log("obstructed rim key step became unsafe: target=%s key=%s", rimRecovery.target(), intent);
      return failRimRecovery(
          "rim escape step unsafe: target=" + rimRecovery.target(), "a failed ledge escape");
    }
    KeyMapping key = recoveryKey(intent);
    if (shouldHoldSprint()) {
      KeyBindUtil.holdThese(key, client.options.keySprint);
    } else {
      KeyBindUtil.holdThese(key);
    }
    return true;
  }

  private RimRecovery.MovementIntent recoveryIntent() {
    RimRecovery.MovementIntent intent =
        RimRecovery.movementIntent(
            client.player.getX(),
            client.player.getZ(),
            client.player.getYRot(),
            rimRecovery.target(),
            rimRecovery.intent());
    rimRecovery.updateIntent(intent);
    return intent;
  }

  private KeyMapping recoveryKey(RimRecovery.MovementIntent intent) {
    return switch (intent) {
      case FORWARD -> client.options.keyUp;
      case LEFT -> client.options.keyLeft;
      case BACKWARD -> client.options.keyDown;
      case RIGHT -> client.options.keyRight;

      default -> throw new MatchException(null, null);
    };
  }

  /**
   * A rim escape that cannot finish is a stall like any other, so it asks for a fresh route from
   * here before giving up. It used to call {@link #abortRimRecovery()} outright - the ONLY watchdog
   * in this class that quit on its first failure instead of spending the replan budget - which
   * ended the navigation and left the user to re-issue the command by hand. The escape state is
   * dropped first so the new route is followed normally rather than re-entering the escape that
   * just failed.
   */
  private boolean failRimRecovery(String reason, String chatReason) {
    rimRecovery.clear();
    clearRimRecoveryState();
    if (askReplan(reason, chatReason)) {
      return true;
    }
    error("%s - out of retries, stopping", reason);
    abortRimRecovery();
    return true;
  }

  private void abortRimRecovery() {
    stop();
    won = false;
    bailed = true;
  }

  private void clearRimRecoveryState() {
    stallWatch.clearStallWatches();
    movingStreak = 0;
    wallGrindTicks = 0;
    wallStallTicks = 0;
    previousY = client.player.getY();
    peekGate.reset(false);
    ledgeGate.reset(false);
    deadzoneGate.reset(false);
    movementController.reset();
    laneKeeper.reset();
    gazePlanner.reset();
  }

  /** Writes the held keys to the debug log whenever the set changes. */
  private void logHeldKeys(List<KeyMapping> keys) {
    KeyMapping[] order = {
      client.options.keyUp,
      client.options.keyDown,
      client.options.keyLeft,
      client.options.keyRight,
      client.options.keyJump,
      client.options.keyShift
    };
    String[] names = {"W", "S", "A", "D", "jump", "sneak"};
    int mask = 0;
    for (int i = 0; i < order.length; i++) {
      if (keys.contains(order[i])) {
        mask |= 1 << i;
      }
    }
    if (mask == loggedKeyMask) {
      return;
    }
    loggedKeyMask = mask;
    StringBuilder held = new StringBuilder();
    for (int i = 0; i < names.length; i++) {
      if ((mask & 1 << i) != 0) {
        held.append(held.isEmpty() ? "" : "+").append(names[i]);
      }
    }
    detail("[KEYS] %s", held.isEmpty() ? "none" : held);
  }

  private void clearUnsupportedStrafe(List<KeyMapping> keys, boolean keepStrafeForward) {
    int strafe =
        (keys.contains(client.options.keyLeft) ? 1 : 0)
            - (keys.contains(client.options.keyRight) ? 1 : 0);
    if (strafe == 0) {
      return;
    }
    double yaw = Math.toRadians(client.player.getYRot());
    boolean forward = keys.contains(client.options.keyUp);
    double step = Math.clamp(client.player.getDeltaMovement().horizontalDistance(), .25, .65);
    if (!forward
        && keepStrafeForward
        && !keys.contains(client.options.keyDown)
        && hopTerrain.safeWalkingLine(
            client.player.getX() + step * (strafe * Math.cos(yaw) - Math.sin(yaw)),
            client.player.getZ() + step * (strafe * Math.sin(yaw) + Math.cos(yaw)))) {
      keys.add(client.options.keyUp);
      return;
    }
    double forwardStep = forward ? step : 0;
    double x = client.player.getX() + step * strafe * Math.cos(yaw) - forwardStep * Math.sin(yaw);
    double z = client.player.getZ() + step * strafe * Math.sin(yaw) + forwardStep * Math.cos(yaw);
    if (!hopTerrain.safeWalkingLine(x, z)) {
      keys.remove(client.options.keyLeft);
      keys.remove(client.options.keyRight);
    }
  }

  private boolean holdsMovementKey(List<KeyMapping> keys) {
    return keys.contains(client.options.keyUp)
        || keys.contains(client.options.keyDown)
        || keys.contains(client.options.keyLeft)
        || keys.contains(client.options.keyRight);
  }

  /**
   * Protect jump and drop momentum, without pausing strafes after a confirmed half-step landing.
   */
  boolean holdStrafeOff() {
    return airTicks > 0 || landTicks <= LANDING_STRAFE_HOLD && landingNeedsStrafeHold;
  }

  private boolean canKeepRecordedAirStrafe(double aimX, double aimZ, int aimIndex) {
    CombatModule combat = RyznBootstrap.moduleManager().getByClass(CombatModule.class);
    return combat.isCombatNavActive()
        && combat.usesRecordedMovement()
        && Math.abs(trailFootY(aimIndex) - takeoffY) <= CLIMB_REACH_GATE_Y
        && upcomingDropWaypoint(takeoffY) < 0
        && hopTerrain.safeAirStrafeLine(takeoffY, aimX, aimZ);
  }

  /**
   * True once the body is in a plain FALL rather than a hop, and far enough below the lip to be
   * clear of it.
   *
   * <p>Forward is deliberately held through a hop - that is what carries the body across a gap -
   * but a ledge drop is not a hop, and a 20-block fall is ~40 ticks of held forward. Minecraft
   * still applies air control and the takeoff sprint momentum, so the body sails several blocks
   * past the planned landing and lands off the route (the 2-7 block "off the path" replans). Worse,
   * direction comes from yaw alone (pitch is irrelevant to movement - the movement goal is built
   * with the player's own Y substituted in), so aiming down at a node almost underfoot gives a
   * near-zero heading whose yaw is noise, and forward pushes the body sideways at random for the
   * whole descent. Releasing forward here lets the fall go where gravity says.
   */
  private boolean fallingFreely() {
    return airTicks > 0
        && !airFromJump
        && !client.player.isInWater()
        && takeoffY - client.player.getY() > FALL_RELEASE_DROP;
  }

  /**
   * Smallest true-3D distance from the player to any segment of the planned trail. Clamping to
   * segment ends means a body displaced ALONG the path (overshot / fell behind) or dropped to
   * another level reads as off-path, not just one shoved sideways - this is the real "am I still on
   * the route" measure.
   */
  /**
   * A point on the route ahead of where the body actually is, or null when the body is close enough
   * to the line to leave the ordinary node aim alone.
   *
   * <p>Projects the body onto the trail polyline and walks {@link #REJOIN_LEAD} blocks further
   * along it. A point on the route can still cut a stair corner when approached from off the line:
   * prefer the ordinary target if it is walkable and the next two measured-speed ticks of the
   * rejoin chord are not. Leave farther climbs to hop handling. The lead damps the correction.
   *
   * <p>Searched forward from the node before the cursor only, so a switchback that brings the route
   * back alongside itself cannot capture the projection onto the returning leg.
   */
  private Vec3 rejoinTarget(double fallbackX, double fallbackZ) {
    if (trail.size() < 2 || client.player == null) {
      return null;
    }

    Vec3 body = new Vec3(client.player.getX(), client.player.getY(), client.player.getZ());
    int from = Math.max(1, Math.min(trailCursor, trail.size() - 1));
    int end = Math.min(trail.size(), from + REJOIN_SCAN_NODES);

    int bestSegment = -1;
    double bestFraction = 0.0;
    double bestDistance = Double.MAX_VALUE;
    for (int index = from; index < end; index++) {
      Vec3 a = footPoint(index - 1);
      Vec3 b = footPoint(index);
      double fraction = flatProjection(body, a, b);
      double x = a.x + (b.x - a.x) * fraction;
      double z = a.z + (b.z - a.z) * fraction;
      double distance = flatDist(body.x, body.z, x, z);
      if (distance < bestDistance) {
        bestDistance = distance;
        bestSegment = index;
        bestFraction = fraction;
      }
    }
    if (bestSegment < 0 || bestDistance <= REJOIN_OFF_LINE) {
      return null;
    }

    // Walk REJOIN_LEAD blocks further along the polyline from that projection.
    double remaining = REJOIN_LEAD;
    Vec3 a = footPoint(bestSegment - 1);
    Vec3 b = footPoint(bestSegment);
    double x = a.x + (b.x - a.x) * bestFraction;
    double z = a.z + (b.z - a.z) * bestFraction;
    for (int index = bestSegment; index < trail.size(); index++) {
      Vec3 next = footPoint(index);
      double segment = flatDist(x, z, next.x, next.z);
      if (segment >= remaining) {
        if (segment < 1.0e-6) {
          break;
        }
        double step = remaining / segment;
        return preferWalkableRejoin(
            new Vec3(x + (next.x - x) * step, next.y, z + (next.z - z) * step),
            fallbackX,
            fallbackZ);
      }
      remaining -= segment;
      x = next.x;
      z = next.z;
    }
    return preferWalkableRejoin(
        new Vec3(x, footPoint(trail.size() - 1).y, z), fallbackX, fallbackZ);
  }

  private Vec3 preferWalkableRejoin(Vec3 target, double fallbackX, double fallbackZ) {
    double fromX = client.player.getX();
    double fromZ = client.player.getZ();
    double deltaX = target.x - fromX;
    double deltaZ = target.z - fromZ;
    Vec3 velocity = client.player.getDeltaMovement();
    double reach = Math.max(client.player.getBbWidth(), Math.hypot(velocity.x, velocity.z) * 2.0);
    double fraction = Math.min(1.0, reach / Math.max(1.0E-6, Math.hypot(deltaX, deltaZ)));
    return hopTerrain.bodyLineWalkable(fromX + deltaX * fraction, fromZ + deltaZ * fraction)
            || !hopTerrain.bodyLineWalkable(fallbackX, fallbackZ)
        ? target
        : null;
  }

  /** Where {@code point} falls along segment a-b, measured flat, clamped to the segment. */
  private static double flatProjection(Vec3 point, Vec3 a, Vec3 b) {
    double deltaX = b.x - a.x;
    double deltaZ = b.z - a.z;
    double lengthSquared = deltaX * deltaX + deltaZ * deltaZ;
    if (lengthSquared < 1.0e-12) {
      return 0.0;
    }
    return Math.clamp(
        ((point.x - a.x) * deltaX + (point.z - a.z) * deltaZ) / lengthSquared, 0.0, 1.0);
  }

  double nearestPathDistance() {
    Vec3 p = new Vec3(client.player.getX(), client.player.getY(), client.player.getZ());
    double best = Double.MAX_VALUE;
    for (int i = 1; i < trail.size(); i++) {
      // Compared at FOOT height (see trailFootY). Measuring the raw trail Y put a constant
      // one-block
      // error in the vertical term, so a body tracking the route perfectly read as 1.0 off and the
      // cap
      // was really only ever bounding sqrt(cap^2 - 1) of lateral drift.
      double d = pointToSegmentDist(p, footPoint(i - 1), footPoint(i));
      if (d < best) {
        best = d;
      }
    }
    return best;
  }

  /** Trail point {@code index} lifted to the height a body standing on it occupies. */
  private Vec3 footPoint(int index) {
    Vec3 point = trail.get(index);
    double y = trailFootY(index);
    return y == point.y ? point : new Vec3(point.x, y, point.z);
  }

  /**
   * Ask WalkingNavigation for a new route from where the player is now to the saved goal, and stand
   * down so its tick re-arms this driver once the new path lands. Returns false when the replan
   * budget is gone / native is missing, so the caller drops through to a hard abort.
   */
  boolean askReplan(String reason, String chatReason) {
    // Already searching for exactly this - keep walking the current route until it lands. Asking
    // again
    // every tick spent the whole replan budget in under a second and cancelled the in-flight search
    // each
    // time, so the new route never arrived.
    if (WalkingNavigation.isSearchPending()) {
      stallWatch.clearStallWatches();
      return true;
    }
    if (WalkingNavigation.requestReplan(chatReason)) {
      log("replan asked: %s", reason);
      stallWatch.clearStallWatches();
      // Deliberately NOT stopping the driver. This used to set running=false and release every
      // movement
      // key, then re-arm through startPath once the search resolved - a dead stop of at least a
      // tick
      // (up to 3s in the logs) on top of a rotation-controller reset, which is what a wedge replan
      // actually felt like in game. The route is stale for the two or three ticks the search takes,
      // but
      // stale is a better guess than frozen, and WalkingNavigation hot-swaps it in place when it
      // lands.
      return true;
    }
    return false;
  }

  /** True-3D distance from point p to the segment a-b, clamped to the segment's ends. */
  private static double pointToSegmentDist(Vec3 p, Vec3 a, Vec3 b) {
    double abx = b.x - a.x;
    double aby = b.y - a.y;
    double abz = b.z - a.z;
    double seg2 = abx * abx + aby * aby + abz * abz;
    if (seg2 < 1.0e-9) {
      return p.distanceTo(a);
    }
    double t = ((p.x - a.x) * abx + (p.y - a.y) * aby + (p.z - a.z) * abz) / seg2;
    t = Math.max(0.0, Math.min(1.0, t));
    double cx = a.x + abx * t;
    double cy = a.y + aby * t;
    double cz = a.z + abz * t;
    double dx = p.x - cx;
    double dy = p.y - cy;
    double dz = p.z - cz;
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  /**
   * Pull the live turn ceiling / node peek / reach radius (and sprint) from the current {@code
   * RyznSettings.pathSpeedMode}. NORMAL/FAST/EXTREME are fixed bundles for vanilla / potion /
   * plugin-boosted speeds; AUTO scales them smoothly off MEASURED speedNow so it self-adjusts to
   * whatever the server sets; CUSTOM uses the baseline constants. Sprint tracks the RyznSettings
   * toggle in every mode.
   */
  private void refreshSpeedProfile() {
    double measuredSpeed =
        client.player == null
            ? 0.0
            : Math.hypot(client.player.getDeltaMovement().x, client.player.getDeltaMovement().z);
    PathSpeedProfile profile =
        movementMode.shape(PathSpeedProfile.from(RyznSettings.get().pathfinder(), measuredSpeed));
    livePeek = profile.lookAhead();
    liveReach = profile.reachRadius();
    CombatModule combat = RyznBootstrap.moduleManager().getByClass(CombatModule.class);
    boolean recordedCombat = combat.isCombatNavActive() && combat.usesRecordedMovement();
    rotationController.setRecordedMovement(recordedCombat);
    gazePlanner.setRecordedCombat(recordedCombat);
    rotationController.configureTurnBounds(profile.amountMinimum(), profile.amountMaximum());
    rotationController.setFreeAim(true);
  }

  /**
   * Follows the constant-jumping setting mid-route, but only with the feet on the ground: switching
   * mid-arc would hand a committed hop to a mode that does not expect to be in the air.
   */
  private void refreshMovementMode() {
    GroundMovementMode wanted = GroundMovementMode.forSetting(AutomaticHop.enabled(client));
    if (wanted != movementMode && client.player.onGround()) {
      log("Movement mode %s -> %s", movementMode.label(), wanted.label());
      movementMode = wanted;
    }
  }

  // trace shims

  void log(String msg, Object... parts) {
    DebugLog.trace(DebugLog.Category.NAV, msg, parts);
  }

  /** Diagnostics dump: log file only, never chat. */
  static void detail(String msg, Object... parts) {
    DebugLog.detail(DebugLog.Category.NAV, msg, parts);
  }

  void error(String msg, Object... parts) {
    DebugLog.error(DebugLog.Category.NAV, msg, parts);
  }

  // run phases

  public enum State {
    STARTING_PATH,
    TRAVERSING,
    JUMPING,
    WAITING,
    END
  }
}
