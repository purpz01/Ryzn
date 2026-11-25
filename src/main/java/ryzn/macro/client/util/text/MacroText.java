package ryzn.macro.client.util.text;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared formatting and search-text helpers for Skyblock features. */
public final class MacroText {

  private static final Pattern STACK_32 =
      Pattern.compile("(?<![0-9])(?:x\\s*32|32\\s*x)(?![0-9])", Pattern.CASE_INSENSITIVE);
  private static final Pattern STACK_64 =
      Pattern.compile("(?<![0-9])(?:x\\s*64|64\\s*x)(?![0-9])", Pattern.CASE_INSENSITIVE);
  private static final char SECTION = '§';
  private static final char LOCATION_SYMBOL = '⏣';
  private static final char NBSP = '\u00A0';
  private static final char REPLACEMENT = '\uFFFD'; // U+FFFD replacement character
  // UTF-8 bytes of § and ⏣ that show up when the text was decoded as Latin-1.
  private static final char MOJIBAKE_C2 = '\u00C2'; // lead byte 0xC2
  private static final char MOJIBAKE_E2 = '\u00E2'; // lead byte 0xE2
  private static final char MOJIBAKE_A3 = '\u00A3'; // trail byte 0xA3

  private MacroText() {}

  /** Strip vanilla section-sign colour/format codes (e.g. {@code §a}) from {@code text}. */
  public static String stripFormatting(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder out = null;
    int i = 0;
    while (i < text.length()) {
      final int codeLength = formatCodeLength(text, i);
      if (codeLength > 0) {
        if (out == null) {
          out = new StringBuilder(text.length());
          out.append(text, 0, i);
        }
        i += codeLength;
        continue;
      }

      if (out != null) {
        out.append(text.charAt(i));
      }
      i++;
    }
    return out == null ? text : out.toString();
  }

  /**
   * Lower-case, single-spaced, trimmed, format-stripped form used for case-insensitive name/lore
   * matching.
   */
  public static String normalizeSearchText(String text) {
    if (text == null) {
      return "";
    }
    return collapseWhitespace(stripFormatting(text).toLowerCase(Locale.ROOT));
  }

  public static String cleanDecoratedText(String text) {
    if (text == null) {
      return "";
    }
    String fixed = fixCommonMojibake(text);
    fixed = stripAnyFormatPair(fixed);
    fixed = stripGlyphs(fixed);
    return collapseWhitespace(fixed);
  }

  public static String collapseWhitespace(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }

    int start = 0;
    int end = text.length();
    while (start < end) {
      final int cp = text.codePointAt(start);
      if (!isCollapsedWhitespace(cp)) {
        break;
      }
      start += Character.charCount(cp);
    }
    while (end > start) {
      final int cp = text.codePointBefore(end);
      if (!isCollapsedWhitespace(cp)) {
        break;
      }
      end -= Character.charCount(cp);
    }
    if (start >= end) {
      return "";
    }

    StringBuilder out = null;
    boolean previousWhitespace = false;
    int i = start;
    while (i < end) {
      final int cp = text.codePointAt(i);
      final int next = i + Character.charCount(cp);
      if (isCollapsedWhitespace(cp)) {
        if (out == null) {
          out = new StringBuilder(end - start);
          out.append(text, start, i);
        }
        if (!previousWhitespace) {
          out.append(' ');
        }
        previousWhitespace = true;
      } else {
        if (out != null) {
          out.append(text, i, next);
        }
        previousWhitespace = false;
      }
      i = next;
    }

    if (out == null && start == 0 && end == text.length()) {
      return text;
    }
    return out == null ? text.substring(start, end) : out.toString();
  }

  /**
   * Roman numeral to int ("IV" -> 4), case-insensitive. Hypixel writes every tier - collection
   * levels, enchant levels, skill level-up lines - this way. Unknown characters count as zero.
   */
  public static int romanToInt(String roman) {
    if (roman == null) {
      return 0;
    }
    final String upper = roman.toUpperCase(Locale.ROOT);
    int total = 0;
    int previous = 0;
    for (int i = upper.length() - 1; i >= 0; i--) {
      final int value =
          switch (upper.charAt(i)) {
            case 'I' -> 1;
            case 'V' -> 5;
            case 'X' -> 10;
            case 'L' -> 50;
            case 'C' -> 100;
            default -> 0;
          };
      total += value < previous ? -value : value;
      previous = Math.max(previous, value);
    }
    return total;
  }

  public static boolean containsLocationSymbol(String text) {
    if (text == null) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == LOCATION_SYMBOL || isMojibakeLocationSymbol(text, i)) {
        return true;
      }
    }
    return false;
  }

  public static boolean containsStackAmount(String text, int amount) {
    if (text == null) {
      return false;
    }
    return switch (amount) {
      case 32 -> STACK_32.matcher(text).find();
      case 64 -> STACK_64.matcher(text).find();
      default -> false;
    };
  }

  private static String stripGlyphs(String text) {
    final StringBuilder out = new StringBuilder(text.length());
    int i = 0;
    while (i < text.length()) {
      final int cp = text.codePointAt(i);
      i += Character.charCount(cp);
      if (cp == LOCATION_SYMBOL || cp == NBSP || isJunkGlyph(cp)) {
        out.append(' ');
      } else {
        out.appendCodePoint(cp);
      }
    }
    return out.toString();
  }

  private static boolean isJunkGlyph(int cp) {
    if (cp == REPLACEMENT) {
      return true;
    }
    if (isArrow(cp)) {
      return false;
    }
    return switch (Character.getType(cp)) {
      case Character.CONTROL,
          Character.FORMAT,
          Character.PRIVATE_USE,
          Character.SURROGATE,
          Character.UNASSIGNED,
          Character.OTHER_SYMBOL,
          Character.MODIFIER_SYMBOL ->
          true;
      default -> false;
    };
  }

  /**
   * Arrows are {@code OTHER_SYMBOL}, which {@link #isJunkGlyph} otherwise drops - but Hypixel
   * points sidebar objectives with the eight compass arrows, so they are content and have to
   * survive cleaning.
   */
  private static boolean isArrow(int cp) {
    return (cp >= 0x2190 && cp <= 0x21FF) // Arrows
        || (cp >= 0x2794 && cp <= 0x27BF) // Dingbats arrows, incl. U+27A1 east
        || (cp >= 0x2B00 && cp <= 0x2B11); // Misc Symbols and Arrows, incl. the other seven
  }

  private static int formatCodeLength(String text, int offset) {
    final char first = text.charAt(offset);
    if (first == SECTION) {
      return offset + 1 < text.length() && isVanillaFormatCode(text.charAt(offset + 1)) ? 2 : 0;
    }
    if (first == MOJIBAKE_C2
        && offset + 2 < text.length()
        && text.charAt(offset + 1) == SECTION
        && isVanillaFormatCode(text.charAt(offset + 2))) {
      return 3;
    }
    return 0;
  }

  private static boolean isVanillaFormatCode(char c) {
    return (c >= '0' && c <= '9')
        || (c >= 'a' && c <= 'f')
        || (c >= 'A' && c <= 'F')
        || (c >= 'k' && c <= 'o')
        || (c >= 'K' && c <= 'O')
        || c == 'r'
        || c == 'R'
        || c == 'x'
        || c == 'X';
  }

  private static String stripAnyFormatPair(String text) {
    StringBuilder out = null;
    int i = 0;
    while (i < text.length()) {
      if (text.charAt(i) == SECTION) {
        if (out == null) {
          out = new StringBuilder(text.length());
          out.append(text, 0, i);
        }
        i += i + 1 < text.length() ? 2 : 1;
        continue;
      }

      if (out != null) {
        out.append(text.charAt(i));
      }
      i++;
    }
    return out == null ? text : out.toString();
  }

  private static boolean isCollapsedWhitespace(int cp) {
    return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
  }

  private static String fixCommonMojibake(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder out = null;
    int i = 0;
    while (i < text.length()) {
      final Replacement replacement = mojibakeReplacement(text, i);
      if (replacement != null) {
        if (out == null) {
          out = new StringBuilder(text.length());
          out.append(text, 0, i);
        }
        out.append(replacement.value);
        i += replacement.length;
        continue;
      }

      if (out != null) {
        out.append(text.charAt(i));
      }
      i++;
    }
    return out == null ? text : out.toString();
  }

  private static Replacement mojibakeReplacement(String text, int offset) {
    final char first = text.charAt(offset);
    if (first == MOJIBAKE_C2) {
      if (offset + 1 < text.length() && text.charAt(offset + 1) == SECTION) {
        return new Replacement(String.valueOf(SECTION), 2);
      }
      return new Replacement("", 1);
    }
    if (first != MOJIBAKE_E2 || offset + 2 >= text.length()) {
      return null;
    }

    final char second = text.charAt(offset + 1);
    final char third = text.charAt(offset + 2);
    if (second == '\u008F' && third == MOJIBAKE_A3) {
      return new Replacement(String.valueOf(LOCATION_SYMBOL), 3);
    }
    if (second == '\u0080' && (third == '\u0094' || third == '\u0093')) {
      return new Replacement("-", 3);
    }
    if (second == '\u0086' && third == '\u0092') {
      return new Replacement("->", 3);
    }
    return null;
  }

  private static boolean isMojibakeLocationSymbol(String text, int offset) {
    return offset + 2 < text.length()
        && text.charAt(offset) == MOJIBAKE_E2
        && text.charAt(offset + 1) == '\u008F'
        && text.charAt(offset + 2) == MOJIBAKE_A3;
  }

  private record Replacement(String value, int length) {}
}
