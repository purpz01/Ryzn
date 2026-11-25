package ryzn.macro.client.feature.commands.api;

import com.mojang.brigadier.CommandDispatcher;

/** Process-lifetime owner of client command registration order. */
public interface CommandManager {
  /**
   * @description Records the known command definitions once before Fabric asks for a dispatcher.
   */
  void init();

  /**
   * @description Adds all known commands to one new dispatcher in their original order.
   * @param dispatcher the Ryzn client dispatcher to populate
   * @throws IllegalStateException if called before initialization
   */
  void registerAll(CommandDispatcher<RyznCommandSource> dispatcher);
}
