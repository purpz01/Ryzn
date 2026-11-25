package ryzn.macro.client.feature.hud;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ryzn.macro.client.feature.hud.SkyBlockHudData.Field;
import ryzn.macro.client.util.text.MacroText;

/** Interprets cleaned sidebar and TAB text without depending on Minecraft or the renderer. */
final class SkyBlockHudParser {

  private static final Pattern NUMERIC_DATE = Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b");
  private static final Pattern SEASON_DATE =
      Pattern.compile(
          "(?i)(?:early |late )?(?:spring|summer|autumn|fall|winter) \\d{1,2}(?:st|nd|rd|th)");
  private static final Pattern TIME_TOKEN =
      Pattern.compile("(?i)(\\d{1,2}):(\\d{2})\\s*([ap])\\.?m");
  private static final Pattern TIMER_TOKEN = Pattern.compile("\\d{1,2}:\\d{2}(?::\\d{2})?");
  private static final Pattern WEBSITE = Pattern.compile("(?i)(?:www\\.)?hypixel\\.(?:net|com)");
  private static final Pattern PET_BRACKET = Pattern.compile("(?i)\\[lvl\\s*(max|\\d+)]\\s*(.+)");

  SkyBlockHudData parse(List<Line> sidebar, List<String> tab) {
    SkyBlockHudData data = new SkyBlockHudData();
    parseSidebar(sidebar, data);
    parseTab(tab, data);
    return data;
  }

  SkyBlockHudData parseSidebar(List<Line> sidebar) {
    SkyBlockHudData data = new SkyBlockHudData();
    parseSidebar(sidebar, data);
    return data;
  }

  private void parseSidebar(List<Line> lines, SkyBlockHudData data) {
    boolean justSawTime = false;
    boolean objectiveOpen = false;
    String objectiveMark = "";
    for (Line line : lines) {
      String clean = line.clean();
      final String lower = clean.toLowerCase(Locale.ROOT);
      final boolean afterTime = justSawTime;
      justSawTime = false;

      if (!data.has(Field.AREA) && hasLocationSymbol(line.raw())) {
        data.set(Field.AREA, clean);
        objectiveOpen = false;
        continue;
      }
      if (!data.has(Field.DATE) && isDate(clean)) {
        data.set(Field.DATE, extractDate(clean));
        objectiveOpen = false;
        continue;
      }
      if (!data.has(Field.TIME) && TIME_TOKEN.matcher(clean).find()) {
        data.set(Field.TIME, normalizeTime(clean));
        justSawTime = true;
        objectiveOpen = false;
        continue;
      }
      if (!data.has(Field.AREA) && afterTime && isPlainArea(clean)) {
        data.set(Field.AREA, clean);
        continue;
      }
      if (startsWith(lower, "purse:", "piggy:")) {
        data.set(Field.PURSE, valueAfterColon(clean));
        objectiveOpen = false;
        continue;
      }
      if (startsWith(lower, "bank:", "personal bank:", "co-op bank:", "coop bank:")) {
        data.set(Field.BANK, valueAfterColon(clean));
        objectiveOpen = false;
        continue;
      }
      if (lower.startsWith("bits:")) {
        data.set(Field.BITS, valueAfterColon(clean));
        objectiveOpen = false;
        continue;
      }
      tagMode(lower, data);
      if (isEventLine(lower)) {
        feedEvent(data, stripEventLabel(clean));
        objectiveOpen = false;
        continue;
      }
      if (lower.contains("objective") || lower.contains("quest")) {
        clean = stripObjectiveLabel(clean);
        objectiveOpen = true;
      }
      if (objectiveOpen && !hardBoundary(lower)) {
        // Hypixel hangs the compass arrow off the label ("Objective ⬈") rather than the wording,
        // so a fragment with no words is a marker: hold it for the line that carries the text.
        if (hasWords(clean)) {
          data.set(Field.OBJECTIVE, withMark(clean, objectiveMark));
          objectiveMark = "";
          objectiveOpen = false;
        } else {
          objectiveMark = withMark(objectiveMark, clean);
        }
        continue;
      }
      objectiveOpen = false;
    }
  }

  private void parseTab(List<String> tab, SkyBlockHudData data) {
    for (String text : tab) {
      String lower = text.toLowerCase(Locale.ROOT);
      Matcher pet = PET_BRACKET.matcher(text);
      if (pet.find()) {
        feedPet(data, pet.group(1), pet.group(2));
      } else if (lower.startsWith("pet:")) {
        data.set(Field.PET_NAME, valueAfterColon(text));
      }

      if (startsWith(lower, "purse:", "piggy:")) {
        data.set(Field.PURSE, valueAfterColon(text));
      } else if (startsWith(lower, "bank:", "personal bank:", "co-op bank:", "coop bank:")) {
        data.set(Field.BANK, valueAfterColon(text));
      } else if (lower.startsWith("bits:")) {
        data.set(Field.BITS, valueAfterColon(text));
      } else if (lower.startsWith("profile:")) {
        data.set(Field.PROFILE, valueAfterColon(text));
      }
      tagMode(lower, data);
      if (isEventLine(lower)) {
        feedEvent(data, stripEventLabel(text));
      }
    }
  }

  private static void feedPet(SkyBlockHudData data, String levelToken, String name) {
    data.set(Field.PET_NAME, name.trim());
    if (levelToken != null) {
      data.set(Field.PET_STATUS, levelToken.equalsIgnoreCase("max") ? "MAX" : "Lvl " + levelToken);
    }
  }

  private static void feedEvent(SkyBlockHudData data, String text) {
    if (!hasText(text)) {
      return;
    }
    Matcher matcher = TIMER_TOKEN.matcher(text);
    if (matcher.find()) {
      String name = (text.substring(0, matcher.start()) + text.substring(matcher.end())).trim();
      data.set(Field.EVENT_NAME, hasText(name) ? name : text);
      data.set(Field.EVENT_TIMER, matcher.group());
    } else {
      data.set(Field.EVENT_NAME, text);
    }
  }

  private static void tagMode(String lower, SkyBlockHudData data) {
    if (lower.contains("ironman")) {
      data.set(Field.MODE, "Ironman");
    } else if (lower.contains("stranded")) {
      data.set(Field.MODE, "Stranded");
    } else if (lower.contains("bingo")) {
      data.set(Field.MODE, "Bingo");
    }
  }

  private static boolean isDate(String text) {
    return NUMERIC_DATE.matcher(text).find() || SEASON_DATE.matcher(text).find();
  }

  private static String extractDate(String text) {
    Matcher matcher = NUMERIC_DATE.matcher(text);
    if (matcher.find()) {
      return matcher.group();
    }
    matcher = SEASON_DATE.matcher(text);
    if (matcher.find()) {
      return capitalizeWords(matcher.group().trim());
    }
    return text;
  }

  private static String normalizeTime(String text) {
    Matcher matcher = TIME_TOKEN.matcher(text);
    if (matcher.find()) {
      return matcher.group(1)
          + ":"
          + matcher.group(2)
          + " "
          + matcher.group(3).toUpperCase(Locale.ROOT)
          + "M";
    }
    return text;
  }

  private static String stripObjectiveLabel(String text) {
    int colon = text.indexOf(':');
    if (colon >= 0) {
      return collapse(text.substring(colon + 1)).trim();
    }
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.equals("objective") || lower.equals("quest")) {
      return "";
    }
    if (lower.startsWith("objective")) {
      return text.substring("objective".length()).trim();
    }
    return text;
  }

  private static String stripEventLabel(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.startsWith("event:")) {
      return collapse(text.substring("event:".length())).trim();
    }
    return text;
  }

  private static boolean isEventLine(String lower) {
    return lower.contains("event")
        || lower.contains("jacob")
        || lower.contains("spooky")
        || lower.contains("winter")
        || lower.contains("new year")
        || lower.contains("dark auction")
        || lower.contains("mining fiesta")
        || lower.contains("carnival")
        || lower.contains("season of jerry");
  }

  private static boolean hardBoundary(String lower) {
    return startsWith(lower, "purse:", "piggy:", "bank:", "bits:", "pet:", "profile:")
        || WEBSITE.matcher(lower).find()
        || isDate(lower)
        || TIME_TOKEN.matcher(lower).find();
  }

  private static boolean isPlainArea(String text) {
    if (text.isBlank() || text.contains(":") || text.length() > 24) {
      return false;
    }
    String lower = text.toLowerCase(Locale.ROOT);
    if (startsWith(lower, "purse", "bank", "bits", "gems")
        || isEventLine(lower)
        || lower.contains("objective")
        || lower.contains("quest")) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      if (Character.isLetter(text.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static String valueAfterColon(String text) {
    int colon = text.indexOf(':');
    return collapse(colon >= 0 ? text.substring(colon + 1) : text).trim();
  }

  private static boolean startsWith(String text, String... prefixes) {
    for (String prefix : prefixes) {
      if (text.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasLocationSymbol(String raw) {
    return MacroText.containsLocationSymbol(raw);
  }

  private static boolean hasText(String text) {
    return text != null && !text.isBlank();
  }

  /**
   * True when {@code text} carries wording, not just punctuation or a symbol such as a compass
   * arrow.
   */
  private static boolean hasWords(String text) {
    if (text == null) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      if (Character.isLetterOrDigit(text.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static String withMark(String text, String mark) {
    if (!hasText(mark)) {
      return text == null ? "" : text;
    }
    return hasText(text) ? text + " " + mark : mark;
  }

  private static String capitalizeWords(String text) {
    StringBuilder result = new StringBuilder(text.length());
    int start = 0;
    while (start < text.length()) {
      while (start < text.length() && text.charAt(start) == ' ') {
        start++;
      }
      if (start >= text.length()) {
        break;
      }
      int end = start + 1;
      while (end < text.length() && text.charAt(end) != ' ') {
        end++;
      }
      if (!result.isEmpty()) {
        result.append(' ');
      }
      result.append(Character.toUpperCase(text.charAt(start)));
      if (end > start + 1) {
        result.append(text, start + 1, end);
      }
      start = end + 1;
    }
    return result.toString();
  }

  private static String collapse(String text) {
    return MacroText.collapseWhitespace(text);
  }

  record Line(String raw, String clean) {}
}
