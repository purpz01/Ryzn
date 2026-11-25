package ryzn.macro.client.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ryzn.macro.client.core.RyznRuntime;

@Mixin(LevelRenderer.class)
public abstract class RuntimeLevelRendererMixin {
  @Unique private PoseStack ryzn$pose;
  @Shadow @Final private SubmitNodeStorage submitNodeStorage;

  @Inject(
      method = "lambda$addMainPass$0",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
              ordinal = 0))
  private void ryzn$startMain(CallbackInfo ci) {
    RyznRuntime.startMain(submitNodeStorage);
  }

  @ModifyExpressionValue(
      method = "submitFeatures",
      at = @At(value = "NEW", target = "Lcom/mojang/blaze3d/vertex/PoseStack;"))
  private PoseStack ryzn$capturePose(PoseStack pose) {
    ryzn$pose = pose;
    return pose;
  }

  @Inject(method = "submitFeatures", at = @At("RETURN"))
  private void ryzn$collectSubmits(
      LevelRenderState state,
      SubmitNodeCollector collector,
      boolean renderBlockOutline,
      CallbackInfo ci) {
    RyznRuntime.collectSubmits(ryzn$pose, collector, state.cameraRenderState);
    ryzn$pose = null;
  }
}
