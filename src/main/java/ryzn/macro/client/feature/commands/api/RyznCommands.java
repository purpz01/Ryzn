package ryzn.macro.client.feature.commands.api;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.CommandRegistrationEvent;
import ryzn.macro.client.core.events.api.EventBus;

public final class RyznCommands {
  private static CommandDispatcher<RyznCommandSource> dispatcher;

  private RyznCommands() {}

  public static LiteralArgumentBuilder<RyznCommandSource> literal(String name) {
    return LiteralArgumentBuilder.literal(name);
  }

  public static <T> RequiredArgumentBuilder<RyznCommandSource, T> argument(
      String name, ArgumentType<T> type) {
    return RequiredArgumentBuilder.argument(name, type);
  }

  public static void initialize(EventBus bus) {
    if (dispatcher != null) {
      return;
    }
    dispatcher = new CommandDispatcher<>();
    bus.post(new CommandRegistrationEvent(dispatcher));
    var connection = Minecraft.getInstance().getConnection();
    if (connection != null) {
      installSuggestions(connection);
    }
  }

  public static boolean execute(String command) {
    if (!RyznBootstrap.isReady() || dispatcher == null) {
      return false;
    }
    StringReader reader = new StringReader(command);
    String root = reader.readUnquotedString();
    if (dispatcher.getRoot().getChild(root) == null) {
      return false;
    }
    var connection = Minecraft.getInstance().getConnection();
    if (connection == null) {
      return false;
    }
    RyznCommandSource source = (RyznCommandSource) (Object) connection.getSuggestionsProvider();
    try {
      dispatcher.execute(command, source);
    } catch (CommandSyntaxException failure) {
      source.sendError(Component.literal(failure.getMessage()));
    } catch (Exception | LinkageError failure) {
      source.sendError(Component.literal("Ryzn command failed: " + failure.getMessage()));
      RyznBootstrap.fail("client command", failure);
    }
    return true;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void installSuggestions(ClientPacketListener connection) {
    if (dispatcher == null) {
      return;
    }
    Map<CommandNode<RyznCommandSource>, CommandNode<RyznCommandSource>> copies = new HashMap<>();
    CommandDispatcher<RyznCommandSource> target =
        (CommandDispatcher) (Object) connection.getCommands();
    copies.put(dispatcher.getRoot(), target.getRoot());
    copyChildren(dispatcher.getRoot(), target.getRoot(), copies);
  }

  private static void copyChildren(
      CommandNode<RyznCommandSource> origin,
      CommandNode<RyznCommandSource> target,
      Map<CommandNode<RyznCommandSource>, CommandNode<RyznCommandSource>> copies) {
    for (var child : origin.getChildren()) {
      var builder = child.createBuilder();
      builder.requires(source -> RyznBootstrap.isReady() && child.canUse(source));
      if (builder.getCommand() != null) {
        builder.executes(context -> 0);
      }
      if (builder.getRedirect() != null) {
        builder.redirect(copies.get(builder.getRedirect()));
      }
      var copy = builder.build();
      copies.put(child, copy);
      target.addChild(copy);
      copyChildren(child, copy, copies);
    }
  }
}
