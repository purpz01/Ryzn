package ryzn.macro.client.render;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import ryzn.macro.client.mixin.accessor.RenderTypeAccessor;

/**
 * The vanilla debug-filled pipeline with the depth test forced to pass, so a filled face stays
 * visible behind terrain.
 *
 * <p>Shared for the same reason as {@link SeeThroughLines}: two of these would be two GPU pipelines
 * doing identical work. The route overlay and the head-node overlay both want exactly it.
 */
public final class SeeThroughQuads {

  private static RenderType type;

  private SeeThroughQuads() {}

  public static RenderType type() {
    if (type == null) {
      final RenderPipeline pipeline =
          RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
              .withLocation(
                  Identifier.fromNamespaceAndPath("ryzn", "pipeline/path_quads_seethrough"))
              .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
              .build();
      type =
          RenderTypeAccessor.ryzn$create(
              "ryzn_path_quads_seethrough",
              RenderSetup.builder(pipeline).sortOnUpload().createRenderSetup());
    }
    return type;
  }
}
