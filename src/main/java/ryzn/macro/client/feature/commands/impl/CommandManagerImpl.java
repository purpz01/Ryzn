package ryzn.macro.client.feature.commands.impl;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;
import ryzn.macro.client.core.events.api.CommandRegistrationEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.feature.commands.api.Command;
import ryzn.macro.client.feature.commands.api.CommandManager;
import ryzn.macro.client.feature.commands.api.RyznCommandSource;
import ryzn.macro.client.feature.commands.api.RyznCommands;
import ryzn.macro.client.feature.commands.impl.level7.Level7TestCommand;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;

/** Registers Ryzn controls and the local pathfinder diagnostics. */
public final class CommandManagerImpl implements CommandManager {
  private static final String DEV_ROOT = "dev";

  private List<Command> userCommands;
  private List<Command> devCommands;

  /**
   * @description Records each command registration exactly once, in a stable order.
   */
  @Override
  public void init() {
    if (userCommands != null) {
      return;
    }
    userCommands = List.of(PathfindCommand::register, Level7TestCommand::register);
    devCommands = List.of(PathfindCommand::registerDev);
  }

  /**
   * @description Registers all definitions on the supplied dispatcher in stable list order.
   * @param dispatcher the current Ryzn client dispatcher
   * @throws IllegalStateException if the manager has not been initialized
   */
  @Override
  public void registerAll(CommandDispatcher<RyznCommandSource> dispatcher) {
    if (userCommands == null) {
      throw new IllegalStateException("Command manager is not initialized");
    }
    for (Command command : userCommands) {
      command.register(dispatcher);
    }
    final CommandDispatcher<RyznCommandSource> scratch = new CommandDispatcher<>();
    for (Command command : devCommands) {
      command.register(scratch);
    }
    final LiteralArgumentBuilder<RyznCommandSource> dev = RyznCommands.literal(DEV_ROOT);
    scratch.getRoot().getChildren().forEach(dev::then);
    dispatcher.register(dev);
  }

  /**
   * @description Registers every known command when authenticated startup creates the dispatcher.
   * @param commandregistrationevent the dispatcher registration notification
   */
  @Subscribe
  public void onCommandRegistrationEvent(CommandRegistrationEvent commandregistrationevent) {
    registerAll(commandregistrationevent.dispatcher());
  }
}
