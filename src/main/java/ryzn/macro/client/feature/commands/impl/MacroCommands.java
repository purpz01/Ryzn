package ryzn.macro.client.feature.commands.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.mixin.accessor.ScreenAccessor;

public final class MacroCommands {

  private MacroCommands() {}

  public static String stripLeadingSlash(String command) {
    if (command == null) {
      return "";
    }
    String clean = command.trim();
    while (clean.startsWith("/")) {
      clean = clean.substring(1).trim();
    }
    return clean;
  }

  public static String sendCommand(Minecraft mc, String command) {
    final String clean = stripLeadingSlash(command);
    if (clean.isBlank() || mc == null || mc.getConnection() == null) {
      return "";
    }
    RyznBootstrap.failsafeManager().onCommand(clean);
    mc.getConnection().sendCommand(clean);
    return clean;
  }

  public static String runChatClick(Minecraft mc, String command) {
    final String clean = command == null ? "" : command.trim();
    if (clean.isBlank() || mc == null || mc.getConnection() == null) {
      return "";
    }
    if (clean.startsWith("/")) {
      final String slashless = stripLeadingSlash(clean);
      RyznBootstrap.failsafeManager().onCommand(slashless);
      mc.getConnection().sendCommand(slashless);
      return slashless;
    }
    mc.getConnection().sendChat(clean);
    return clean;
  }

  /**
   * Answers a clickable chat option. Command options keep the command path above; an option that
   * carries a server-defined {@link ClickEvent.Custom} has no command to send, so it goes through
   * vanilla's own chat click handler, which sends the same packet a mouse click on it does.
   */
  public static boolean runChatClick(Minecraft mc, ClickEvent click) {
    if (mc == null || mc.player == null || mc.getConnection() == null) {
      return false;
    }
    return switch (click) {
      case ClickEvent.RunCommand run -> !runChatClick(mc, run.command()).isEmpty();
      case ClickEvent.SuggestCommand suggest -> !runChatClick(mc, suggest.command()).isEmpty();
      case ClickEvent.Custom custom -> {
        ScreenAccessor.ryzn$defaultHandleGameClickEvent(custom, mc, mc.gui.screen());
        yield true;
      }
      case null, default -> false;
    };
  }
}
