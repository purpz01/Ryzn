package ryzn.macro.client.mixin.accessor;

import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.spongepowered.asm.mixin.Mixin;
import ryzn.macro.client.feature.commands.api.RyznCommandSource;

@Mixin(ClientSuggestionProvider.class)
public abstract class RyznCommandSourceMixin implements RyznCommandSource {}
