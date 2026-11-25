package ryzn.macro.client.input;

import java.util.concurrent.ThreadLocalRandom;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.debug.DebugLog;

public final class GuiActionTiming {

  /**
   * Floor for the click that follows a menu opening. A just-opened menu is not click-ready, and
   * this was the only click with no delay in front of it - it fired a tick after the screen
   * appeared and got dropped.
   */
  private static final int FIRST_MENU_CLICK_FLOOR_TICKS = 3;

  private GuiActionTiming() {}

  /**
   * Delay before the first click in a freshly opened menu; deliberately slower than a normal click.
   */
  public static int firstMenuClickDelay() {
    return randomDelayAtLeast(FIRST_MENU_CLICK_FLOOR_TICKS);
  }

  public static int randomDelay() {
    final RyznSettings cfg = RyznSettings.get();
    return randomDelay(
        cfg.pathfinder().getGuiActionDelayMinTicks(), cfg.pathfinder().getGuiActionDelayMaxTicks());
  }

  private static int randomDelay(int minTicks, int maxTicks) {
    final int min = Math.max(0, Math.min(100, minTicks));
    final int max = Math.max(min, Math.min(100, maxTicks));
    final int picked = ThreadLocalRandom.current().nextInt(min, max + 1);
    DebugLog.trace(
        DebugLog.Category.MACRO,
        "timing: waiting %dt before the next menu click (%d-%dt range)",
        picked,
        min,
        max);
    return picked;
  }

  public static int randomDelayAtLeast(int floor) {
    return Math.max(floor, randomDelay());
  }

  public static int maxDelayTicks() {
    return RyznSettings.get().pathfinder().getGuiActionDelayMaxTicks();
  }
}
