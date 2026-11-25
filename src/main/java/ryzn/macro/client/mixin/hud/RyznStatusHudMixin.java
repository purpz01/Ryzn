package ryzn.macro.client.mixin.hud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ryzn.macro.client.core.RyznHooks;

@Mixin(Hud.class)
public class RyznStatusHudMixin {
  @Inject(method = "extractRenderState", at = @At("TAIL"))
  private void ryzn$renderStatusHud(
      GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
    RyznHooks.renderStatusHud(graphics);
  }
}
