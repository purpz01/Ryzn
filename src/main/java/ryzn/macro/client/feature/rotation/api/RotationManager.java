package ryzn.macro.client.feature.rotation.api;

import net.minecraft.client.player.LocalPlayer;

/** Shared owner of look arbitration while movement and macros run together. */
public interface RotationManager {
  /**
   * @description Reports whether another feature currently owns the head while a path drives the
   *     body.
   * @return true when path rotation must yield its look request
   */
  boolean externalOwnsLook();

  /**
   * @description Reports whether a path or feature owns player look for input locking.
   * @return true when manual mouse look should be suppressed
   */
  boolean anyMacroOwnsLook();

  /**
   * @description Marks whether the client is resolving an interaction ray that must use raw look.
   * @param active true for the duration of a crosshair pick
   */
  void setPicking(boolean active);

  /**
   * @description Reports whether an interaction ray is being resolved.
   * @return true while raw look must bypass camera easing
   */
  boolean isPicking();

  /**
   * @description Reports whether a path or recent feature turn drives the local camera.
   * @param player the current local player
   * @return true while camera easing may display a feature-owned turn
   */
  boolean isCameraDriven(LocalPlayer player);

  /**
   * @description Records a functional player turn before the camera display eases toward it.
   * @param player the player whose live angles were updated
   */
  void markCamera(LocalPlayer player);

  /**
   * @description Selects display easing for tick-driven or already frame-driven rotation.
   * @param player the player whose live angles were updated
   * @param frameDriven true when the owner already updates functional rotation each frame
   */
  void markCamera(LocalPlayer player, boolean frameDriven);

  /**
   * @description Checks the short grace period after the most recent functional turn.
   * @param player the player with the tick counter to compare
   * @return true while a recent turn remains eligible for easing
   */
  boolean isCameraFresh(LocalPlayer player);

  /**
   * @description Advances and returns the local display yaw without writing player rotation.
   * @param player the player whose functional yaw is the target
   * @return the smoothed display yaw
   */
  float cameraYaw(LocalPlayer player);

  /**
   * @description Advances and returns the local display pitch without writing player rotation.
   * @param player the player whose functional pitch is the target
   * @return the smoothed display pitch
   */
  float cameraPitch(LocalPlayer player);

  /**
   * @description Resets camera easing so the next driven frame starts at the live angle.
   */
  void idleCamera();

  /**
   * Applies a feature-owned smoothed turn using rotation subsystem state.
   *
   * @description Advances the owner's aim state and writes one sensitivity-aligned player turn.
   * @param owner the element that owns the requested turn
   * @param player the local player to turn
   * @param yaw the desired yaw
   * @param pitch the desired pitch
   * @param deadzone the angle tolerance in degrees
   * @param yawFactor the yaw easing factor per 60 Hz frame
   * @param pitchFactor the pitch easing factor per 60 Hz frame
   * @param yawRate the maximum yaw speed in degrees per second
   * @param pitchRate the maximum pitch speed in degrees per second
   */
  void smoothTurn(
      Object owner,
      LocalPlayer player,
      float yaw,
      float pitch,
      float deadzone,
      float yawFactor,
      float pitchFactor,
      double yawRate,
      double pitchRate);

  /**
   * @description Returns the smoothed aim controller this subsystem keeps for one owner.
   * @param owner the feature that aims through the controller
   * @param downPitch the pitch the controller uses when told to look down
   * @return the owner's controller, created on first use
   */
  LookController lookController(Object owner, float downPitch);

  /**
   * Releases a feature's turn state when it stops owning look.
   *
   * @description Makes the next turn start from the player's current angles.
   * @param owner the element releasing its aim state
   */
  void releaseTurn(Object owner);

  /**
   * Applies a requested player turn on the mouse-sensitivity lattice.
   *
   * @description Writes the snapped turn and records it as an expected rotation.
   * @param player the local player to turn
   * @param yaw requested yaw in degrees
   * @param pitch requested pitch in degrees
   */
  void applyLook(LocalPlayer player, float yaw, float pitch);

  /** Advances the owned rotation session toward the given camera target. */
  void aimTarget(Object owner, LocalPlayer player, float yaw, float pitch);

  /** Changes planner speed for this aim while keeping output limits on real time. */
  void aimTarget(Object owner, LocalPlayer player, float yaw, float pitch, double plannerSpeed);

  /**
   * Drives the shared macro aim profile for one owner and applies its player turn.
   *
   * @description Keeps standard smoothing state on this manager.
   * @param owner the feature controlling look
   * @param player the local player to turn
   * @param yaw requested yaw in degrees
   * @param pitch requested pitch in degrees
   */
  void aimStandard(Object owner, LocalPlayer player, float yaw, float pitch);

  /**
   * Drives the shared macro aim profile with the speed range of {@code activity}.
   *
   * @param owner the feature controlling look
   * @param player the local player to turn
   * @param yaw requested yaw in degrees
   * @param pitch requested pitch in degrees
   * @param activity which Rotations tab speed range this turn uses
   */
  void aimStandard(
      Object owner, LocalPlayer player, float yaw, float pitch, RotationActivity activity);

  /**
   * Drives the standard aim profile with a speed scale and optional held yaw.
   *
   * @description Applies the supplied speed and pitch-only settings to the turn.
   * @param owner the feature controlling look
   * @param player the local player to turn
   * @param yaw requested yaw in degrees
   * @param pitch requested pitch in degrees
   * @param speed multiplier for the standard aim rate
   * @param holdYaw whether to retain the captured yaw and change only pitch
   */
  void aimStandard(
      Object owner, LocalPlayer player, float yaw, float pitch, float speed, boolean holdYaw);

  /**
   * Drives a tuned aim profile owned by the rotation manager.
   *
   * @description Preserves Rev Slayer's spawn aim tuning.
   * @param owner the feature controlling look
   * @param player the local player to turn
   * @param yaw requested yaw in degrees
   * @param pitch requested pitch in degrees
   * @param deadzone the angle tolerance in degrees
   * @param yawEase yaw easing factor per fixed step
   * @param yawMaxStep maximum yaw degrees per fixed step
   * @param pitchEase pitch easing factor per fixed step
   * @param pitchMaxStep maximum pitch degrees per fixed step
   */
  void aimCustom(
      Object owner,
      LocalPlayer player,
      float yaw,
      float pitch,
      float deadzone,
      float yawEase,
      float yawMaxStep,
      float pitchEase,
      float pitchMaxStep);

  /**
   * Clears one owner's macro aim profile.
   *
   * @description Starts the next aim from the player's current angle.
   * @param owner the feature releasing look
   */
  void resetAim(Object owner);

  /**
   * A sampled arc turn owned by the rotation subsystem.
   *
   * @param yaw sampled yaw in degrees
   * @param pitch sampled pitch in degrees
   */
  record ArcFrame(float yaw, float pitch) {}

  /**
   * Advances one owner's curved turn toward the requested angle.
   *
   * @description Keeps arc state in the shared rotation subsystem.
   * @param owner the route or feature that owns the arc
   * @param nowNanos monotonic sampling time
   * @param currentYaw current player yaw
   * @param currentPitch current player pitch
   * @param targetYaw desired yaw
   * @param targetPitch desired pitch
   * @param maximumBendDegrees maximum curve bend
   * @param durationMs requested turn duration
   * @return sampled look angles
   */
  ArcFrame sampleArc(
      Object owner,
      long nowNanos,
      float currentYaw,
      float currentPitch,
      float targetYaw,
      float targetPitch,
      float maximumBendDegrees,
      int durationMs);

  /**
   * Drops one owner's curved turn state.
   *
   * @description Starts the owner's next arc from its current angle.
   * @param owner the route or feature releasing the arc
   */
  void resetArc(Object owner);

  /**
   * Reports whether one owner's curved turn is still underway.
   *
   * @description Checks the active arc without creating state.
   * @param owner the route or feature controlling the arc
   * @return true while its sampled arc has not finished
   */
  boolean isArcActive(Object owner);

  /**
   * One damped head turn and whether it started a new smoothing session.
   *
   * @param yaw smoothed yaw
   * @param pitch smoothed pitch
   * @param primedNow true when the owner started a new smoothing session
   */
  record DampedFrame(float yaw, float pitch, boolean primedNow) {}

  /**
   * Advances one owner's critically damped head turn.
   *
   * @description Keeps combat smoothing velocity in the shared rotation subsystem.
   * @param owner the feature controlling the turn
   * @param currentYaw current player yaw
   * @param currentPitch current player pitch
   * @param targetYaw desired yaw
   * @param targetPitch desired pitch
   * @param smoothTime damping time in seconds
   * @param maxSpeed maximum angular speed in degrees per second
   * @param deltaSeconds elapsed time since the prior step
   * @param reset whether the player turn drifted from the last feature turn
   * @return sampled angles and session state
   */
  DampedFrame dampedTurn(
      Object owner,
      float currentYaw,
      float currentPitch,
      float targetYaw,
      float targetPitch,
      float smoothTime,
      float maxSpeed,
      float deltaSeconds,
      boolean reset);

  /**
   * Drops one owner's damped head turn state.
   *
   * @description Clears angular velocity before the next turn session.
   * @param owner the feature releasing the turn
   */
  void resetDampedTurn(Object owner);

  /**
   * Difference between the current player turn and the most recently expected turn.
   *
   * @param yaw unsigned yaw difference in degrees
   * @param pitch unsigned pitch difference in degrees
   */
  record Deviation(float yaw, float pitch) {}

  /**
   * Records a client-requested turn for forced-rotation monitoring.
   *
   * @description Sets the baseline expected angle after intentional motion.
   * @param yaw expected yaw in degrees
   * @param pitch expected pitch in degrees
   */
  void expectRotation(float yaw, float pitch);

  /**
   * Suspends forced-rotation monitoring through the given client tick.
   *
   * @description Preserves the existing warp grace when world transitions begin.
   * @param untilTick final client tick covered by grace
   */
  void allowRotationGrace(long untilTick);

  /**
   * Clears the expected turn and warp grace when monitoring resets.
   *
   * @description Disarms monitoring after a disconnect or disabled detector.
   */
  void resetRotationExpectation();

  /**
   * Reads the current deviation, capturing the player turn while monitoring is inactive.
   *
   * @description Compares the player's turn with the most recently expected turn.
   * @param player the local player being monitored
   * @param tick current client tick
   * @param monitoringActive whether the failsafe is armed
   * @return angle differences, or null while capturing a baseline or under grace
   */
  Deviation rotationDeviation(LocalPlayer player, long tick, boolean monitoringActive);
}
