package ryzn.macro.client.render;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import ryzn.macro.client.mixin.accessor.RenderTypeAccessor;

/**
 * The vanilla LINES pipeline with the depth test forced to pass, so a line stays visible behind
 * terrain.
 *
 * <p>Shared rather than per-feature: two of these would be two GPU pipelines doing the same thing,
 * and the route overlay and the hotspot pointer both want exactly it. Same approach Skyblocker
 * takes for its cursor line.
 */
public final class SeeThroughLines {

  private static RenderPipeline pipeline;
  private static RenderType type;

  private SeeThroughLines() {}

  public static RenderType type() {
    if (type == null) {
      type =
          RenderTypeAccessor.ryzn$create(
              "ryzn_lines_seethrough", RenderSetup.builder(pipeline()).createRenderSetup());
    }
    return type;
  }

  static RenderPipeline pipeline() {
    if (pipeline == null) {
      pipeline =
          RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
              .withLocation(Identifier.fromNamespaceAndPath("ryzn", "pipeline/lines_seethrough"))
              .withColorTargetState(ColorTargetState.DEFAULT)
              .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
              .build();
    }
    return pipeline;
  }
}
