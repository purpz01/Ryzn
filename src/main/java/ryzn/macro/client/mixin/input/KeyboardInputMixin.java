package ryzn.macro.client.mixin.input;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stamps Ryzn's own input over the one vanilla just read off the keyboard, then rebuilds the move
 * vector to match. A driving macro wins outright; otherwise Snap Tap gets the player's keys.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
  @Inject(method = "tick", at = @At("TAIL"))
  private void ryzn$overrideInput(CallbackInfo ci) {
    Input resolved = ryzn.macro.client.core.RyznHooks.resolveInput(keyPresses);
    if (resolved == keyPresses) {
      return;
    }
    keyPresses = resolved;
    moveVector =
        new Vec2(
                ryzn$impulse(resolved.left(), resolved.right()),
                ryzn$impulse(resolved.forward(), resolved.backward()))
            .normalized();
  }

  /** Mirrors vanilla {@code KeyboardInput.calculateImpulse}: opposing keys cancel to zero. */
  private static float ryzn$impulse(boolean positive, boolean negative) {
    if (positive == negative) {
      return 0.0f;
    }
    return positive ? 1.0f : -1.0f;
  }
}
