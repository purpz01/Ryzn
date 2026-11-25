package ryzn.macro.client.feature.macros.impl.level7;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import ryzn.macro.client.util.text.MacroText;

/**
 * Reads Hypixel SkyBlock skill levels out of the TAB player list (not the scoreboard sidebar). The
 * cleaning mirrors {@code SkyBlockHud}: every {@link PlayerInfo} display name is stripped of
 * section-sign format codes and control/format glyphs, the rows are walked in TAB order, the
 * "Skills:" column header is located, and the contiguous rows beneath it ("Farming 15: 98.4%") are
 * parsed into name + integer level.
 *
 * <p>Levels only ever rise, so the last successfully parsed value for each skill is retained across
 * polls; a momentarily empty or unavailable TAB (e.g. mid server transfer) does not erase known
 * levels. A skill that has never been seen reports {@code -1} so callers can wait for TAB data
 * rather than advancing.
 *
 * <p>The section sign is written as a Unicode escape so the source compiles identically regardless
 * of the platform default encoding (the Gradle build does not pin a UTF-8 source encoding).
 */
public final class SkillLevelDetector {

  public static final String FARMING = "farming";
  public static final String MINING = "mining";
  public static final String FORAGING = "foraging";
  public static final String HUNTING = "hunting";
  private static final Comparator<PlayerInfo> tabOrder =
      Comparator.comparingInt(PlayerInfo::getTabListOrder)
          .thenComparing(info -> info.getProfile().name(), String.CASE_INSENSITIVE_ORDER);

  // "Farming 15: 98.4%" / "Mining 26: 64.2%" / "Combat 60: MAX" -> group(1)=name, group(2)=level.
  private static final Pattern SKILL_LINE =
      Pattern.compile("(?i)\\b([A-Za-z]+)\\s+(\\d{1,3})\\s*:");
  private static final Pattern LEVEL_UP_LINE =
      Pattern.compile("(?i).*skill\\s+level\\s+up\\s+([A-Za-z]+)\\s+(.+)$");
  private static final Pattern LEVEL_TOKEN =
      Pattern.compile("(?i)\\b(?:\\d{1,3}|[IVXLCDM]{1,12})\\b");

  private static final Set<String> KNOWN_SKILLS =
      Set.of(
          "farming",
          "mining",
          "combat",
          "foraging",
          "fishing",
          "enchanting",
          "alchemy",
          "taming",
          "hunting",
          "carpentry",
          "runecrafting",
          "social",
          "dungeoneering");

  private final Map<String, Integer> levels = new HashMap<>();
  private boolean sawTabData;

  /**
   * Refresh skill levels from the current TAB list. Keeps the last known value for any skill not
   * seen.
   *
   * @param mc the client whose TAB list is read
   */
  public void poll(Minecraft mc) {
    sawTabData = false;
    if (mc == null) {
      return;
    }
    final ClientPacketListener conn = mc.getConnection();
    if (conn == null) {
      return;
    }

    final List<PlayerInfo> sortedPlayers = new ArrayList<>(conn.getOnlinePlayers());
    sortedPlayers.sort(tabOrder);

    boolean inSkillsSection = false;
    boolean skillsSectionEnded = false;
    // Header text can vary or be prefixed by hidden glyphs. Known-skill rows are unambiguous, so
    // scan
    // the whole TAB too; level records are monotonic, making duplicate matches harmless.
    for (PlayerInfo info : sortedPlayers) {
      final String line = displayText(info);
      if (line.isBlank()) {
        continue;
      }
      sawTabData = true;

      if (!skillsSectionEnded) {
        if (!inSkillsSection) {
          inSkillsSection = isSkillsHeader(line);
        } else if (!recordSkill(line)) {
          skillsSectionEnded = true;
        }
      }
      recordSkill(line);
    }
  }

  private static boolean isSkillsHeader(String line) {
    final String lower = line.toLowerCase(Locale.ROOT);
    return lower.equals("skills") || lower.equals("skills:") || lower.contains("skills:");
  }

  private boolean recordSkill(String line) {
    final Matcher m = SKILL_LINE.matcher(line);
    if (!m.find()) {
      return false;
    }
    final String name = m.group(1).toLowerCase(Locale.ROOT);
    if (!KNOWN_SKILLS.contains(name)) {
      return false;
    }
    final int level = Integer.parseInt(m.group(2));
    if (level < 0 || level > 80) {
      return false;
    }
    recordLevel(name, level);
    return true;
  }

  public boolean recordLevelUpMessage(String message) {
    final Matcher m = LEVEL_UP_LINE.matcher(clean(message));
    if (!m.matches()) {
      return false;
    }
    final String name = m.group(1).toLowerCase(Locale.ROOT);
    if (!KNOWN_SKILLS.contains(name)) {
      return false;
    }
    final int level = parseLastLevelToken(m.group(2));
    if (level < 0 || level > 80) {
      return false;
    }
    recordLevel(name, level);
    return true;
  }

  private void recordLevel(String name, int level) {
    levels.merge(name, level, Math::max);
  }

  private static int parseLastLevelToken(String text) {
    final Matcher m = LEVEL_TOKEN.matcher(text);
    int level = -1;
    while (m.find()) {
      final int parsed = parseLevelToken(m.group());
      if (parsed >= 0) {
        level = parsed;
      }
    }
    return level;
  }

  private static int parseLevelToken(String token) {
    if (token == null || token.isBlank()) {
      return -1;
    }
    if (Character.isDigit(token.charAt(0))) {
      return Integer.parseInt(token);
    }
    return romanToInt(token.toUpperCase(Locale.ROOT));
  }

  private static int romanToInt(String roman) {
    int total = 0;
    int prev = 0;
    for (int i = roman.length() - 1; i >= 0; i--) {
      final int value = romanDigit(roman.charAt(i));
      if (value <= 0) {
        return -1;
      }
      if (value < prev) {
        total -= value;
      } else {
        total += value;
        prev = value;
      }
    }
    return total;
  }

  private static int romanDigit(char c) {
    return switch (c) {
      case 'I' -> 1;
      case 'V' -> 5;
      case 'X' -> 10;
      case 'L' -> 50;
      case 'C' -> 100;
      case 'D' -> 500;
      case 'M' -> 1000;
      default -> -1;
    };
  }

  /**
   * Detected level for {@code skill} (case-insensitive), or {@code -1} if it has never been seen.
   *
   * @param skill the skill name, matched without case
   * @return the detected level, or -1 when it has never been seen
   */
  public int level(String skill) {
    final Integer value = levels.get(skill.toLowerCase(Locale.ROOT));
    return value != null ? value : -1;
  }

  /**
   * Whether the last {@link #poll} saw any TAB entries at all (connection present and list
   * populated).
   *
   * @return true when the last poll saw TAB entries
   */
  public boolean sawTabData() {
    return sawTabData;
  }

  public void reset() {
    levels.clear();
    sawTabData = false;
  }

  private static String displayText(PlayerInfo info) {
    final Component display = info.getTabListDisplayName();
    return clean(display != null ? display.getString() : info.getProfile().name());
  }

  private static String clean(String text) {
    return MacroText.cleanDecoratedText(text);
  }
}
