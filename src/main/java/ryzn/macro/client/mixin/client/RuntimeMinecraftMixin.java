package ryzn.macro.client.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ryzn.macro.client.core.RyznRuntime;

@Mixin(Minecraft.class)
public abstract class RuntimeMinecraftMixin {
  @Inject(
      method = "run",
      at =
          @At(
              value = "FIELD",
              target = "Lnet/minecraft/client/Minecraft;gameThread:Ljava/lang/Thread;",
              opcode = 181,
              ordinal = 0,
              shift = At.Shift.AFTER))
  private void ryzn$clientStarted(CallbackInfo ci) {
    RyznRuntime.startClient((Minecraft) (Object) this);
  }

  @Inject(
      method = {"exitWorldAndClose", "close"},
      at = @At("HEAD"))
  private void ryzn$clientStopping(CallbackInfo ci) {
    RyznRuntime.stopClient();
  }

  @Inject(method = "tick", at = @At("HEAD"))
  private void ryzn$startTick(CallbackInfo ci) {
    RyznRuntime.startTick((Minecraft) (Object) this);
  }

  @Inject(method = "tick", at = @At("RETURN"))
  private void ryzn$endTick(CallbackInfo ci) {
    RyznRuntime.endTick((Minecraft) (Object) this);
  }
}
