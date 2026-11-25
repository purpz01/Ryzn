package ryzn.macro.client.feature.macros.impl.tree;

import lombok.AccessLevel;
import lombok.Getter;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;

final class TreeNavigationController {

  private static final int SETTLE_TICKS = 2;

  @Getter(AccessLevel.PACKAGE)
  private boolean issued;

  private int settleTicks;

  /** Whether the route this controller issued ever actually DROVE the body. */
  private boolean drove;

  boolean start(int x, int y, int z) {
    issued = PathfindCommand.startToQuiet(x, y, z);
    drove = false;
    settleTicks = 0;
    return issued;
  }

  boolean isFinished() {
    if (!issued) {
      return true;
    }
    if (PathfindCommand.isNavigationActive()) {
      // isNavigationActive is true while the search is still IN FLIGHT, so it cannot tell a
      // resolved
      // route from a pending one. Only isRouteDriving proves a path came back. Same distinction the
      // Level 7 transfer routes had to make.
      if (PathfindCommand.isRouteDriving()) {
        drove = true;
      }
      settleTicks = 0;
      return false;
    }
    if (++settleTicks < SETTLE_TICKS) {
      return false;
    }
    issued = false;
    return true;
  }

  /**
   * True when the last issued route ended without ever driving - i.e. the search answered "no
   * path". Only meaningful once {@link #isFinished()} has returned true.
   */
  boolean answeredNoPath() {
    return !drove;
  }

  void stop() {
    PathfindCommand.stopNavQuiet();
    reset();
  }

  void reset() {
    issued = false;
    drove = false;
    settleTicks = 0;
  }
}
