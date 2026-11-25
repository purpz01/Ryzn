package ryzn.macro.client.feature.macros.impl.tree;

import ryzn.macro.client.config.RyznSettings;

final class TreeChopUi {

  record Status(
      boolean running,
      String state,
      int routeHandled,
      int chopped,
      String choppedUnit,
      String activity) {}

  private static final int AREA_MIN_X = -197;
  private static final int AREA_MAX_X = -141;
  private static final int AREA_MIN_Z = -52;
  private static final int AREA_MAX_Z = 2;
  private static final int NON_OAK_SCAN_RADIUS = 64;

  String status(Status status) {
    RyznSettings settings = RyznSettings.get();
    final TreeLogType type = settings.treeChop().chopLogType();
    StringBuilder text = new StringBuilder("§bTree Chop§7: ");
    text.append(status.running() ? "§arunning §7(" + status.state() + ")" : "§8idle");
    text.append(" §7| ")
        .append(settings.treeChop().chopTreecapMode() ? "Treecap mode" : "Multi-log mode");
    text.append(" §7| live route ").append(status.routeHandled()).append(" handled");
    text.append(" §7| area ")
        .append(AREA_MIN_X)
        .append(',')
        .append(AREA_MIN_Z)
        .append(" .. ")
        .append(AREA_MAX_X)
        .append(',')
        .append(AREA_MAX_Z);
    text.append(" §7| density ").append(settings.treeChop().chopMinDensity());
    text.append(" §7| breakFov ").append(settings.treeChop().chopBreakFovDegrees()).append('°');
    text.append(" §7| chopped ")
        .append(status.chopped())
        .append(' ')
        .append(status.choppedUnit())
        .append(status.chopped() == 1 ? "" : "s");
    return text
        + " | type "
        + type.displayName()
        + (type.isOak() ? "" : " | scan radius " + NON_OAK_SCAN_RADIUS)
        + " | "
        + status.activity();
  }
}
