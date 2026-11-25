package ryzn.macro.client.feature.modules.impl.combat;

/**
 * The optional box the bot is allowed to operate in, parsed from the {@code combat_bounds} setting
 * ({@code "minX,minY,minZ,maxX,maxY,maxZ"}).
 *
 * <p>There used to be two independent parsers for this one string, one in targeting and one in
 * roaming, and they disagreed at the edge: roaming used an inclusive six-face test while targeting
 * handed the numbers to a Minecraft {@code AABB}, whose {@code contains} excludes the max faces. A
 * mob standing exactly on the far edge of the box was therefore worth walking to but not worth
 * attacking.
 *
 * <p>One parser now, and the inclusive reading wins: if you typed two corners, both corners are
 * inside the box you meant. Corners may be given in any order.
 */
record CombatBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

  private static final int COMPONENTS = 6;

  /** Parse the setting string, or null when it is blank or malformed (which means "no limit"). */
  static CombatBounds parse(String csv) {
    if (csv == null || csv.isBlank()) {
      return null;
    }
    final double[] values = new double[COMPONENTS];
    int count = 0;
    int start = 0;
    try {
      for (int i = 0; i <= csv.length(); i++) {
        if (i != csv.length() && csv.charAt(i) != ',') {
          continue;
        }
        if (count == COMPONENTS) {
          return null;
        } // more than six numbers
        values[count++] = Double.parseDouble(csv.substring(start, i).trim());
        start = i + 1;
      }
    } catch (NumberFormatException ex) {
      return null;
    }
    if (count != COMPONENTS) {
      return null;
    }
    return new CombatBounds(
        Math.min(values[0], values[3]),
        Math.min(values[1], values[4]),
        Math.min(values[2], values[5]),
        Math.max(values[0], values[3]),
        Math.max(values[1], values[4]),
        Math.max(values[2], values[5]));
  }

  /** Inclusive on every face, so a position on the boundary you typed counts as inside it. */
  boolean contains(double x, double y, double z) {
    return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
  }

  /** Null-safe: no box configured means everywhere is allowed. */
  static boolean allows(CombatBounds bounds, double x, double y, double z) {
    return bounds == null || bounds.contains(x, y, z);
  }
}
