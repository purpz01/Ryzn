package ryzn.macro.client.feature.reconnect;

import ryzn.macro.client.util.text.MacroText;

/**
 * The chat Hypixel sends when it moves you off SkyBlock without disconnecting you. Strings taken
 * from SkyHanni's kick pattern and aether's recovery triggers.
 *
 * <p>Split out of {@link MacroReconnect} for two reasons: it is the only part of the recovery with
 * no client state, so it can be tested, and a typo in one of these needles fails silently and costs
 * a whole unattended session - exactly the failure that made this class necessary.
 *
 * <p>A trigger preceded by a colon is ignored, because that is a player quoting it in chat rather
 * than the server saying it. Without that, anyone can stop a macro by typing "You were spawned in
 * Limbo.".
 */
public final class EvictionLines {

  /** Moved off SkyBlock, but free to walk back immediately. */
  private static final String[] evicted = {
    "you were spawned in limbo", "routed to limbo", "a disconnect occurred in your connection",
  };

  /**
   * The server announcing its own death, minutes BEFORE it evicts anyone. Nothing has happened yet,
   * so a macro that reads this can leave on its own terms instead of being evacuated mid-swing.
   *
   * <p>The real line is "[Important] This server will restart soon: &lt;reason&gt;" - the reason
   * varies ("Game Update", "Scheduled Reboot", ...), so only the stable half is matched.
   */
  private static final String[] restarting = {
    "this server will restart soon", "evacuating to hub", "server reboot",
  };

  /** Moved off SkyBlock AND put on a cooldown before SkyBlock will take you back. */
  private static final String[] kicked = {
    "you were kicked while joining that server",
    "a kick occurred in your connection",
    "an exception occurred in your connection",
    "there was a problem joining skyblock",
  };

  /**
   * Death lines for the LOCAL player. Everyone else's read "{@code <name> fell into the void.}", so
   * these never match another player.
   */
  private static final String[] ownDeath = {
    "you died", "you fell into the void", "you were killed by",
  };

  private EvictionLines() {}

  public static boolean isOwnDeath(String rawChat) {
    return matches(rawChat, ownDeath);
  }

  public static boolean isEviction(String rawChat) {
    return matches(rawChat, evicted);
  }

  public static boolean isKick(String rawChat) {
    return matches(rawChat, kicked);
  }

  /** A restart / game-update warning: still connected, still playing, but on borrowed time. */
  public static boolean isRestartWarning(String rawChat) {
    return matches(rawChat, restarting);
  }

  private static boolean matches(String rawChat, String[] needles) {
    final String line = MacroText.normalizeSearchText(rawChat);
    for (String needle : needles) {
      final int at = line.indexOf(needle);
      if (at < 0) {
        continue;
      }
      // Every way a player can put text on screen carries a speaker prefix first: "Name: ",
      // "<Name> ", "Party > Name: ". None of the server's own notices contain either character.
      final String prefix = line.substring(0, at);
      if (prefix.indexOf(':') < 0 && prefix.indexOf('>') < 0) {
        return true;
      }
    }
    return false;
  }
}
