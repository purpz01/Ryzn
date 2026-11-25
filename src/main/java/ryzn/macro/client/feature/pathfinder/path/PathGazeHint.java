package ryzn.macro.client.feature.pathfinder.path;

import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.util.time.Clock;

/**
 * A point a macro wants the walking head to drift toward ahead of time, such as the mob it is about
 * to engage. The executor eases onto it with its normal gaze aim, so when the macro takes the head
 * the remaining turn is small instead of a snap. The macro re-sets it every tick; an unrefreshed
 * hint expires, so a macro that stops without clearing it cannot leave the head staring.
 */
public final class PathGazeHint {

  private static final long EXPIRY_MS = 250L;

  private static Vec3 focus;
  private static long setMs;

  private PathGazeHint() {}

  public static void set(Vec3 point) {
    focus = point;
    setMs = Clock.now();
  }

  public static void clear() {
    focus = null;
  }

  static Vec3 focus() {
    return focus != null && Clock.now() - setMs <= EXPIRY_MS ? focus : null;
  }
}
