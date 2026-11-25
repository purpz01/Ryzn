package ryzn.macro.client.feature.macros.impl.tree;

import static ryzn.macro.client.MacroInput.releaseAttackAndUse;
import static ryzn.macro.client.MacroInput.releaseMovementKeys;

import java.util.concurrent.ThreadLocalRandom;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.input.MacroKeys;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class TreeChopMotion {

  private static final float STEP_FACING_LIMIT_DEGREES = 25f;
  private static final float STEP_PITCH_DEGREES = 12f;
  // Below this horizontal eye->aim distance the look is near-vertical (e.g. centered under a
  // same-column
  // trunk), so atan2(dz,dx) is dominated by sub-block position noise and the yaw would spin. Hold
  // it instead.
  private static final double MIN_YAW_HORIZONTAL = 0.5;
  private static final double LEAD_PER_TICK = 0.010;
  private static final double LEAD_MAX = 0.26;
  private static final double LEAD_SWAY = 0.012;
  private static final double LEAD_FACE_MARGIN = 0.12;

  /**
   * How far the watch look may sit off the route heading. Under TreeChopGaze's 40 degree backstep
   * limit, so the executor keeps resolving the walk onto W.
   */
  private static final float WATCH_OFF_ROUTE_DEGREES = 32f;

  private static final float WATCH_PITCH_MIN = -12f;
  private static final float WATCH_PITCH_MAX = 18f;
  private static final float WATCH_SMOOTH_SECONDS = 0.35f;
  private static final float WATCH_GAIN_PER_SECOND = 3.5f;
  private static final float ACQUIRE_SMOOTH_SECONDS = 0.14f;
  private static final float ACQUIRE_GAIN_PER_SECOND = 9f;

  private final TreeChopPhase phase;
  private final TreeLookMotion lookMotion = new TreeLookMotion();

  @Getter(AccessLevel.PACKAGE)
  private boolean controllingLook;

  /** Set while the head is held on the tree DURING a route - see TreeChopGaze. */
  @Setter(AccessLevel.PACKAGE)
  private boolean routeGaze;

  @Getter(AccessLevel.PACKAGE)
  private boolean repositioning;

  private BlockPos lookTargetLog;
  private float heldYaw;
  private boolean hasHeldYaw;

  /** True while the head is only drifting toward a tree, not aimed at a log. */
  private boolean watching;

  boolean ownsRouteLook() {
    return routeGaze && controllingLook;
  }

  void release(Options options) {
    controllingLook = false;
    routeGaze = false;
    lookMotion.clear();
    lookTargetLog = null;
    hasHeldYaw = false;
    watching = false;
    releaseAttackAndUse(options);
  }

  void beginMovementTick() {
    repositioning = false;
  }

  void releaseAttack(Options options) {
    releaseAttackAndUse(options);
  }

  /**
   * Plant the feet for the whole tree.
   *
   * <p>This used to walk the player onto the trunk column and keep pressing into its centre, with a
   * recontact loop behind it. Nothing in the reference footage does that: the player stops beside
   * the trunk and stays exactly there - 30 consecutive samples at one coordinate across six seconds
   * - and fells the tree with the camera alone. Shuffling into a block you are about to mine has no
   * human counterpart, so the drive is gone and the "centred" latch is satisfied on arrival, which
   * is what the phase machinery downstream waits on.
   */
  void driveTowardLogOrStop(Options options, LocalPlayer player, BlockPos log) {
    if (options == null) {
      return;
    }
    releaseMovementKeys(options);
    phase.markCenteredAtTrunk();
  }

  /**
   * Tick glide: the aim point on {@code log}, drifting toward {@code next} as the break progresses,
   * plus a small sway so the crosshair is never frozen mid-break.
   *
   * <p>The drift is what makes one log flow into the next instead of the camera snapping across
   * after each break lands. It is clamped inside the current block's own face by {@link
   * #LEAD_FACE_MARGIN}: if the crosshair ever leaves the block being mined the server resets its
   * break progress, which costs far more than the head start is worth. So the lead buys the
   * rotation up front and the final hop is tiny.
   */
  Vec3 leadAimPoint(LocalPlayer player, BlockPos log, BlockPos next, int breakTicks) {
    Vec3 base = TreeLogTargeting.aimPointForLog(player, log);
    TreeChopMacro settings = RyznBootstrap.macroManager().getByClass(TreeChopMacro.class);
    if (settings.chopOvershootChance() == 0 || settings.chopOvershootDegrees() == 0) {
      return base;
    }
    double lead = next == null ? 0.0 : Math.min(LEAD_MAX, Math.max(0, breakTicks) * LEAD_PER_TICK);
    double sway = Math.sin(breakTicks * 0.27) * LEAD_SWAY;
    double x = base.x + sway;
    double y = base.y + Math.sin(breakTicks * 0.19) * LEAD_SWAY;
    double z = base.z + sway;
    if (lead > 0.0) {
      double dx = (next.getX() + 0.5) - (log.getX() + 0.5);
      double dy = (next.getY() + 0.5) - (log.getY() + 0.5);
      double dz = (next.getZ() + 0.5) - (log.getZ() + 0.5);
      double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
      if (length > 1.0E-6) {
        x += dx / length * lead;
        y += dy / length * lead;
        z += dz / length * lead;
      }
    }
    return new Vec3(
        Mth.clamp(x, log.getX() + LEAD_FACE_MARGIN, log.getX() + 1.0 - LEAD_FACE_MARGIN),
        Mth.clamp(y, log.getY() + LEAD_FACE_MARGIN, log.getY() + 1.0 - LEAD_FACE_MARGIN),
        Mth.clamp(z, log.getZ() + LEAD_FACE_MARGIN, log.getZ() + 1.0 - LEAD_FACE_MARGIN));
  }

  Vec3 contactAimPoint(LocalPlayer player, BlockPos log) {
    Vec3 aim = TreeLogTargeting.aimPointForLog(player, log);
    double eyeY = player.getEyePosition().y;
    return !phase.isHigh() && aim.y > eyeY ? new Vec3(aim.x, eyeY, aim.z) : aim;
  }

  /**
   * Travel look: yaw onto the bearing to {@code target}, pitch just below the horizon. A human
   * walking to the next tree points the camera where their body is going; the macro used to stay
   * locked on a canopy log the whole way there, which is head-and-body disagreement no player
   * produces.
   */
  void setTravelLook(LocalPlayer player, BlockPos target, float pitchDegrees) {
    Vec3 eye = player.getEyePosition();
    double dx = (target.getX() + 0.5) - eye.x;
    double dz = (target.getZ() + 0.5) - eye.z;
    if (dx * dx + dz * dz < 1.0E-6) {
      return;
    }
    float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    setTravelLook(player, yaw, pitchDegrees);
  }

  private void setTravelLook(LocalPlayer player, float yaw, float pitchDegrees) {
    heldYaw = yaw;
    hasHeldYaw = true;
    boolean targetChanged = lookTargetLog != null || watching;
    lookTargetLog = null;
    watching = false;
    lookMotion.setTarget(
        yaw, pitchDegrees, player.getYRot(), player.getXRot(), targetChanged, false);
    controllingLook = true;
  }

  /**
   * Walk-in look: drift toward {@code trunk} without locking onto any log. On a route the yaw stays
   * within {@link #WATCH_OFF_ROUTE_DEGREES} of where the body is going and the pitch stays near the
   * horizon, so it reads as glancing at the next tree rather than staring at a block, possibly
   * through the leaves in between.
   */
  void setWatchLook(LocalPlayer player, BlockPos trunk) {
    Vec3 eye = player.getEyePosition();
    Vec3 aim = TreeChopGaze.aimPoint(trunk);
    double dx = aim.x - eye.x;
    double dz = aim.z - eye.z;
    double horizontal = Math.sqrt(dx * dx + dz * dz);
    if (horizontal < MIN_YAW_HORIZONTAL) {
      return;
    }
    float bearing = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    float routeYaw = WalkingNavigation.routeHeadingYaw();
    float yaw =
        Float.isNaN(routeYaw)
            ? bearing
            : routeYaw
                + Mth.clamp(
                    Mth.wrapDegrees(bearing - routeYaw),
                    -WATCH_OFF_ROUTE_DEGREES,
                    WATCH_OFF_ROUTE_DEGREES);
    float pitch =
        Mth.clamp(
            (float) -Math.toDegrees(Math.atan2(aim.y - eye.y, horizontal)),
            WATCH_PITCH_MIN,
            WATCH_PITCH_MAX);
    heldYaw = yaw;
    hasHeldYaw = true;
    boolean targetChanged = lookTargetLog != null || !watching;
    lookTargetLog = null;
    watching = true;
    lookMotion.setTarget(yaw, pitch, player.getYRot(), player.getXRot(), targetChanged, false);
    lookMotion.soften(WATCH_SMOOTH_SECONDS, WATCH_GAIN_PER_SECOND);
    controllingLook = true;
  }

  void setLookTarget(LocalPlayer player, BlockPos log, Vec3 target) {
    Vec3 eye = player.getEyePosition();
    double dx = target.x - eye.x;
    double dy = target.y - eye.y;
    double dz = target.z - eye.z;
    double horizontal = Math.sqrt(dx * dx + dz * dz);
    final float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
    // Near-vertical aim (log straight above/below): dx/dz are sub-block position noise, so a fresh
    // atan2 yaw
    // swings across the whole circle as the body micro-adjusts under the trunk - the camera spins
    // instead of
    // holding on the log. Yaw is visually irrelevant at this pitch, so hold the last stable yaw.
    float yaw;
    if (horizontal < MIN_YAW_HORIZONTAL) {
      yaw = hasHeldYaw ? heldYaw : player.getYRot();
    } else {
      yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
      heldYaw = yaw;
      hasHeldYaw = true;
    }
    if (!lookMotion.hasTarget()) {
      lookTargetLog = null;
    }
    boolean targetChanged = log == null || !log.equals(lookTargetLog);
    boolean adjacentTarget =
        targetChanged && log != null && TreeLogTargeting.isAdjacentLog(lookTargetLog, log);
    if (targetChanged) {
      lookTargetLog = log;
    }
    lookMotion.setTarget(
        yaw, pitch, player.getYRot(), player.getXRot(), targetChanged, adjacentTarget);
    if (watching) {
      // First log after watching the tree: a smooth turn onto it, not the fast re-aim.
      lookMotion.soften(ACQUIRE_SMOOTH_SECONDS, ACQUIRE_GAIN_PER_SECOND);
      watching = false;
    }
    if (targetChanged) {
      TreeChopMacro settings = RyznBootstrap.macroManager().getByClass(TreeChopMacro.class);
      if (ThreadLocalRandom.current().nextInt(100) < settings.chopOvershootChance()) {
        lookMotion.overshoot(player.getYRot(), player.getXRot(), settings.chopOvershootDegrees());
      }
    }
    controllingLook = true;
  }

  void correctBlockedAim(LocalPlayer player) {
    if (lookTargetLog == null) {
      return;
    }
    setLookTarget(player, lookTargetLog, Vec3.atCenterOf(lookTargetLog));
    lookMotion.correctAim();
  }

  BlockPos aimedLog(LocalPlayer player) {
    if (lookTargetLog == null) {
      return null;
    }
    Vec3 direction = Vec3.atCenterOf(lookTargetLog).subtract(player.getEyePosition()).normalize();
    return player.getLookAngle().dot(direction) > Math.cos(Math.toRadians(8))
        ? lookTargetLog
        : null;
  }

  /**
   * Wall-clock aim ease (TreeLookMotion is already nanoTime-driven). Called from the game tick,
   * which keeps the aim turning while rendering is throttled, AND from the render frame, which
   * re-samples the same curve at frame rate. Easing by real elapsed time is what makes driving it
   * from both loops self-consistent.
   */
  void tickLook() {
    // A live route normally owns the head. The one exception is the gaze handover: the executor
    // yields the
    // head (RotationManager.externalOwnsLook) and keeps steering the body, so the macro aims at the
    // tree
    // it is
    // walking to instead of down the path.
    if (!controllingLook
        || !lookMotion.hasTarget()
        || (WalkingNavigation.isNavigationActive() && !routeGaze)) {
      lookMotion.pause();
      return;
    }
    LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) {
      return;
    }
    var rotation = RyznBootstrap.rotationManager();
    rotation.markCamera(player);
    TreeChopMacro settings = RyznBootstrap.macroManager().getByClass(TreeChopMacro.class);
    if (settings.chopOvershootChance() == 0 || settings.chopOvershootDegrees() == 0) {
      lookMotion.cancelOvershoot();
    }
    TreeLookMotion.LookFrame frame =
        lookMotion.advance(
            player.getYRot(),
            player.getXRot(),
            System.nanoTime(),
            settings.chopRotationSpeed(),
            settings.chopLookAccel(),
            settings.chopLookDeadzone());
    if (frame.moved()) {
      rotation.applyLook(player, frame.yaw(), frame.pitch());
    }
  }

  boolean driveTowardStand(Options options, LocalPlayer player, BlockPos stand, double arrive) {
    return driveTowardStand(options, player, stand, arrive, false);
  }

  boolean driveTowardStand(
      Options options, LocalPlayer player, BlockPos stand, double arrive, boolean allowStepJump) {
    if (stand == null) {
      releaseMovementKeys(options);
      return false;
    }
    boolean jumpForStep =
        allowStepJump
            && player != null
            && TreeClusterTarget.needsStepJump(player.getY(), stand.getY());
    return driveTowardHorizontal(
        options, player, stand.getX() + 0.5, stand.getZ() + 0.5, arrive, jumpForStep);
  }

  boolean driveTowardHorizontal(
      Options options, LocalPlayer player, double targetX, double targetZ, double arrive) {
    return driveTowardHorizontal(options, player, targetX, targetZ, arrive, false);
  }

  private boolean driveTowardHorizontal(
      Options options,
      LocalPlayer player,
      double targetX,
      double targetZ,
      double arrive,
      boolean jumpForStep) {
    if (options == null || player == null) {
      releaseMovementKeys(options);
      return false;
    }
    double dx = targetX - player.getX();
    double dz = targetZ - player.getZ();
    if (dx * dx + dz * dz <= arrive * arrive && !jumpForStep) {
      releaseMovementKeys(options);
      return true;
    }
    repositioning = true;
    if (dx * dx + dz * dz > 1.0E-6) {
      float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
      setTravelLook(player, yaw, STEP_PITCH_DEGREES);
      if (Math.abs(Mth.wrapDegrees(yaw - player.getYRot())) > STEP_FACING_LIMIT_DEGREES) {
        releaseMovementKeys(options);
        return false;
      }
    }
    releaseMovementKeys(options);
    MacroKeys.set(options.keyJump, jumpForStep);
    MacroKeys.set(options.keyUp, dx * dx + dz * dz > arrive * arrive);
    return false;
  }
}
