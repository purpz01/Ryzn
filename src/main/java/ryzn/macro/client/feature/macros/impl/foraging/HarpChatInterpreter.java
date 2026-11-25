package ryzn.macro.client.feature.macros.impl.foraging;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HarpChatInterpreter {
  private static final Pattern SCORE_PATTERN =
      Pattern.compile("scored\\s+(\\d{1,3})\\s*%", Pattern.CASE_INSENSITIVE);
  private static final Pattern GENERIC_PERCENT_PATTERN = Pattern.compile("(\\d{1,3})\\s*%");

  void accept(String text, HarpSongSession session) {
    final String lower = text.toLowerCase(Locale.ROOT);
    final String summary = truncate(text);
    if (lower.contains("already using the harp")) {
      session.markBusy(summary);
      return;
    }
    if (lower.contains("talk to melody")) {
      session.markSongMissing(summary);
      return;
    }
    if (lower.contains("song completed")) {
      session.markSongCompleted(summary);
    }

    final Matcher scored = SCORE_PATTERN.matcher(text);
    if (scored.find()) {
      session.markScore(clampPercent(parseIntSafe(scored.group(1))), summary);
      captureSongName(text, lower, session);
    } else if (lower.contains("you scored")) {
      final Matcher percent = GENERIC_PERCENT_PATTERN.matcher(text);
      if (percent.find()) {
        session.markScore(clampPercent(parseIntSafe(percent.group(1))), summary);
        captureSongName(text, lower, session);
      }
    }
  }

  private static void captureSongName(String text, String lower, HarpSongSession session) {
    final int index = lower.indexOf(" on ");
    if (index < 0) {
      return;
    }
    session.rememberScoredSongName(trimTrailingJunk(text.substring(index + 4).trim()));
  }

  private static String trimTrailingJunk(String name) {
    int end = name.length();
    while (end > 0) {
      final char character = name.charAt(end - 1);
      if (character != '!' && character != '.' && !Character.isWhitespace(character)) {
        break;
      }
      end--;
    }
    return end == name.length() ? name : name.substring(0, end);
  }

  private static String truncate(String text) {
    return text.length() <= 48 ? text : text.substring(0, 48);
  }

  private static int parseIntSafe(String text) {
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static int clampPercent(int value) {
    return Math.max(0, Math.min(100, value));
  }
}
