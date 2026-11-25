package ryzn.macro.client.feature.rotation.impl;

import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import ryzn.macro.client.feature.pathfinder.path.PathExecutor;

/**
 * Display-only low-pass for macro / pathfinder head rotation. The FUNCTIONAL yaw+pitch are written
 * to the player once per game tick (GCD-quantized, 20 Hz - exactly what the server sees, unchanged
 * and human). This eases ONLY the camera view-getters ({@link
 * ryzn.macro.client.mixin.entity.ClientPlayerMixin}) between frames, so stepped + quantized 20 Hz
 * rotation reads as glass at high fps. It never writes the player and never touches a packet, so it
 * is anti-detection-safe by construction: the smoothed angle is local-camera-only.
 *
 * <p>The old scheme lerped linearly between the last two ticks - C0 but not C1, so the angular
 * VELOCITY jumped at every 50 ms tick boundary (and the pathfinder feeds it discrete GCD steps on
 * top). Linear interpolation can't hide either; a one-pole low-pass can. One shared instance serves
 * whichever macro owns the look (only one does at a time). Each frame the mixin passes the live
 * functional angle; we ease the displayed angle toward it with a per-axis time constant, stepped by
 * REAL frame time (wall clock) so it is identical at 30 or 300 fps. Genuine jumps (a teleport /
 * target switch bigger than {@link #SNAP_DEGREES}) snap instead of swooping.
 */
public final class MacroCameraSmoother {

  // Ease time constants (seconds). Bigger = smoother but the camera trails the aim more on fast
  // turns.
  // ponytail: tuned by feel. Small corrections (where 20 Hz + GCD steps look jittery) get glassed;
  // the lag only
  // shows on rare fast whips, where it reads as natural head inertia. Drop these if the trail
  // bothers you, raise
  // them if any step is still visible.
  private static final float YAW_TAU = 0.055f;
  private static final float PITCH_TAU = 0.045f;
  private static final float FRAME_DRIVEN_TAU = 0.012f;
  private static final float FRAME_DRIVEN_YAW_RATE = 950f;
  private static final float FRAME_DRIVEN_PITCH_RATE = 350f;
  private static final float SNAP_DEGREES = 120f;
  private static final float MAX_FRAME_DT =
      0.10f; // clamp long gaps (window unfocused / paused) so we don't lurch
  // Frames stay "fresh" this many ticks after the last drive. It bridges a driver->driver handoff
  // (pathfinder
  // arriving at a cluster -> the macro locking its first aim): without it the camera drops to raw
  // for a tick
  // and snaps off the accumulated smoothing lag. Kept short so normal play un-captures ~150 ms
  // after a macro.
  private static final int FRESH_GRACE_TICKS = 3;

  private int stampTick;
  private boolean everMarked;
  private boolean primed;

  /**
   * Set for the duration of MC's crosshair pick ({@code Minecraft.pick}). While it is true the view
   * getters must return the RAW (server-true) angle, not the smoothed camera angle - the
   * interaction / attack raycast runs off {@code getViewVector}, i.e. the same getters we ease, so
   * without this a running macro / combat aim-assist / the fresh-grace window would aim the ray at
   * the lagged camera angle and a manual right-click on a player or block would silently miss.
   * Camera-only smoothing; the pick stays honest.
   */
  @Getter @Setter private boolean picking;

  private float displayYaw;
  private float displayPitch;
  private long lastNanos;
  private boolean frameDriven;

  /**
   * @description Creates the camera display state owned by the rotation manager.
   */
  public MacroCameraSmoother() {}

  public boolean isDriven(LocalPlayer player) {
    if (PathExecutor.instance.isInterpolatingRotation()) {
      mark(player);
      return true;
    }
    return stampFresh(player);
  }

  /**
   * Stamped from {@link RotationManagerImpl#applyLook} before a macro writes its next functional
   * angle.
   *
   * @param player the local player whose tick count is stamped
   */
  public void mark(LocalPlayer player) {
    mark(player, false);
  }

  /** Keeps display continuity while changing the owner's update cadence. */
  public void mark(LocalPlayer player, boolean frameDriven) {
    this.frameDriven = frameDriven;
    if (!primed) {
      displayYaw = player.getYRot();
      displayPitch = player.getXRot();
      lastNanos = System.nanoTime();
      primed = true;
    }
    stampTick = player.tickCount;
    everMarked = true;
  }

  /**
   * True on the frames of the tick a macro last drove the look (1-tick grace avoids a single-skip
   * flicker). Returns false until a macro has ever marked, and after a world reload resets
   * tickCount below the stamp, so during normal play the view-getter mixin yields and vanilla
   * mouselook / the interaction raycast are untouched.
   *
   * @param player the local player whose tick count is compared
   * @return true on the frames of the tick a macro last drove the look
   */
  public boolean stampFresh(LocalPlayer player) {
    if (!everMarked) {
      return false;
    }
    final int age = player.tickCount - stampTick;
    return age >= 0 && age <= FRESH_GRACE_TICKS;
  }

  public float yaw(LocalPlayer player) {
    advance(player);
    return displayYaw;
  }

  public float pitch(LocalPlayer player) {
    advance(player);
    return displayPitch;
  }

  /**
   * Re-arm: the next frame we drive the camera snaps to the live angle instead of swooping from a
   * stale one.
   */
  public void idle() {
    primed = false;
    frameDriven = false;
  }

  private void advance(LocalPlayer player) {
    final float targetYaw = player.getYRot();
    final float targetPitch = player.getXRot();
    final long now = System.nanoTime();
    if (!primed) {
      displayYaw = targetYaw;
      displayPitch = targetPitch;
      lastNanos = now;
      primed = true;
      return;
    }
    float dt = (now - lastNanos) / 1_000_000_000f;
    if (dt <= 0f) {
      return;
    } // a 2nd getter call in the same frame - already integrated above
    lastNanos = now;
    if (dt > MAX_FRAME_DT) {
      dt = MAX_FRAME_DT;
    }

    final float yawError = Mth.wrapDegrees(targetYaw - displayYaw);
    if (shouldSnap(yawError)) {
      displayYaw = targetYaw;
    } else {
      float tau = frameDriven ? FRAME_DRIVEN_TAU : YAW_TAU;
      float step = yawError * (1f - (float) Math.exp(-dt / tau));
      displayYaw +=
          frameDriven
              ? Math.clamp(step, -FRAME_DRIVEN_YAW_RATE * dt, FRAME_DRIVEN_YAW_RATE * dt)
              : step;
    }

    final float pitchError = targetPitch - displayPitch;
    if (shouldSnap(pitchError)) {
      displayPitch = targetPitch;
    } else {
      float tau = frameDriven ? FRAME_DRIVEN_TAU : PITCH_TAU;
      float step = pitchError * (1f - (float) Math.exp(-dt / tau));
      displayPitch +=
          frameDriven
              ? Math.clamp(step, -FRAME_DRIVEN_PITCH_RATE * dt, FRAME_DRIVEN_PITCH_RATE * dt)
              : step;
    }
  }

  static boolean shouldSnap(float error) {
    return Math.abs(error) >= SNAP_DEGREES;
  }
}
