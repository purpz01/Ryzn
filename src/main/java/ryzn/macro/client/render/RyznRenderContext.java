package ryzn.macro.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;

public record RyznRenderContext(
    PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {

  /** A context for hooks that run where the game has no camera state to hand over. */
  public RyznRenderContext(PoseStack poseStack, SubmitNodeCollector submitNodeCollector) {
    this(poseStack, submitNodeCollector, null);
  }
}
