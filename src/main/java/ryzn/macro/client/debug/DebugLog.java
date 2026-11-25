package ryzn.macro.client.debug;

import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.slf4j.Logger;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.util.text.RyznChat;

/**
 * Debug output for the pathfinder and the macros. Categories gate everything, so nothing is emitted
 * unless debug mode is on ({@code /dev debug}), and {@code error} always reaches the log file. The
 * {@code MACRO} category is quiet on top of that whenever no macro is actually running - see {@link
 * #macroRunning()}.
 *
 * <p>Levels, picked by how often the call site fires:
 *
 * <ul>
 *   <li>{@code detail} - log file only. Per-tick dumps and telemetry walls. Never in chat.
 *   <li>{@code action} - one discrete thing that just happened (a click). Never dropped, never
 *       merged.
 *   <li>{@code event} - per-tick state, printed only when it CHANGES (aim target, held key, held
 *       slot).
 *   <li>{@code trace} / {@code chat} - one readable line per real event, in chat and the log.
 *   <li>{@code error} - something failed; log file always, chat while debugging.
 * </ul>
 *
 * <p>Chat lines are throttled per call site (see {@link #CHAT_REPEAT_MS}), so a message emitted
 * every tick shows up as a once-a-second status line rather than a wall. Write them for someone who
 * has never read this code: say what happened and why, not which internal flag flipped.
 */
public final class DebugLog {

  private static final Logger logger = LogUtils.getLogger();

  private DebugLog() {}

  @RequiredArgsConstructor
  public enum Category {
    NAV("ryzn pathfinder"),
    MACRO("ryzn macro");

    private final String label;
    private boolean enabled = false;
  }

  public static boolean isEnabled(Category category) {
    if (category == null || !category.enabled) {
      return false;
    }
    return category != Category.MACRO || macroRunning();
  }

  /**
   * Macro output describes a macro driving the player. With nothing running, the same call sites
   * report the PLAYER's own clicks, menus and head - which is noise, and it buries the run you
   * actually wanted to read.
   *
   * <p>The grace window is the part that matters: a macro clears its own enabled flag BEFORE it
   * releases the keys, closes its menu and says why it stopped, so cutting the log the instant the
   * flag drops would lose exactly the lines worth having. Anything within a few seconds of the last
   * active tick still prints.
   */
  private static boolean macroRunning() {
    final long now = System.currentTimeMillis();
    // Both, because neither list is complete on its own: MacroLook knows about Torrhus,
    // Dwarven
    // and Can of Worms, and the failsafe's list knows about a bare pathfind and the accessory
    // sorter.
    if (RyznBootstrap.macroManager().anyMacroRunning()
        || RyznBootstrap.failsafeManager().automationActive()) {
      macroActiveUntilMs = now + MACRO_QUIET_GRACE_MS;
      return true;
    }
    return now < macroActiveUntilMs;
  }

  /**
   * How long after the last active macro tick the macro log keeps printing. Covers the whole stop
   * path.
   */
  private static final long MACRO_QUIET_GRACE_MS = 3000L;

  private static volatile long macroActiveUntilMs;

  public static void setEnabled(Category category, boolean enabled) {
    if (category != null) {
      category.enabled = enabled;
    }
  }

  public static void trace(Category category, String fmt, Object... args) {
    if (!isEnabled(category)) {
      return;
    }
    final String message = format(fmt, args);
    logger.info("[{}] {}", tag(category), message);
    sink(category, message);
    showInChat(fmt, message);
  }

  public static void error(Category category, String fmt, Object... args) {
    final String message = format(fmt, args);
    logger.error("[{}] {}", tag(category), message);
    sink(category, message);
    if (isEnabled(category)) {
      showInChat(fmt, message);
    } // surface failures in chat while debugging
  }

  public static void chat(Category category, String fmt, Object... args) {
    if (!isEnabled(category)) {
      return;
    }
    final String message = format(fmt, args);
    logger.info("[{}] {}", tag(category), message);
    sink(category, message);
    showInChat(fmt, message);
  }

  /**
   * One discrete thing the macro just did - a slot click, a mouse click, a menu closing. Never
   * throttled and never deduplicated: these are the lines someone reads back to reconstruct a run,
   * and dropping the second of two identical clicks is exactly how a debug log stops being
   * evidence. Only use it where the call site fires on an actual action, not every tick.
   */
  public static void action(Category category, String fmt, Object... args) {
    if (!isEnabled(category)) {
      return;
    }
    final String message = format(fmt, args);
    logger.info("[{}] {}", tag(category), message);
    sink(category, message);
    emitToChat(message);
  }

  /**
   * One line per CHANGE, for state a macro re-asserts every tick: the key it is holding, the point
   * it is aiming at, the hotbar slot it wants. The message is dropped entirely - log file included
   * - while it matches the last one under {@code key}, so a per-tick call site reads as a list of
   * what actually changed. Pick a {@code key} per piece of state, and keep every message about that
   * state on it, so a return to a previous value still prints.
   */
  public static void event(Category category, String key, String fmt, Object... args) {
    if (!isEnabled(category)) {
      return;
    }
    final String message = format(fmt, args);
    if (message.equals(lastEvent.put(key, message))) {
      return;
    }
    logger.info("[{}] {}", tag(category), message);
    sink(category, message);
    emitToChat(
        message); // a message that just changed is not a heartbeat, so it skips the repeat throttle
  }

  /** Last message emitted under each {@link #event} key. Bounded by the call sites. */
  private static final Map<String, String> lastEvent = new ConcurrentHashMap<>();

  /**
   * Firehose diagnostics - per-tick dumps, coordinate/telemetry walls, anything only useful when
   * read back afterwards. Always written to {@code logs/latest.log}, NEVER echoed to chat: those
   * lines are what buried the readable ones, and no one can follow a 300-character state dump
   * scrolling past 20 times a second.
   */
  public static void detail(Category category, String fmt, Object... args) {
    if (!isEnabled(category)) {
      return;
    }
    final String message = format(fmt, args);
    logger.info("[{}] {}", tag(category), message);
    sink(category, message);
  }

  /** Receives every NAV line while set; the pathfinder flight recorder files them per tick. */
  private static volatile Consumer<String> navSink;

  public static void setNavSink(Consumer<String> sink) {
    navSink = sink;
  }

  private static void sink(Category category, String message) {
    final Consumer<String> target = navSink;
    if (target != null && category == Category.NAV) {
      target.accept(message);
    }
  }

  /**
   * Whether enabled categories also echo to chat, read straight off the persisted setting so there
   * is no second copy of the state to drift. Off means log-file only: every message still reaches
   * {@code logs/latest.log} exactly as before, only the chat spam stops. Per-tick trace output
   * buries real chat - one hunting session emitted 58 route plans plus hundreds of aim lines - and
   * reading the log afterwards is the better workflow anyway.
   */
  public static boolean isChatEcho() {
    final RyznSettings settings = RyznSettings.get();
    return settings == null || !settings.pathfinder().isPathDebugLogOnly();
  }

  /**
   * A message that repeats faster than this is a per-tick heartbeat; chat only needs the latest
   * one.
   */
  private static final long CHAT_REPEAT_MS = 1000L;

  /**
   * Last time each message shape reached chat, keyed by its format string (bounded by the call
   * sites).
   */
  private static final Map<String, Long> lastChatAt = new ConcurrentHashMap<>();

  /**
   * Print one line to the in-game chat. Called only on the client thread (trace/chat/error sites).
   * The same call site is throttled to one line per second so a per-tick log reads as a status
   * update instead of a wall; the log file still gets every single one.
   */
  private static void showInChat(String key, String message) {
    if (!isChatEcho()) {
      return;
    }
    final Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.player == null) {
      return;
    }
    final long now = System.currentTimeMillis();
    final Long last = lastChatAt.get(key == null ? message : key);
    if (last != null && now - last < CHAT_REPEAT_MS) {
      return;
    }
    lastChatAt.put(key == null ? message : key, now);
    mc.player.sendSystemMessage(styledLine(message));
  }

  /**
   * Print one line to chat with no throttle, for callers that already guarantee they do not repeat.
   */
  private static void emitToChat(String message) {
    if (!isChatEcho()) {
      return;
    }
    final Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.player == null) {
      return;
    }
    mc.player.sendSystemMessage(styledLine(message));
  }

  private static final int DEBUG_AMBER = 0xE5B951;
  private static final int MESSAGE_IVORY = 0xD5CEC0;

  public static Component styledLine(String message) {
    return RyznChat.brandPrefix()
        .append(
            Component.literal(" TRACE")
                .withStyle(
                    s ->
                        s.withColor(TextColor.fromRgb(DEBUG_AMBER))
                            .withBold(false)
                            .withItalic(true)))
        .append(
            Component.literal(" " + message)
                .withStyle(s -> s.withColor(TextColor.fromRgb(MESSAGE_IVORY)).withBold(false)));
  }

  private static String tag(Category category) {
    return category == null ? "ryzn" : category.label;
  }

  private static String format(String fmt, Object... args) {
    if (fmt == null) {
      return "";
    }
    if (args == null || args.length == 0) {
      return fmt;
    }
    try {
      return String.format(fmt, args);
    } catch (RuntimeException e) {
      return fmt;
    }
  }
}
