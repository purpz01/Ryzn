package ryzn.macro.client.util.text;

import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

/** Formats Ryzn's in-game chat messages. */
public final class RyznChat {

  private static final int BRAND_YELLOW = 0xFFD54F;
  private static final int BRAND_DIVIDER = 0xA88D49;
  private static final int BODY_GREY = 0xD5CEC0;

  private RyznChat() {}

  public static MutableComponent brandPrefix() {
    return Component.literal("RYZN")
        .withStyle(s -> s.withColor(TextColor.fromRgb(BRAND_YELLOW)).withBold(true))
        .append(
            Component.literal(" |")
                .withStyle(s -> s.withColor(TextColor.fromRgb(BRAND_DIVIDER)).withBold(false)));
  }

  /**
   * A branded chat line: the prefix then the message body (greyed; section colour codes still
   * apply).
   */
  public static Component line(String message) {
    return brandPrefix()
        .append(
            Component.literal(" " + message)
                .withStyle(s -> s.withColor(TextColor.fromRgb(BODY_GREY)).withBold(false)));
  }

  /**
   * Why the current panic sweep is stopping things, or null outside one. Client thread only.
   *
   * <p>While set, every "Stopped X" until {@link #clearStopReason()} is tagged with it, so a panic
   * sweep reads as "Stopped Hunting [Ryzn menu opened]" on the macro's own line instead of needing
   * a second one.
   */
  @Setter private static String stopReason;

  public static void clearStopReason() {
    stopReason = null;
  }

  /**
   * A general feature announcement, always shown (NOT gated behind /dev debug): green "Started X"
   * or red "Stopped X". Use this from a macro/module toggle so the player always sees it start and
   * stop.
   */
  public static void announce(String feature, boolean started) {
    final String why = started || stopReason == null ? "" : " §7[" + stopReason + "]";
    send((started ? "§aStarted §f" : "§cStopped §f") + feature + why);
  }

  /** Display a branded line in the client chat. Pass only the body; the prefix is prepended. */
  public static void send(String message) {
    final Minecraft mc = Minecraft.getInstance();
    if (mc != null && mc.player != null) {
      mc.player.sendSystemMessage(line(message));
    }
  }
}
