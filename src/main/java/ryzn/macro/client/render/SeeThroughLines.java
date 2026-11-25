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

/** Shares a line render type that keeps world overlays visible through terrain. */
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
