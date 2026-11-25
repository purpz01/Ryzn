package ryzn.macro.client.feature.macros.impl.sand;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.render.RyznRenderContext;

/** Draws the saved Sand Miner route and its looping final segment. */
final class SandZoneRenderer {

  private static final int ROUTE_COLOR = 0xCCB16CFF;
  private static final int POINT_COLOR = 0xFFD8B4FF;
  private static final int ACTIVE_POINT_COLOR = 0xFF48E05A;
  private static final float LINE_WIDTH = 2.5f;
  private static final double POINT_HALF = 0.22;

  private SandZoneRenderer() {}

  static void render(SandMinerMacro macro, RyznRenderContext context) {
    if (!macro.isShowRoute()) {
      return;
    }
    List<int[]> route = macro.routePoints();
    if (route.isEmpty()) {
      return;
    }
    PoseStack pose = context.poseStack();
    SubmitNodeCollector collector = context.submitNodeCollector();
    if (pose == null || collector == null) {
      return;
    }

    Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
    int active = macro.currentRouteIndex();
    collector.submitCustomGeometry(
        pose,
        RenderTypes.LINES,
        (last, lines) -> {
          for (int i = 0; i < route.size(); i++) {
            int[] point = route.get(i);
            drawBox(
                lines,
                last,
                camera,
                point[0] + 0.5,
                point[1] + 1.08,
                point[2] + 0.5,
                POINT_HALF,
                i == active ? ACTIVE_POINT_COLOR : POINT_COLOR);
            if (route.size() > 1) {
              int[] next = route.get((i + 1) % route.size());
              line(
                  lines,
                  last,
                  camera,
                  point[0] + 0.5,
                  point[1] + 1.08,
                  point[2] + 0.5,
                  next[0] + 0.5,
                  next[1] + 1.08,
                  next[2] + 0.5,
                  ROUTE_COLOR);
            }
          }
        });
  }

  private static void drawBox(
      VertexConsumer lines,
      PoseStack.Pose pose,
      Vec3 camera,
      double cx,
      double cy,
      double cz,
      double half,
      int color) {
    double x0 = cx - half;
    double x1 = cx + half;
    double y0 = cy - half;
    final double y1 = cy + half;
    double z0 = cz - half;
    double z1 = cz + half;
    line(lines, pose, camera, x0, y0, z0, x1, y0, z0, color);
    line(lines, pose, camera, x1, y0, z0, x1, y0, z1, color);
    line(lines, pose, camera, x1, y0, z1, x0, y0, z1, color);
    line(lines, pose, camera, x0, y0, z1, x0, y0, z0, color);
    line(lines, pose, camera, x0, y1, z0, x1, y1, z0, color);
    line(lines, pose, camera, x1, y1, z0, x1, y1, z1, color);
    line(lines, pose, camera, x1, y1, z1, x0, y1, z1, color);
    line(lines, pose, camera, x0, y1, z1, x0, y1, z0, color);
    line(lines, pose, camera, x0, y0, z0, x0, y1, z0, color);
    line(lines, pose, camera, x1, y0, z0, x1, y1, z0, color);
    line(lines, pose, camera, x1, y0, z1, x1, y1, z1, color);
    line(lines, pose, camera, x0, y0, z1, x0, y1, z1, color);
  }

  private static void line(
      VertexConsumer lines,
      PoseStack.Pose pose,
      Vec3 camera,
      double ax,
      double ay,
      double az,
      double bx,
      double by,
      double bz,
      int color) {
    double dx = bx - ax;
    double dy = by - ay;
    double dz = bz - az;
    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (length < 1.0E-6) {
      return;
    }
    float nx = (float) (dx / length);
    float ny = (float) (dy / length);
    float nz = (float) (dz / length);
    lines
        .addVertex(pose, (float) (ax - camera.x), (float) (ay - camera.y), (float) (az - camera.z))
        .setColor(color)
        .setLineWidth(LINE_WIDTH)
        .setNormal(pose, nx, ny, nz);
    lines
        .addVertex(pose, (float) (bx - camera.x), (float) (by - camera.y), (float) (bz - camera.z))
        .setColor(color)
        .setLineWidth(LINE_WIDTH)
        .setNormal(pose, nx, ny, nz);
  }
}
