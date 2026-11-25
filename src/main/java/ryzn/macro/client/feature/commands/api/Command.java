package ryzn.macro.client.feature.commands.api;

import com.mojang.brigadier.CommandDispatcher;

/** A client command definition registered by the central command manager. */
@FunctionalInterface
public interface Command {
  /**
   * @description Adds one command definition to the current client dispatcher.
   * @param dispatcher the Ryzn client dispatcher to receive the definition
   */
  void register(CommandDispatcher<RyznCommandSource> dispatcher);
}
