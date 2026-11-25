package ryzn.macro.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.CollectSubmitsEvent;

/**
 * Minimal in-world debug-draw helper for the path executor's optional overlay. Self-contained: it
 * pulls the camera from {@link Minecraft} and defers the geometry to this frame's {@code
 * COLLECT_SUBMITS} phase, so it needs no {@code RyznRenderContext} threaded through the executor's
 * render callback. Only used when the navigation debug overlay is enabled; otherwise these methods
 * are never called.
 *
 * <p>Vertices are emitted camera-relative (world coordinates minus the camera position, flushed
 * through the context pose), matching how Fabric world-render events expect geometry. Line vertices
 * supply the LINE_WIDTH / NORMAL elements required by the {@code lines} format.
 */
public final class RenderUtil {

  private static final float LINE_WIDTH = 2.0f;

  private record PendingLine(Vec3 from, Vec3 to, int argb) {}

  private record PendingShape(VoxelShape shape, int argb) {}

  private static final List<PendingLine> pendingLines = new ArrayList<>();
  private static final List<PendingShape> pendingShapes = new ArrayList<>();
  private static boolean registered;

  private RenderUtil() {}

  public static void drawLine(Vec3 from, Vec3 to, int argb) {
    if (from == null || to == null) {
      return;
    }
    final Vec3 cam = cameraPos();
    if (cam == null) {
      return;
    }

    synchronized (RenderUtil.class) {
      pendingLines.add(new PendingLine(from.subtract(cam), to.subtract(cam), argb));
    }
  }

  /** Flushes this tick's deferred debug primitives as one lines submit. */
  static void collectSubmits(RyznRenderContext ctx) {
    final List<PendingLine> lines;
    final List<PendingShape> shapes;
    synchronized (RenderUtil.class) {
      if (pendingLines.isEmpty() && pendingShapes.isEmpty()) {
        return;
      }
      lines = List.copyOf(pendingLines);
      shapes = List.copyOf(pendingShapes);
      pendingLines.clear();
      pendingShapes.clear();
    }
    ctx.submitNodeCollector()
        .submitCustomGeometry(
            ctx.poseStack(),
            RenderTypes.LINES,
            (pose, buffer) -> {
              for (PendingLine l : lines) {
                writeEdge(pose, buffer, l.from(), l.to(), l.argb());
              }
              for (PendingShape s : shapes) {
                renderShapeEdges(pose, buffer, s.shape(), s.argb());
              }
            });
  }

  public static synchronized void register() {
    if (!registered) {
      registered = true;
      RyznBootstrap.eventBus()
          .subscribe(CollectSubmitsEvent.class, event -> collectSubmits(event.context()));
    }
  }

  private static void writeEdge(
      PoseStack.Pose pose, VertexConsumer lines, Vec3 from, Vec3 to, int argb) {
    double nx = to.x - from.x;
    double ny = to.y - from.y;
    double nz = to.z - from.z;
    final double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
    if (len < 1.0e-6) {
      nx = 0.0;
      ny = 1.0;
      nz = 0.0;
    } else {
      nx /= len;
      ny /= len;
      nz /= len;
    }
    lines
        .addVertex(pose, (float) from.x, (float) from.y, (float) from.z)
        .setColor(argb)
        .setLineWidth(LINE_WIDTH)
        .setNormal(pose, (float) nx, (float) ny, (float) nz);
    lines
        .addVertex(pose, (float) to.x, (float) to.y, (float) to.z)
        .setColor(argb)
        .setLineWidth(LINE_WIDTH)
        .setNormal(pose, (float) nx, (float) ny, (float) nz);
  }

  /** Replacement for the removed vanilla ShapeRenderer.renderShape: box edges as coloured lines. */
  private static void renderShapeEdges(
      PoseStack.Pose pose, VertexConsumer lines, VoxelShape shape, int argb) {
    shape.forAllEdges(
        (x1, y1, z1, x2, y2, z2) ->
            writeEdge(pose, lines, new Vec3(x1, y1, z1), new Vec3(x2, y2, z2), argb));
  }

  private static Vec3 cameraPos() {
    final Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.gameRenderer == null) {
      return null;
    }
    final Camera camera = mc.gameRenderer.mainCamera();
    return (camera != null && camera.isInitialized()) ? camera.position() : null;
  }
}
