package ryzn.macro.client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ryzn.macro.client.core.RyznHooks;

@Mixin(FramerateLimitTracker.class)
public class FramerateLimitTrackerMixin {

  @ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"))
  private int ryzn$keepMacrosResponsive(int original) {
    return RyznHooks.macroFramerateLimit(original);
  }
}
