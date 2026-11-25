package ryzn.macro.client.feature.macros.impl.foragingquest;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import ryzn.macro.client.feature.macros.impl.foragingquest.ForagingRoute.Target;
import ryzn.macro.client.feature.macros.impl.level7.SkillLevelDetector;
import ryzn.macro.client.feature.macros.impl.tree.TreeLogType;
import ryzn.macro.client.util.text.MacroText;

/** Quest follow-up: every wood collection to III, then Hub Oak until Foraging 12. */
final class ForagingCollectionGrind {

  static final int FORAGING_TARGET_LEVEL = 12;
  static final int TARGET_COLLECTION_LEVEL = 3;
  private static final Pattern ROMAN_TOKEN = Pattern.compile("(?i)\\b[ivxlc]+\\b");
  private static final int SKILL_POLL_INTERVAL_TICKS = 10;
  private static final Leg[] legs = {
    new Leg(TreeLogType.JUNGLE, ForagingRoute.MOLBERT.target()),
    new Leg(TreeLogType.ACACIA, ForagingRoute.MELODY.target()),
    new Leg(TreeLogType.DARK_OAK, ForagingRoute.RYAN.target()),
    new Leg(TreeLogType.SPRUCE, ForagingRoute.KELLY.target()),
    new Leg(TreeLogType.BIRCH, ForagingRoute.CHARLIE.target()),
    new Leg(TreeLogType.OAK, ForagingRoute.OAK_COLLECTION_START)
  };

  record Leg(TreeLogType type, Target travel) {}

  private final SkillLevelDetector skills = new SkillLevelDetector();
  private final Map<TreeLogType, Integer> levels = new EnumMap<>(TreeLogType.class);
  private int legIndex;
  private int lastChoppedCount = -1;
  private int stalledTicks;
  private int skillPollTicks;

  Leg leg() {
    return legs[legIndex];
  }

  static String[] legLabels() {
    String[] labels = new String[legs.length];
    for (int i = 0; i < labels.length; i++) {
      labels[i] =
          "Grind "
              + legs[i].type().displayName()
              + " collection to III"
              + (legs[i].type() == TreeLogType.OAK ? ", then Foraging 12" : "");
    }
    return labels;
  }

  static TreeLogType typeAt(int index) {
    return index >= 0 && index < legs.length ? legs[index].type() : null;
  }

  static int legIndexOf(TreeLogType type) {
    for (int i = 0; i < legs.length; i++) {
      if (legs[i].type() == type) {
        return i;
      }
    }
    return -1;
  }

  void resumeAt(int index) {
    if (typeAt(index) == null) {
      return;
    }
    legIndex = index;
    lastChoppedCount = -1;
    stalledTicks = 0;
  }

  boolean advance() {
    if (legIndex + 1 == legs.length) {
      return false;
    }
    resumeAt(legIndex + 1);
    return true;
  }

  int level(TreeLogType type) {
    return levels.getOrDefault(type, 0);
  }

  boolean reachedTarget(TreeLogType type) {
    return level(type) >= TARGET_COLLECTION_LEVEL;
  }

  void clearCollections() {
    levels.clear();
  }

  void observeChat(String clean) {
    int marker = clean.indexOf("collection level up");
    if (marker < 0) {
      return;
    }
    String tail = clean.substring(marker + "collection level up".length());
    TreeLogType type = tail.contains("dark oak") ? TreeLogType.DARK_OAK : null;
    if (type == null) {
      for (Leg leg : legs) {
        if (tail.contains(leg.type().statusName())) {
          type = leg.type();
          break;
        }
      }
    }
    if (type == null) {
      return;
    }
    var tokens = ROMAN_TOKEN.matcher(tail);
    int level = 0;
    while (tokens.find()) {
      level = MacroText.romanToInt(tokens.group());
    }
    if (level > 0) {
      levels.merge(type, level, Math::max);
    }
  }

  int foragingLevel(Minecraft mc) {
    if (skillPollTicks-- <= 0) {
      skillPollTicks = SKILL_POLL_INTERVAL_TICKS;
      skills.poll(mc);
    }
    return skills.level(SkillLevelDetector.FORAGING);
  }

  boolean isStalled(int choppedCount, int stallTicks) {
    if (choppedCount != lastChoppedCount) {
      lastChoppedCount = choppedCount;
      stalledTicks = 0;
      return false;
    }
    if (++stalledTicks < stallTicks) {
      return false;
    }
    stalledTicks = 0;
    return true;
  }

  void reset() {
    legIndex = 0;
    skills.reset();
    lastChoppedCount = -1;
    stalledTicks = 0;
    skillPollTicks = 0;
  }
}
