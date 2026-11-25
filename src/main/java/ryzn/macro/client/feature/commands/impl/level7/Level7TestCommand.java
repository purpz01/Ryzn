package ryzn.macro.client.feature.commands.impl.level7;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.Level7ActionEvent;
import ryzn.macro.client.feature.commands.api.RyznCommandSource;
import ryzn.macro.client.feature.commands.api.RyznCommands;
import ryzn.macro.client.feature.macros.impl.level7.AutoLvl7Macro;
import ryzn.macro.client.util.text.RyznChat;

/**
 * {@code /ryzn level7} - drive the Level 7 Test Macro orchestrator (see {@link AutoLvl7Macro}).
 *
 * <pre>
 *   /ryzn level7 start    begin the fixed early-game route
 *   /ryzn level7 stop     release all macro controls and stop pathfinding
 *   /ryzn level7 status   show state, detected Farming / Mining, active child, and wait reason
 * </pre>
 */
public final class Level7TestCommand {

  private Level7TestCommand() {}

  public static void register(CommandDispatcher<RyznCommandSource> dispatcher) {
    dispatcher.register(
        RyznCommands.literal("ryzn")
            .then(
                RyznCommands.literal("level7")
                    .then(RyznCommands.literal("start").executes(Level7TestCommand::start))
                    .then(RyznCommands.literal("stop").executes(Level7TestCommand::stop))
                    .then(RyznCommands.literal("status").executes(Level7TestCommand::status))));
  }

  private static int start(CommandContext<RyznCommandSource> ctx) {
    RyznBootstrap.eventBus().post(new Level7ActionEvent(Level7ActionEvent.Action.START));
    return 1;
  }

  private static int stop(CommandContext<RyznCommandSource> ctx) {
    RyznBootstrap.eventBus().post(new Level7ActionEvent(Level7ActionEvent.Action.STOP));
    return 1;
  }

  private static int status(CommandContext<RyznCommandSource> ctx) {
    for (String line : RyznBootstrap.macroManager().getByClass(AutoLvl7Macro.class).statusLines()) {
      ctx.getSource().sendFeedback(RyznChat.line(line));
    }
    return 1;
  }
}
