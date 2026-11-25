package ryzn.macro.client.feature.macros.impl.foragingquest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import ryzn.macro.client.mixin.accessor.ChatComponentAccessor;
import ryzn.macro.client.util.text.MacroText;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class ForagingChatWaits {

  enum Result {
    WAITING,
    MATCHED,
    FAILED
  }

  record Outcome(Result result, String message) {

    static Outcome waiting() {
      return new Outcome(Result.WAITING, null);
    }

    static Outcome matched() {
      return new Outcome(Result.MATCHED, null);
    }

    static Outcome failed(String message) {
      return new Outcome(Result.FAILED, message);
    }
  }

  private final ForagingChatHistory history;

  Outcome objective(String objective, boolean timedOut) {
    if (history.containsObjectiveSince(history.stateStartSequence(), objective)) {
      return Outcome.matched();
    }
    return timedOut ? Outcome.failed("Timed out waiting for " + objective) : Outcome.waiting();
  }

  Outcome containsSince(int sinceSequence, String needle, boolean timedOut) {
    if (history.containsSince(sinceSequence, needle)) {
      return Outcome.matched();
    }
    return timedOut ? Outcome.failed("Timed out waiting for " + needle) : Outcome.waiting();
  }

  boolean hasObjectiveSinceState(String objective) {
    return history.containsObjectiveSince(history.stateStartSequence(), objective);
  }

  boolean trapPlaced(int trapNumber) {
    return history.containsSince(
        history.stateStartSequence(), "placed trap (" + trapNumber + "/3)");
  }

  ClickEvent helpMolbertClick() {
    return history.findClickSinceState("help molbert");
  }

  /**
   * The FIRST option's click on a "Select an option" prompt since the state started. Hypixel lists
   * the brackets left-to-right, so the first clickable is the first bracket ([Sure] for the Lumber
   * Jack, [Yeah] for Kelly). Null until the dialogue appears.
   */
  ClickEvent firstSelectOptionClick() {
    return history.findClickSinceState("select an option");
  }

  /**
   * The click of the "Select an option" bracket whose text contains optionText (e.g. "sure" picks
   * [Sure...] even when a wrong option like [You look like a mole yourself] is listed first). Null
   * until the dialogue appears.
   */
  ClickEvent selectOptionClick(String optionText) {
    return history.findOptionClickSinceState("select an option", optionText);
  }

  ClickEvent selectOptionClickOrFirst(String optionText) {
    ClickEvent click = selectOptionClick(optionText);
    return click == null ? firstSelectOptionClick() : click;
  }

  ClickEvent visibleOptionClick(Minecraft mc, String optionText) {
    for (GuiMessage.Line line : recentPromptLines(mc)) {
      ClickEvent click = ForagingChatHistory.findOptionClick(line.content(), optionText);
      if (click != null) {
        return click;
      }
    }
    return null;
  }

  /** Every styled span of the newest visible prompt, for diagnosing a prompt no option matched. */
  String describeVisiblePrompt(Minecraft mc) {
    List<GuiMessage.Line> lines = recentPromptLines(mc);
    if (lines.isEmpty()) {
      return "no visible prompt";
    }
    StringBuilder out = new StringBuilder();
    lines
        .getFirst()
        .parent()
        .content()
        .visit(
            (style, text) -> {
              out.append('"').append(text).append("\" click=").append(style.getClickEvent());
              out.append(" | ");
              return Optional.empty();
            },
            Style.EMPTY);
    return out.toString();
  }

  private static List<GuiMessage.Line> recentPromptLines(Minecraft mc) {
    if (mc.gui == null || !(mc.gui.hud.getChat() instanceof ChatComponentAccessor chat)) {
      return List.of();
    }
    List<GuiMessage.Line> lines = new ArrayList<>();
    int checked = 0;
    for (GuiMessage.Line line : chat.ryzn$trimmedMessages()) {
      if (++checked > 30) {
        break;
      }
      if (MacroText.normalizeSearchText(line.parent().content().getString())
          .contains("select an option")) {
        lines.add(line);
      }
    }
    return lines;
  }
}
