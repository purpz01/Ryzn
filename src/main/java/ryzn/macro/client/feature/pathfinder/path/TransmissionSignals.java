package ryzn.macro.client.feature.pathfinder.path;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.util.text.MacroText;

/**
 * What Hypixel says about the Aspect's ability: current mana from the action bar, and the chat or
 * action bar lines that mean a click did nothing. Read-only; {@link TransmissionPathExecutor} polls
 * it.
 */
public final class TransmissionSignals {

  public static final TransmissionSignals INSTANCE = new TransmissionSignals();

  enum Refusal {
    NONE,
    NO_MANA,
    COOLDOWN,
    NOT_HERE
  }

  private static final Pattern MANA = Pattern.compile("([0-9][0-9,]*)/([0-9][0-9,]*)\\s*✎");
  private static final long MANA_STALE_MS = 5_000L;
  private static final long REFUSAL_FRESH_MS = 1_500L;

  private int mana = -1;
  private long manaSeenAtMs;
  private Refusal refusal = Refusal.NONE;
  private long refusalAtMs;

  private TransmissionSignals() {}

  @Subscribe
  public void onGameMessageEvent(GameMessageEvent event) {
    final Component message = event.message();
    if (message == null) {
      return;
    }
    final String text = MacroText.stripFormatting(message.getString());
    if (event.overlay()) {
      final Matcher matcher = MANA.matcher(text);
      if (matcher.find()) {
        mana = parse(matcher.group(1));
        manaSeenAtMs = System.currentTimeMillis();
      }
    }
    final String lower = text.toLowerCase(Locale.ROOT);
    if (lower.contains("not enough mana") || lower.contains("don't have enough mana")) {
      refuse(Refusal.NO_MANA);
    } else if (lower.contains("this ability is on cooldown")) {
      refuse(Refusal.COOLDOWN);
    } else if ((lower.contains("cannot use") || lower.contains("can't use"))
        && (lower.contains("abilit") || lower.contains("item"))
        && (lower.contains("here") || lower.contains("area") || lower.contains("rift"))) {
      refuse(Refusal.NOT_HERE);
    }
  }

  /** Current mana, or -1 when the action bar has not shown it lately. */
  int mana() {
    return System.currentTimeMillis() - manaSeenAtMs <= MANA_STALE_MS ? mana : -1;
  }

  /** The newest refusal seen in the last moment, cleared once read. */
  Refusal takeRefusal() {
    final Refusal seen =
        System.currentTimeMillis() - refusalAtMs <= REFUSAL_FRESH_MS ? refusal : Refusal.NONE;
    refusal = Refusal.NONE;
    return seen;
  }

  private void refuse(Refusal kind) {
    refusal = kind;
    refusalAtMs = System.currentTimeMillis();
  }

  private static int parse(String digits) {
    try {
      return Integer.parseInt(digits.replace(",", ""));
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
