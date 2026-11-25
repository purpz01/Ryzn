package ryzn.macro.client.feature.pathfinder.path;

import java.util.function.BiPredicate;
import net.minecraft.world.phys.Vec3;

/**
 * Where a plain fall is actually going to put the body, and which single key steers it back onto
 * the plan.
 *
 * <p>The planner models a drop as "step one cell sideways, then straight down" - it gives the fall
 * no horizontal momentum at all. Minecraft disagrees: a body that walks off a lip keeps its takeoff
 * speed and only sheds it to air drag, so it sails several blocks past the planned landing. That
 * mismatch is what the "N off the path" replans were, and {@link PathExecutor#fallingFreely()} only
 * ever treated the symptom - releasing forward stops the fall getting WORSE, it does not aim it.
 *
 * <p>This aims it. Air physics with no input is fully deterministic, so the arc can simply be run
 * forward to the planned landing height and the resulting XZ compared against where the route
 * wanted the body. The simulation deliberately models NO input, including our own: it answers
 * "where does this end up if I do nothing more from here", which is exactly the reference a greedy
 * corrector needs. Re-running it every tick closes the loop - once enough sideways velocity has
 * been added the prediction slides onto the target and {@link #steer} stops asking for a key by
 * itself, with no PID and no overshoot bookkeeping.
 *
 * <p>Authority here is small: a movement key adds {@value #AIR_ACCEL} per tick against a horizontal
 * speed that is already ~0.28 off a sprint, so this trims a landing, it does not rescue a badly
 * aimed one. That is why the executor also drops sprint before a planned drop - momentum not taken
 * off the lip is momentum this never has to fight.
 *
 * <p>Constants are vanilla's, and unlike {@code BobberPredictor} (whose hook is server-driven, so
 * its numbers had to be fitted from logged casts) that is the right authority: a falling player is
 * simulated by the CLIENT's own {@code travel()}, so the server's movement quirks never enter into
 * it.
 */
final class FallPredictor {

  /**
   * Vanilla air physics, per tick: {@code vy = (vy - GRAVITY) * VERTICAL_DRAG}, {@code vxz *=
   * DRAG}.
   */
  static final double GRAVITY = 0.08;

  static final double VERTICAL_DRAG = 0.98;
  static final double HORIZONTAL_DRAG = 0.91;

  /**
   * Horizontal acceleration one movement key adds while airborne (vanilla {@code getFlyingSpeed}).
   */
  static final double AIR_ACCEL = 0.02;

  /** Runaway guard. A 200-tick fall is 10s and far past anything survivable. */
  private static final int MAX_SIM_TICKS = 200;

  /** How much bigger the other axis's error must be before the correction switches axes. */
  private static final double AXIS_STICK = 1.5;

  private FallPredictor() {}

  /**
   * Where the body touches down, and how many ticks away that is.
   *
   * @param reached false when the arc never came down to the landing height inside {@link
   *     #MAX_SIM_TICKS} (rising, or falling into a void) - the XZ is then simply where the sim ran
   *     out and must not be steered on.
   */
  record Landing(double x, double z, int ticks, boolean reached) {}

  /**
   * The one movement key a correction wants held. Airborne only ever gets one - see {@link
   * MovementGate}.
   */
  enum Steer {
    NONE,
    FORWARD,
    BACK,
    LEFT,
    RIGHT
  }

  /**
   * Run the fall forward from here to {@code landingFootY} with no input.
   *
   * <p>Tick order is vanilla's: the body MOVES on its current velocity first, then gravity and drag
   * are applied for the next tick. Getting that backwards costs a tick of travel, which at sprint
   * speed is a third of a block of predicted landing error.
   *
   * <p>The crossing tick is interpolated rather than rounded, since a fast fall covers over a block
   * per tick near the end and rounding to the tick boundary would put the same error back in by
   * hand.
   */
  static Landing simulate(Vec3 position, Vec3 velocity, double landingFootY) {
    return simulate(position, velocity, landingFootY, null);
  }

  static Landing simulate(
      Vec3 position, Vec3 velocity, double landingFootY, BiPredicate<Vec3, Vec3> clearMovement) {
    double x = position.x;
    double y = position.y;
    double z = position.z;
    double vx = velocity.x;
    double vy = velocity.y;
    double vz = velocity.z;

    if (y <= landingFootY) {
      return new Landing(x, z, 0, true);
    }

    for (int tick = 1; tick <= MAX_SIM_TICKS; tick++) {
      double nextY = y + vy;
      if (nextY <= landingFootY) {
        double fraction = vy == 0.0 ? 0.0 : (y - landingFootY) / -vy;
        fraction = Math.clamp(fraction, 0.0, 1.0);
        double landingX = x + vx * fraction;
        double landingZ = z + vz * fraction;
        boolean clear =
            clearMovement == null
                || clearMovement.test(
                    new Vec3(x, y, z), new Vec3(landingX, landingFootY, landingZ));
        return new Landing(landingX, landingZ, tick, clear);
      }
      if (clearMovement != null
          && !clearMovement.test(new Vec3(x, y, z), new Vec3(x + vx, nextY, z + vz))) {
        return new Landing(x, z, tick, false);
      }
      x += vx;
      y = nextY;
      z += vz;
      vy = (vy - GRAVITY) * VERTICAL_DRAG;
      vx *= HORIZONTAL_DRAG;
      vz *= HORIZONTAL_DRAG;
    }
    return new Landing(x, z, MAX_SIM_TICKS, false);
  }

  /**
   * The single key that pulls the predicted landing towards the planned one.
   *
   * <p>The error is resolved into the body's own axes, because that is the frame the keys act in:
   * forward is {@code (-sin yaw, cos yaw)} and right is {@code (-cos yaw, -sin yaw)} (vanilla
   * {@code Entity#getInputVector}). Only the DOMINANT axis is driven - two keys at once is a
   * diagonal impulse that curves the arc, which is the nudge the airborne single-key rule exists to
   * stop.
   *
   * <p>{@code deadzone} is what keeps this from chattering: inside it the landing is as good as the
   * planner's own tolerance and no key is worth the momentum it would leave in the body on
   * touchdown.
   */
  static Steer steer(
      double errorX, double errorZ, float yawDegrees, double deadzone, Steer previous) {
    if (Math.hypot(errorX, errorZ) < deadzone) {
      return Steer.NONE;
    }

    double yaw = Math.toRadians(yawDegrees);
    double sin = Math.sin(yaw);
    double cos = Math.cos(yaw);
    double forward = errorX * -sin + errorZ * cos;
    double right = errorX * -cos + errorZ * -sin;

    // Stick to the axis already being driven unless the other one is clearly larger. Only one key
    // is
    // held at a time, so a bare dominance test flip-flops whenever the two errors are close -
    // logged
    // as BACK, LEFT, BACK, LEFT down a whole fall, which spends every tick starting an impulse and
    // none
    // building one. Air control is weak enough that momentum has to be allowed to accumulate on one
    // axis to be worth anything at all.
    boolean preferRight = Math.abs(right) >= Math.abs(forward);
    if (previous == Steer.LEFT || previous == Steer.RIGHT) {
      preferRight = Math.abs(right) * AXIS_STICK >= Math.abs(forward);
    } else if (previous == Steer.FORWARD || previous == Steer.BACK) {
      preferRight = Math.abs(right) >= Math.abs(forward) * AXIS_STICK;
    }

    if (preferRight) {
      return right > 0.0 ? Steer.RIGHT : Steer.LEFT;
    }
    return forward > 0.0 ? Steer.FORWARD : Steer.BACK;
  }
}
