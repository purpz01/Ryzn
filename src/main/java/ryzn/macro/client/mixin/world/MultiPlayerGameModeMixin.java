package ryzn.macro.client.mixin.world;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

  @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
  private void ryzn$observeBlockBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
    ryzn.macro.client.core.RyznHooks.onBlockBreak(pos);
  }
}
