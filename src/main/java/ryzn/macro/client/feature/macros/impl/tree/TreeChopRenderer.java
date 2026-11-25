package ryzn.macro.client.feature.macros.impl.tree;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.render.RyznRenderContext;

/**
 * In-world highlight for the tree-chop macro: fills every log block of each qualifying oak cluster
 * (size &gt;= the density gate) so you can see what the macro considers choppable. Colour encodes
 * status:
 *
 * <ul>
 *   <li><b>cyan</b> - the cluster currently being chopped (the macro's target),
 *   <li><b>green</b> - a qualifying cluster in the fixed tree zone.
 * </ul>
 *
 * The cluster snapshot is published by {@link TreeChopMacro} each scan. Uses MC's own world-render
 * pipeline (camera-relative quads into {@code debugQuads}), the same approach as {@code
 * PathRenderer}.
 */
public final class TreeChopRenderer {

  private static volatile List<LogCluster> clusters = List.of();
  private static volatile BlockPos target = null;

  private TreeChopRenderer() {}

  /**
   * Called by the macro each scan with the qualifying clusters and the current target (may be
   * null).
   *
   * @param qualifying the clumps that passed the density gate
   * @param targetBase the trunk foot of the current target, or null
   */
  public static void publish(List<LogCluster> qualifying, BlockPos targetBase) {
    clusters = (qualifying == null) ? List.of() : List.copyOf(qualifying);
    target = targetBase;
  }

  public static void clear() {
    clusters = List.of();
    target = null;
  }

  /**
   * Registered on {@code CollectSubmitsEvent}.
   *
   * @param ctx the render context of the current frame
   */
  public static void render(RyznRenderContext ctx) {
    if (!RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).chopHighlight()) {
      return;
    }
    final List<LogCluster> snap = clusters; // volatile snapshot
    if (snap.isEmpty()) {
      return;
    }
    final Minecraft mc = Minecraft.getInstance();
    final PoseStack pose = ctx.poseStack();
    final SubmitNodeCollector collector = ctx.submitNodeCollector();
    if (mc.player == null || pose == null || collector == null) {
      return;
    }

    final Vec3 cam = mc.gameRenderer.mainCamera().position();
    final BlockPos tgt = target;
    final double inf = 0.02;

    collector.submitCustomGeometry(
        pose,
        RenderTypes.debugQuads(),
        (last, quads) -> {
          for (LogCluster cluster : snap) {
            final boolean isTarget = tgt != null && cluster.base().equals(tgt);
            final int rgb = isTarget ? 0x00E5FF : 0x3CFF3C;
            final int argb = (rgb & 0x00FFFFFF) | 0x55000000; // ~33% alpha fill
            for (BlockPos b : cluster.blocks()) {
              final double x0 = b.getX() - cam.x - inf;
              final double y0 = b.getY() - cam.y - inf;
              final double z0 = b.getZ() - cam.z - inf;
              addFilledBox(
                  quads,
                  last,
                  x0,
                  y0,
                  z0,
                  x0 + 1 + 2 * inf,
                  y0 + 1 + 2 * inf,
                  z0 + 1 + 2 * inf,
                  argb);
            }
          }
        });
  }

  private static void addFilledBox(
      VertexConsumer vc,
      PoseStack.Pose pose,
      double x0,
      double y0,
      double z0,
      double x1,
      double y1,
      double z1,
      int argb) {
    final float ax = (float) x0;
    final float ay = (float) y0;
    final float az = (float) z0;
    final float bx = (float) x1;
    final float by = (float) y1;
    final float bz = (float) z1;
    quad(vc, pose, ax, ay, az, bx, ay, az, bx, by, az, ax, by, az, argb);
    quad(vc, pose, ax, ay, bz, ax, by, bz, bx, by, bz, bx, ay, bz, argb);
    quad(vc, pose, ax, ay, az, ax, by, az, ax, by, bz, ax, ay, bz, argb);
    quad(vc, pose, bx, ay, az, bx, ay, bz, bx, by, bz, bx, by, az, argb);
    quad(vc, pose, ax, ay, az, ax, ay, bz, bx, ay, bz, bx, ay, az, argb);
    quad(vc, pose, ax, by, az, bx, by, az, bx, by, bz, ax, by, bz, argb);
  }

  private static void quad(
      VertexConsumer vc,
      PoseStack.Pose pose,
      float x1,
      float y1,
      float z1,
      float x2,
      float y2,
      float z2,
      float x3,
      float y3,
      float z3,
      float x4,
      float y4,
      float z4,
      int argb) {
    vc.addVertex(pose, x1, y1, z1).setColor(argb);
    vc.addVertex(pose, x2, y2, z2).setColor(argb);
    vc.addVertex(pose, x3, y3, z3).setColor(argb);
    vc.addVertex(pose, x4, y4, z4).setColor(argb);
  }
}
