package ryzn.macro.client.mixin.input;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ryzn.macro.client.core.RyznHooks.MouseInputHooks;

/**
 * While the pathfinder is driving the player, suppress mouse-look so the bot's capped yaw slew
 * isn't fought by accumulated mouse movement (no jitter). Client-side only; cancels the rotation,
 * sends nothing.
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

  @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
  private void ryzn$blockManualClicksWhileMacroOwnsInput(
      long window, MouseButtonInfo button, int action, CallbackInfo ci) {
    if (MouseInputHooks.blocksClick(button.button(), action)) {
      ci.cancel();
    }
  }

  @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
  private void ryzn$blockManualScrollWhileMining(
      long window, double horizontal, double vertical, CallbackInfo ci) {
    if (MouseInputHooks.blocksScroll(vertical)) {
      ci.cancel();
    }
  }

  @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
  private void ryzn$keepCursorReleasedWhileMacroRuns(CallbackInfo ci) {
    if (MouseInputHooks.keepsCursorReleased()) {
      ci.cancel();
    }
  }

  @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
  private void ryzn$lockMouseWhileBotting(double partialTick, CallbackInfo ci) {
    if (MouseInputHooks.blocksLook()) {
      ci.cancel();
    }
  }
}
