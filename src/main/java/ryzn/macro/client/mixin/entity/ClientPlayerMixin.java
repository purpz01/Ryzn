package ryzn.macro.client.mixin.entity;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ryzn.macro.client.core.RyznHooks.MacroCameraSmoother;

/**
 * Render-frame rotation smoothing for every macro that drives the local player's look. Each macro
 * advances its smoothed yaw/pitch and writes it to the player ONCE per client tick (the
 * packet-visible, GCD-quantized, human 20 Hz aim); the camera and hand renderer read the
 * partial-tick view getters every frame. We replace their return value with a shared display-only
 * low-pass ({@link MacroCameraSmoother}) that eases the camera toward the live tick angle by real
 * frame time - so stepped 20 Hz (and quantized) rotation reads as glass at any framerate, without
 * touching a single packet.
 *
 * <p>Mojang-mapped equivalents of Yarn's {@code getYaw(F)F} / {@code getPitch(F)F}: {@link
 * LocalPlayer#getViewYRot(float)} and {@link LocalPlayer#getViewXRot(float)} are both declared on
 * {@code LocalPlayer} in 26.2, so this only affects the client player. Yields whenever no macro is
 * driving the look, letting vanilla / the mouse return normally.
 */
@Mixin(LocalPlayer.class)
public class ClientPlayerMixin {

  @Inject(method = "getViewYRot(F)F", at = @At("HEAD"), cancellable = true)
  private void ryzn$interpViewYaw(float partialTick, CallbackInfoReturnable<Float> cir) {
    final LocalPlayer player = (LocalPlayer) (Object) this;
    if (MacroCameraSmoother.isPicking()) {
      return;
    } // interaction/attack raycast: raw angle, leave state untouched
    if (MacroCameraSmoother.isDriven(player)) {
      cir.setReturnValue(MacroCameraSmoother.yaw(player));
    } else {
      MacroCameraSmoother.idle();
    }
  }

  @Inject(method = "getViewXRot(F)F", at = @At("HEAD"), cancellable = true)
  private void ryzn$interpViewPitch(float partialTick, CallbackInfoReturnable<Float> cir) {
    final LocalPlayer player = (LocalPlayer) (Object) this;
    if (MacroCameraSmoother.isPicking()) {
      return;
    }
    if (MacroCameraSmoother.isDriven(player)) {
      cir.setReturnValue(MacroCameraSmoother.pitch(player));
    } else {
      MacroCameraSmoother.idle();
    }
  }
}
