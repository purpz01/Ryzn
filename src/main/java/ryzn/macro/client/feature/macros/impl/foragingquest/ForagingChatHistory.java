package ryzn.macro.client.feature.macros.impl.foragingquest;

import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Accessors(fluent = true)
final class ForagingChatHistory {

  private final int historyLimit;
  private final int objectiveGraceLines;
  private final ArrayDeque<Entry> entries = new ArrayDeque<>();

  @Getter(AccessLevel.PACKAGE)
  private int sequence;

  @Getter(AccessLevel.PACKAGE)
  private int stateStartSequence;

  @Getter(AccessLevel.PACKAGE)
  private int travelStartSequence;

  void add(String normalizedText, Component component) {
    entries.addLast(new Entry(++sequence, normalizedText, component));
    while (entries.size() > historyLimit) {
      entries.removeFirst();
    }
  }

  void markStateStart() {
    stateStartSequence = sequence;
  }

  void markTravelStart() {
    travelStartSequence = sequence;
  }

  boolean containsSinceState(String needle) {
    return containsSince(stateStartSequence, needle);
  }

  boolean containsSince(int sinceSequence, String needle) {
    String cleanNeedle = normalizeSearchText(needle);
    for (Entry entry : entries) {
      if (entry.sequence <= sinceSequence) {
        continue;
      }
      if (entry.text.contains(cleanNeedle)) {
        return true;
      }
    }
    return false;
  }

  boolean containsObjectiveSince(int sinceSequence, String objectiveNeedle) {
    String cleanObjective = normalizeSearchText(objectiveNeedle);
    int startSequence = Math.max(0, sinceSequence - objectiveGraceLines);
    boolean sawObjectiveHeader = false;
    for (Entry entry : entries) {
      if (entry.sequence <= startSequence) {
        continue;
      }
      boolean header = entry.text.contains("new objective");
      boolean objective = entry.text.contains(cleanObjective);
      if (header && objective) {
        return true;
      }
      if (header) {
        sawObjectiveHeader = true;
        continue;
      }
      if (sawObjectiveHeader && objective) {
        return true;
      }
    }
    return false;
  }

  /** The first clickable option on a matching line since the state started, or null. */
  ClickEvent findClickSinceState(String lineNeedle) {
    return findOptionClickSinceState(lineNeedle, "");
  }

  /**
   * The click of the option on a matching line whose own text contains optionNeedle (e.g. line
   * "select an option", option "sure" picks [Sure...] wherever it sits). Null until found.
   */
  ClickEvent findOptionClickSinceState(String lineNeedle, String optionNeedle) {
    String cleanNeedle = normalizeSearchText(lineNeedle);
    int promptSequence = -1;
    for (Entry entry : entries) {
      if (entry.sequence <= stateStartSequence) {
        continue;
      }
      if (entry.text.contains(cleanNeedle)) {
        promptSequence = entry.sequence;
      }
      if (promptSequence < 0) {
        continue;
      }
      if (entry.sequence > promptSequence + 2) {
        promptSequence = -1;
        continue;
      }
      if (entry.component == null) {
        continue;
      }
      ClickEvent click = findOptionClick(entry.component.getVisualOrderText(), optionNeedle);
      if (click != null) {
        return click;
      }
    }
    return null;
  }

  void reset() {
    entries.clear();
    sequence = 0;
    stateStartSequence = 0;
    travelStartSequence = 0;
  }

  static ClickEvent findOptionClick(FormattedCharSequence text, String option) {
    String cleanOption = normalizeSearchText(option);
    Map<ClickEvent, StringBuilder> clickableText = new LinkedHashMap<>();
    text.accept(
        (index, style, codepoint) -> {
          ClickEvent click = style.getClickEvent();
          if (click != null) {
            clickableText
                .computeIfAbsent(click, ignored -> new StringBuilder())
                .appendCodePoint(codepoint);
          }
          return true;
        });
    for (Map.Entry<ClickEvent, StringBuilder> entry : clickableText.entrySet()) {
      if (normalizeSearchText(entry.getValue().toString()).contains(cleanOption)
          && answersOption(entry.getKey())) {
        return entry.getKey();
      }
    }
    return null;
  }

  /** Command options and server-defined actions answer a prompt; links and pages do not. */
  private static boolean answersOption(ClickEvent click) {
    return switch (click) {
      case ClickEvent.RunCommand run -> !run.command().isBlank();
      case ClickEvent.SuggestCommand suggest -> !suggest.command().isBlank();
      case ClickEvent.Custom custom -> true;
      default -> false;
    };
  }

  private record Entry(int sequence, String text, Component component) {}
}
