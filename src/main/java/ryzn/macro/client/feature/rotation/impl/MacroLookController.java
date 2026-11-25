package ryzn.macro.client.feature.rotation.impl;

import lombok.RequiredArgsConstructor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.rotation.api.LookController;
import ryzn.macro.client.util.text.MacroText;

/** Shared smoothed aiming state for macro-controlled player look. */
@RequiredArgsConstructor
public final class MacroLookController implements LookController {

  private enum Mode {
    NONE,
    POINT,
    DOWN
  }

  private static final float SETTLED_THRESHOLD = 2.0f;
  private final float[] desiredAim = new float[2];
  private final float downPitch;
  private Vec3 target;
  private Mode mode = Mode.NONE;
  private boolean settled;

  public void target(Vec3 target) {
    this.target = target;
    mode = Mode.POINT;
  }

  public Vec3 target() {
    return target;
  }

  public boolean hasAim() {
    return mode != Mode.NONE;
  }

  public boolean aimAtEntity(LocalPlayer player, Entity entity) {
    return aimAt(
        player,
        MacroAim.entityLookPoint(entity),
        entity == null ? "a point" : MacroText.stripFormatting(entity.getName().getString()));
  }

  public boolean aimAtBlock(LocalPlayer player, BlockPos position) {
    return aimAt(
        player,
        new Vec3(position.getX() + 0.5, position.getY() + 0.65, position.getZ() + 0.5),
        "the block at " + position.toShortString());
  }

  public boolean aimAtPoint(LocalPlayer player, Vec3 point) {
    return aimAt(player, point, "a point");
  }

  public boolean aimDown(LocalPlayer player) {
    down();
    final boolean done = settle(MacroAim.isPitchWithin(player, downPitch, SETTLED_THRESHOLD));
    trace(
        done ? "aim: settled looking down at pitch %.0f" : "aim: tilting down to pitch %.0f",
        downPitch);
    return done;
  }

  /**
   * {@code what} names the target for the debug line - the rest is the same aim for every caller.
   */
  private boolean aimAt(LocalPlayer player, Vec3 point, String what) {
    target(point);
    final boolean done =
        settle(MacroAim.isLookWithin(player, point, SETTLED_THRESHOLD, desiredAim));
    trace(
        done
            ? "aim: settled on %s (%.0f, %.0f, %.0f), yaw %.0f pitch %.0f"
            : "aim: turning to %s (%.0f, %.0f, %.0f), want yaw %.0f pitch %.0f",
        what,
        point.x,
        point.y,
        point.z,
        desiredAim[0],
        desiredAim[1]);
    return done;
  }

  /**
   * What the head is aiming at, printed only when the answer CHANGES - so a macro that re-aims at
   * the same target every tick costs one line, not twenty a second. Coordinates and angles are
   * whole numbers to avoid a debug line for every small target movement.
   */
  private void trace(String fmt, Object... args) {
    DebugLog.event(DebugLog.Category.MACRO, AIM_KEY, fmt, args);
  }

  /** Every aim line shares one key, so going back to a previous target still prints. */
  private static final String AIM_KEY = "macro-aim";

  public void down() {
    target = null;
    mode = Mode.DOWN;
  }

  public boolean settle(boolean aimedNow) {
    if (!aimedNow) {
      settled = false;
      return false;
    }
    if (!settled) {
      settled = true;
      return false;
    }
    return true;
  }

  public void resetSettle() {
    settled = false;
  }

  /**
   * Take the head back after something else was driving it (a path executor route, a menu, another
   * phase). Drops the eased angle AND the ease clock, so the next {@link #tick} restarts the curve
   * from wherever the head actually is now. Without this the controller resumes from the angle it
   * held before the handover and with a dt covering the whole gap, which saturates the blend - i.e.
   * the head snaps instead of easing.
   */
  public void reanchor() {
    resetSettle();
    RyznBootstrap.rotationManager().resetAim(this);
  }

  public void clear() {
    trace("aim: let go of the head");
    target = null;
    mode = Mode.NONE;
    resetSettle();
    RyznBootstrap.rotationManager().resetAim(this);
  }

  /**
   * Wall-clock ease + apply. Call from the game tick (guarantees the aim keeps turning while the
   * window is unfocused and rendering is throttled) AND from the render frame (re-samples the same
   * curve at frame rate). The ease is stepped by real elapsed time, so driving it from both loops
   * is self-consistent, not double speed.
   */
  public void tick(LocalPlayer player) {
    tick(player, 1.0f);
  }

  public void tick(LocalPlayer player, float speedMultiplier) {
    var rotations = RyznBootstrap.rotationManager();
    if (mode == Mode.DOWN) {
      rotations.aimStandard(this, player, player.getYRot(), downPitch, 1f, true);
    } else if (target != null) {
      MacroAim.desiredYawPitch(player.getEyePosition(), target, desiredAim);
      rotations.aimTarget(this, player, desiredAim[0], desiredAim[1]);
    }
  }

  public void render(LocalPlayer player) {
    tick(player);
  }
}
