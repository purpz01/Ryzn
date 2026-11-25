package ryzn.macro.client.core.events.api;

import com.mojang.brigadier.CommandDispatcher;
import ryzn.macro.client.feature.commands.api.RyznCommandSource;

/**
 * Ryzn's request to populate the current client command dispatcher.
 *
 * @param dispatcher the dispatcher to receive command definitions
 */
public record CommandRegistrationEvent(CommandDispatcher<RyznCommandSource> dispatcher)
    implements Event {}
