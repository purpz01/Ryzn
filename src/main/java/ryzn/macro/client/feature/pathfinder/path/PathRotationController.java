package ryzn.macro.client.feature.pathfinder.path;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.groups.WalkingSettings.Control;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.rotation.impl.Angle;
import ryzn.macro.client.feature.rotation.impl.AngleUtil;
import ryzn.macro.client.feature.rotation.impl.abcurves.AbcRotation;

/**
 * Owns the pathfinder's ABCurves session and between-tick camera interpolation.
 *
 * <p>Drives a {@link PathLook} rather than the game directly, so the whole pipeline runs in the
 * headless sandbox against a simulated head. See {@link PathLook} for why.
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
final class PathRotationController {
  static final float DEFAULT_YAW_RATE_FLOOR = 12.0f;
  static final float DEFAULT_YAW_RATE_CEIL = 40.0f;

  private static final float GYRO_HOLD_BAND_DEGREES = 7.0f;

  /**
   * How far the aim may drift from the yaw a jump took off with, while the body is off the ground.
   *
   * <p>Airborne the gaze NODE is latched ({@link GazeStringPull}) but the ANGLE to it is not, and
   * at close range that is the half that matters: a latched point one or two blocks away swings
   * tens of degrees a tick once the body is displaced sideways. A jump into a riser does exactly
   * that - the step blocks one axis, the body slides along the other, and the bearing to the node
   * it is sliding past runs away. The head chased it faithfully and landed the body over 100
   * degrees off the route, which is the staircase zigzag and the corner overshoot loop.
   *
   * <p>A jump is a committed direction. This band keeps the small corrections a curving route wants
   * and drops the whip. Air control is a fifth of ground authority anyway, so chasing a runaway
   * bearing mid-flight buys almost no steering and costs the heading the body has to land on.
   */
  private static final float AIR_AIM_DRIFT_DEGREES = 35.0f;

  private final PathLook look;

  @Getter(AccessLevel.PACKAGE)
  private float aimYaw;

  @Getter(AccessLevel.PACKAGE)
  private float aimPitch;

  private Angle lastRouteGaze;
  private Angle swipeTarget;
  private int swipeTicks;
  private final AbcRotation.Session abc = new AbcRotation.Session();

  private float frameYaw;
  private float framePitch;
  private float frameYawPrevious;
  private float framePitchPrevious;
  private boolean frameRotationReady;
  private boolean externalHandlerWasDriving;
  private boolean yawHolding;

  /**
   * Aim yaw the current hop took off with, and whether one is being held. See
   * AIR_AIM_DRIFT_DEGREES.
   */
  private float airborneAimYaw;

  private boolean airborneAimHeld;

  private float airbornePitch;
  private boolean airbornePitchHeld;
  private boolean airbornePitchHoldEnabled = true;
  private boolean lastAimWasGaze;

  private float amountMinimum;
  private float amountMaximum = 180.0f;

  /** Aim exactly where asked: no pitch band and no airborne yaw hold. */
  private boolean freeAim;

  private boolean recordedMovement;

  void setRecordedMovement(boolean enabled) {
    recordedMovement = enabled;
  }

  void setFreeAim(boolean freeAim) {
    this.freeAim = freeAim;
  }

  void setAirbornePitchHold(boolean enabled) {
    airbornePitchHoldEnabled = enabled;
  }

  void onPathSet() {
    clearSwipe();
    if (!look.present()) {
      return;
    }
    aimYaw = AngleUtil.normalizeAngle(look.yaw());
    aimPitch = look.pitch();
  }

  void reset() {
    clearSwipe();
    abc.reset();
    yawHolding = false;
    frameRotationReady = false;
    externalHandlerWasDriving = false;
    airborneAimHeld = false;
    airbornePitchHeld = false;
    airbornePitchHoldEnabled = true;
  }

  void configureTurnBounds(float minimum, float maximum) {
    amountMinimum = minimum;
    amountMaximum = maximum;
  }

  void resumeAfterExternalLook() {
    if (!externalHandlerWasDriving || !look.present()) {
      return;
    }
    syncToPlayerLook();
    externalHandlerWasDriving = false;
  }

  void yieldToExternalLook() {
    clearSwipe();
    if (!look.present()) {
      return;
    }
    syncToPlayerLook();
    externalHandlerWasDriving = true;
  }

  void aimAt(
      Angle rawTarget,
      double horizontalDistance,
      boolean preciseLook,
      int airTicks,
      boolean verticalGazeOpen,
      boolean swipeEligible) {
    if (rawTarget == null) {
      return;
    }
    rawTarget = routeGaze(rawTarget, swipeEligible);
    aimYaw =
        applyTurnDeadzone(holdAirborneAim(AngleUtil.normalizeAngle(rawTarget.getYaw()), airTicks));
    aimPitch = holdAirbornePitch(rawTarget.getPitch(), airTicks);
    lastAimWasGaze = true;
    driveAim(preciseLook, verticalGazeOpen, false);
  }

  private Angle routeGaze(Angle target, boolean eligible) {
    var walking = RyznSettings.get().pathfinder().walking();
    if (!eligible || !walking.enabled(Control.SWIPES)) {
      clearSwipe();
      return target;
    }
    Angle previous = lastRouteGaze;
    lastRouteGaze = target;
    if (swipeTarget != null) {
      double gap =
          Math.hypot(
              AngleUtil.normalizeAngle(swipeTarget.getYaw() - look.yaw()),
              swipeTarget.getPitch() - look.pitch());
      if (gap <= walking.get(Control.SWIPE_TOLERANCE) || --swipeTicks == 0) {
        swipeTarget = null;
        return target;
      }
      return swipeTarget;
    }
    if (previous != null
        && Math.hypot(
                AngleUtil.normalizeAngle(target.getYaw() - previous.getYaw()),
                target.getPitch() - previous.getPitch())
            >= walking.get(Control.SWIPE_ANGLE)) {
      swipeTarget = target;
      swipeTicks = walking.get(Control.SWIPE_DURATION);
    }
    return target;
  }

  private void clearSwipe() {
    lastRouteGaze = null;
    swipeTarget = null;
  }

  /**
   * Keeps the pitch the hop took off with until the feet are down again. The gaze point is fixed
   * but the eye rises and falls about a block through every jump, so the true line to it swings
   * several degrees per hop; with constant jumping that read as the head nodding on every jump.
   */
  private float holdAirbornePitch(float targetPitch, int airTicks) {
    if (airTicks <= 0 || freeAim || !airbornePitchHoldEnabled) {
      airbornePitchHeld = false;
      return targetPitch;
    }
    if (!airbornePitchHeld) {
      if (!lastAimWasGaze) {
        // Airborne straight out of an exact aim (an Instant Transmission landing): that pitch
        // was the ability's, not a walking look, so do not pin the head to it.
        return targetPitch;
      }
      airbornePitch = aimPitch; // still last tick's aim: the one the takeoff committed to
      airbornePitchHeld = true;
    }
    return airbornePitch;
  }

  /**
   * Aims at {@code target} with no turn deadzone or airborne yaw hold, for an ability that must
   * fire along an exact bearing.
   */
  void aimExact(Angle target, double horizontalDistance) {
    clearSwipe();
    if (target == null) {
      return;
    }
    yawHolding = false;
    lastAimWasGaze = false;
    airbornePitchHeld = false;
    aimYaw = AngleUtil.normalizeAngle(target.getYaw());
    aimPitch = target.getPitch();
    driveAim(true, false, true);
  }

  /**
   * Clamps the raw aim to {@link #AIR_AIM_DRIFT_DEGREES} of the yaw this hop left the ground with.
   */
  private float holdAirborneAim(float targetYaw, int airTicks) {
    if (airTicks <= 0 || freeAim) {
      airborneAimHeld = false;
      return targetYaw;
    }
    if (!airborneAimHeld) {
      airborneAimYaw = aimYaw; // still last tick's aim: the one the takeoff committed to
      airborneAimHeld = true;
    }
    float drift = AngleUtil.normalizeAngle(targetYaw - airborneAimYaw);
    if (Math.abs(drift) <= AIR_AIM_DRIFT_DEGREES) {
      return targetYaw;
    }
    return AngleUtil.normalizeAngle(airborneAimYaw + Math.signum(drift) * AIR_AIM_DRIFT_DEGREES);
  }

  boolean isInterpolating(boolean running) {
    return running && frameRotationReady && !RyznBootstrap.rotationManager().externalOwnsLook();
  }

  private void syncToPlayerLook() {
    abc.reset();
    float liveYaw = look.yaw();
    float livePitch = look.pitch();
    aimYaw = AngleUtil.normalizeAngle(liveYaw);
    aimPitch = livePitch;
    frameYaw = liveYaw;
    framePitch = livePitch;
    frameYawPrevious = liveYaw;
    framePitchPrevious = livePitch;
  }

  private float applyTurnDeadzone(float targetYaw) {
    if (!look.present()) {
      return targetYaw;
    }
    float currentYaw = look.yaw();
    float turn = AngleUtil.normalizeAngle(targetYaw - currentYaw);
    float amount = Math.abs(turn);

    if (yawHolding) {
      if (amount <= amountMinimum + GYRO_HOLD_BAND_DEGREES) {
        return currentYaw;
      }
      yawHolding = false;
    } else if (amount < amountMinimum) {
      yawHolding = true;
      return currentYaw;
    }
    if (amount > amountMaximum) {
      turn = Math.signum(turn) * amountMaximum;
    }
    return AngleUtil.normalizeAngle(currentYaw + turn);
  }

  private void driveAim(boolean preciseLook, boolean verticalGazeOpen, boolean exactAim) {
    if (!look.present()) {
      return;
    }
    if (!frameRotationReady) {
      frameYaw = look.yaw();
      framePitch = look.pitch();
      frameYawPrevious = frameYaw;
      framePitchPrevious = framePitch;
      frameRotationReady = true;
    }

    frameYawPrevious = frameYaw;
    framePitchPrevious = framePitch;
    var settings = RyznSettings.get().rotations();
    var walking = RyznSettings.get().pathfinder().walking();
    var tuning = settings.tuning();
    int yawCap = walking.get(Control.YAW_CAP);
    int pitchCap = walking.get(Control.PITCH_CAP);
    if (yawCap > 0 || pitchCap > 0) {
      tuning =
          new AbcRotation.Tuning(
              tuning.randomSeed(),
              yawCap == 0 ? tuning.yawCap() : yawCap,
              pitchCap == 0 ? tuning.pitchCap() : pitchCap,
              tuning.dampingMillis(),
              tuning.deadzone(),
              tuning.limitOvershoot(),
              tuning.quantizeOutput());
    }
    var frame =
        abc.step(
            settings.isAbcEnabled(),
            settings.getAbcSeed(),
            look.nowNanos(),
            look.yaw(),
            look.pitch(),
            aimYaw,
            pitchTarget(preciseLook, verticalGazeOpen, exactAim),
            RotationSmoothing.mouseGcd(look.sensitivity()),
            false,
            tuning);
    applyTick(frame.yaw(), frame.pitch());
  }

  private float pitchTarget(boolean preciseLook, boolean verticalGazeOpen, boolean exactAim) {
    float pitchLow = verticalGazeOpen ? -28f : -8f;
    float pitchHigh = verticalGazeOpen ? 28f : 15f;
    float target =
        freeAim || exactAim
            ? Math.clamp(aimPitch, -90f, 90f)
            : preciseLook
                ? Math.clamp(aimPitch, -35f, 35f)
                : Math.clamp(aimPitch, pitchLow, pitchHigh);
    return recordedMovement ? Math.max(target, 0f) : target;
  }

  private void applyTick(float yaw, float pitch) {
    frameYaw = yaw;
    framePitch = pitch;

    // Movement uses the tick angle. The view getters interpolate independently between ticks.
    look.apply(frameYaw, framePitch);
  }
}
