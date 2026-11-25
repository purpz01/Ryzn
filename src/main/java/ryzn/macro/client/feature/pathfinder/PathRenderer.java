package ryzn.macro.client.feature.pathfinder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.feature.pathfinder.path.PathExecutor;
import ryzn.macro.client.feature.pathfinder.path.VisualPathSpline;
import ryzn.macro.client.render.RyznRenderContext;
import ryzn.macro.client.render.SeeThroughLines;
import ryzn.macro.client.render.SeeThroughQuads;

/**
 * In-world route overlay, drawn whenever a route is active - the default navigation visual, not a
 * debug feature. Two styles, chosen by the "Node boxes" toggle in Visuals -> Path Visuals:
 *
 * <ul>
 *   <li>{@link #drawCurve} (default) - a pink line through the executor's trail above the blocks
 *       the route stands on, using its foot heights.
 *   <li>{@link #drawBoxes} - a glassy box per turn point linked by a straight gradient polyline.
 * </ul>
 *
 * <p>Both styles share the same node set: gap-filler nodes Ryzn inserts for steering aren't drawn,
 * and nodes on a straight run between their neighbours are culled, so what is left are the real
 * corners. Both carry one start->end gradient, so the route reads as a single flowing colour.
 *
 * <p>Uses Minecraft's own world-render pipeline via Fabric's {@code COLLECT_SUBMITS} event
 * (vertices emitted camera-relative, per the API contract). Everything renders see-through (custom
 * no-depth-test render types, {@link #seeThroughQuads()} / {@link #seeThroughLines()}) so the route
 * stays visible behind terrain. Skija was considered but is a 2D canvas with no access to the
 * world's 3D camera transform, so it can only draw a flat HUD overlay, not in-world geometry.
 */
public final class PathRenderer {

  // Node visual style - a translucent gradient fill per turn point (engine key node), fitted to the
  // block's
  // own shape, no outline. The gap-fillers Ryzn inserts for steering aren't drawn, so only the
  // important
  // nodes show. The fill is densest at the block base and fades toward the top (a vertical alpha
  // gradient).
  private static final int FILL_ALPHA_BOTTOM = 0xC0; // ~75% at the base
  private static final int FILL_ALPHA_TOP = 0x1A; // ~10% at the top (low, not fully clear)
  private static final float OUTLINE_WIDTH = 2.5f;
  private static final double INFLATE = 0.02; // lift faces/edges off the block surface (z-fight)

  private static final int START_COLOR = 0xffffb4df;
  private static final int END_COLOR = 0xffff459f;

  private static final float LINK_WIDTH = 2.5f;
  private static final double COLLINEAR_EPS =
      0.1; // max perpendicular offset (blocks) to treat as straight

  private static final double SAMPLE_STEP = 0.35; // blocks between sampled curve points
  private static final float ROUTE_GLOW_WIDTH = 6.0f;
  private static final double TICK_HALF_WIDTH = 0.32;
  private static final float TICK_WIDTH = 2.0f;
  private static final double LEAD_DISTANCE = 3.0; // curve style: blocks drawn ahead of the player
  private static final double FADE_DISTANCE =
      1.0; // curve style: blocks the leading edge fades over

  // Progressive "laying-down" reveal, shared by both styles: the route draws in ahead of the player
  // and
  // advances with them, a pure function of the interpolated player position (no easing, so it never
  // lags).
  // Off (the "Draw the whole route at once" toggle) reveals everything the moment the route
  // resolves.
  private static final double LEAD_NODES = 2.0; // box style: key-nodes drawn ahead of the player
  private static final double REVEAL_BAND = 0.5; // node-index span over which a node fades/rises in
  private static final double REVEAL_MIN_SCALE =
      0.4; // starting height fraction as a node materialises

  // See-through render types (built lazily on first render): clones of vanilla LINES / DEBUG_QUADS
  // with the
  // depth test forced to ALWAYS_PASS (no depth write) so the overlay draws over terrain instead of
  // behind it.

  /**
   * Debug flag: backs {@code /dev debug}. Now only gates verbose pathfind chat + tree-macro debug
   * draws.
   */
  @Getter @Setter private static volatile boolean enabled = false;

  private static volatile List<Waypoint> path = List.of();

  /**
   * Render-only route node. {@code key} marks an engine turn point (drawn as a glassy box);
   * gap-fillers inserted for steering are {@code key=false}. {@code action} is retained from the
   * planner (unused here).
   */
  public record Waypoint(
      int x,
      int y,
      int z,
      double centerX,
      double centerY,
      double centerZ,
      int action,
      boolean key) {
    Waypoint(int x, int y, int z, double centerX, double centerZ, int action, boolean key) {
      this(x, y, z, centerX, y, centerZ, action, key);
    }

    Waypoint(int x, int y, int z, int action, boolean key) {
      this(x, y, z, x + 0.5, y, z + 0.5, action, key);
    }
  }

  private PathRenderer() {}

  public static void setPath(List<Waypoint> p) {
    path = (p == null || p.isEmpty()) ? List.of() : List.copyOf(p);
  }

  public static void clear() {
    path = List.of();
  }

  /** Registered on {@code CollectSubmitsEvent}. */
  public static void render(RyznRenderContext ctx) {
    if (RyznSettings.get().combat().isCombatRecordingClean()) {
      return; // recording mode: keep the path lines off-screen while the overlay toggle itself
      // stays as-is
    }
    final List<Waypoint> p = path; // volatile snapshot
    final boolean boxes = RyznSettings.get().pathfinder().isPathDrawBoxes();
    PathExecutor executor = PathExecutor.instance;
    boolean drawExecution =
        !boxes && executor.isActive() && !executor.getRouteFootPoints().isEmpty();
    if (p.isEmpty() && !drawExecution) {
      return;
    }
    final PoseStack pose = ctx.poseStack();
    final SubmitNodeCollector collector = ctx.submitNodeCollector();
    if (pose == null || collector == null) {
      return;
    }
    final Minecraft mc = Minecraft.getInstance();
    final ClientLevel level = mc.level;
    final Vec3 cam = mc.gameRenderer.mainCamera().position();
    if (drawExecution) {
      drawExecutionTrail(mc, cam, pose, collector, executor);
      return;
    }

    // Drawn nodes = engine key nodes only (gap-fillers aren't drawn), with nodes that sit on a
    // straight
    // run between their neighbours culled so a long straight line shows just its two endpoints.
    final List<Waypoint> keyNodes = new ArrayList<>();
    for (final Waypoint w : p) {
      if (w.key()) {
        keyNodes.add(w);
      }
    }
    final List<Waypoint> drawn = cullCollinear(keyNodes);
    if (boxes) {
      drawBoxes(level, cam, pose, collector, drawn);
    } else {
      drawCurve(mc, level, cam, pose, collector, drawn);
    }
  }

  /**
   * Box style: a glassy box fitted to each turn point's block shape, linked by a straight gradient
   * polyline that is cut where it passes inside a box so it reads as entering and leaving rather
   * than crossing glass.
   */
  private static void drawBoxes(
      ClientLevel level,
      Vec3 cam,
      PoseStack pose,
      SubmitNodeCollector collector,
      List<Waypoint> drawn) {
    final Minecraft mc = Minecraft.getInstance();
    final int n = drawn.size();

    // Every key node gets its OWN vertical gradient (bottom accent A -> top accent B), so the
    // gradient is visible on each node, not just as a flat colour that shifts across the route. The
    // connecting line keeps a route-progression gradient (node i's hue -> node i+1's hue) so the
    // whole
    // path still flows through the palette.
    final int[] hues = new int[n];
    for (int i = 0; i < n; i++) {
      final double t = n <= 1 ? 0.0 : (double) i / (double) (n - 1);
      hues[i] = lerpColor(START_COLOR, END_COLOR, t);
    }

    // Fit each node's box to the block it sits on (slab = half-height frame, path block = 15/16,
    // stairs =
    // the step shape) by reading the live VoxelShape - Java owns block-shape classification.
    // Waypoint y
    // already points at the floor block (getKeyNodesArray applies yOffset=-1 to the native feet-Y
    // output),
    // so this is the block the player stands on. Precomputed once and reused by both render passes.
    final List<List<AABB>> boxesPerNode = new ArrayList<>(n);
    for (final Waypoint w : drawn) {
      boxesPerNode.add(blockBoxes(level, w.x(), w.y(), w.z()));
    }

    // Reveal head (continuous node index): LEAD_NODES ahead of the player's projected spot on the
    // route,
    // so nodes and line lay down just in front of the player and flow at their speed. Left
    // unclamped so the
    // final node still fully reveals once the player reaches the end; the draws below bound
    // themselves to n.
    // No player -> reveal everything (head past the last node).
    final double playerPos = playerNodePos(mc, drawn);
    final double head = playerPos < 0.0 ? (n + REVEAL_BAND) : playerPos + LEAD_NODES;

    // Each node's boxes exactly as they'll be drawn (reveal-grown, inflated off the block face).
    // One
    // source of truth for the fill, the outline, and the link clipping below.
    final List<List<AABB>> drawnBoxes = new ArrayList<>(n);
    for (int k = 0; k < n; k++) {
      final double reveal = nodeReveal(head, k);
      final List<AABB> grown = new ArrayList<>();
      if (reveal > 0.0) {
        final double scale = REVEAL_MIN_SCALE + (1.0 - REVEAL_MIN_SCALE) * reveal;
        for (final AABB b : boxesPerNode.get(k)) {
          grown.add(
              new AABB(
                  b.minX - INFLATE,
                  b.minY - INFLATE,
                  b.minZ - INFLATE,
                  b.maxX + INFLATE,
                  b.minY + (b.maxY - b.minY) * scale + INFLATE,
                  b.maxZ + INFLATE));
        }
      }
      drawnBoxes.add(grown);
    }

    // PASS 1 - translucent gradient fill, no outline. QUADS mode (position+color), drawn
    // see-through so
    // the fill shows behind terrain. The colour gradients cyan->violet AND the alpha fades
    // bottom->top,
    // so each node reads as a dense blue base dissolving upward. Each node's reveal factor scales
    // its alpha
    // and rises it from the base as the growing line reaches it.
    final int fillBottom = (START_COLOR & 0x00FFFFFF) | (FILL_ALPHA_BOTTOM << 24);
    final int fillTop = (END_COLOR & 0x00FFFFFF) | (FILL_ALPHA_TOP << 24);
    collector.submitCustomGeometry(
        pose,
        seeThroughQuads(),
        (last, quads) -> {
          for (int k = 0; k < n; k++) {
            final double reveal = nodeReveal(head, k);
            if (reveal <= 0.0) {
              continue;
            }
            final int alphaBottom = scaleAlpha(fillBottom, reveal);
            final int alphaTop = scaleAlpha(fillTop, reveal);
            for (final AABB b : drawnBoxes.get(k)) {
              addGradientBox(
                  quads,
                  last,
                  b.minX - cam.x,
                  b.minY - cam.y,
                  b.minZ - cam.z,
                  b.maxX - cam.x,
                  b.maxY - cam.y,
                  b.maxZ - cam.z,
                  alphaBottom,
                  alphaTop);
            }
          }
        });

    // PASS 2 - line work, its own submit after the fill (one buffer at a time, per the pipeline
    // rule);
    // we never go back. A vertical-gradient outline hugging each node's block shape (same
    // reveal), then the gradient polyline linking the nodes - drawn only up to the reveal head, so
    // the line
    // grows forward and each node places once the line has reached it. The link runs from the
    // TOP-CENTRE of
    // each block (slab = +0.5, full block = +1.0) so it rises off slabs and full blocks alike, and
    // is cut
    // where it passes inside a node box so it reads as entering and leaving rather than crossing
    // the glass.
    collector.submitCustomGeometry(
        pose,
        seeThroughLines(),
        (last, lines) -> {
          for (int k = 0; k < n; k++) {
            final double reveal = nodeReveal(head, k);
            if (reveal <= 0.0) {
              continue;
            }
            final int colorBottom = scaleAlpha(START_COLOR, reveal);
            final int colorTop = scaleAlpha(END_COLOR, reveal);
            for (final AABB b : drawnBoxes.get(k)) {
              addGradientBoxOutline(
                  lines,
                  last,
                  cam,
                  b.minX,
                  b.minY,
                  b.minZ,
                  b.maxX,
                  b.maxY,
                  b.maxZ,
                  colorBottom,
                  colorTop,
                  OUTLINE_WIDTH);
            }
          }
          for (int i = 0; i + 1 < n; i++) {
            final double segFrac =
                Math.min(1.0, head - i); // how much of segment i the growing line covers
            if (segFrac <= 0.0) {
              break; // head hasn't reached this segment yet; all later segments are further still
            }
            final Waypoint a = drawn.get(i);
            final Waypoint b = drawn.get(i + 1);
            final double ax = a.centerX();
            final double ay = blockTop(boxesPerNode.get(i), a.y());
            final double az = a.centerZ();
            final double bx = b.centerX();
            final double by = blockTop(boxesPerNode.get(i + 1), b.y());
            final double bz = b.centerZ();
            final double ex = ax + (bx - ax) * segFrac;
            final double ey = ay + (by - ay) * segFrac;
            final double ez = az + (bz - az) * segFrac;
            linkSegment(
                lines,
                last,
                cam,
                ax,
                ay,
                az,
                ex,
                ey,
                ez,
                hues[i],
                lerpColor(hues[i], hues[i + 1], segFrac),
                drawnBoxes);
          }
        });
  }

  /**
   * One link between two nodes, with the stretches that fall inside a drawn node box left out - the
   * line enters a face and leaves the far one instead of being painted across the glass (both the
   * boxes and the line render depth-test-free, so nothing else hides it). Colours are sampled from
   * the link's own gradient at each cut, so the surviving pieces still read as one continuous fade.
   */
  private static void linkSegment(
      VertexConsumer lines,
      PoseStack.Pose pose,
      Vec3 cam,
      double ax,
      double ay,
      double az,
      double bx,
      double by,
      double bz,
      int argbA,
      int argbB,
      List<List<AABB>> drawnBoxes) {
    final double dx = bx - ax;
    final double dy = by - ay;
    final double dz = bz - az;
    // ponytail: linear scan over every drawn box per segment. The drawn set is a handful of culled
    // turn
    // points, so it never gets big enough to want a spatial index.
    final List<double[]> hidden = new ArrayList<>();
    for (final List<AABB> boxes : drawnBoxes) {
      for (final AABB box : boxes) {
        final double[] span = insideSpan(box, ax, ay, az, dx, dy, dz);
        if (span != null) {
          hidden.add(span);
        }
      }
    }
    hidden.sort((one, two) -> Double.compare(one[0], two[0]));
    double from = 0.0;
    for (final double[] span : hidden) {
      if (span[0] > from) {
        linkPiece(lines, pose, cam, ax, ay, az, dx, dy, dz, argbA, argbB, from, span[0]);
      }
      from = Math.max(from, span[1]);
      if (from >= 1.0) {
        return;
      }
    }
    linkPiece(lines, pose, cam, ax, ay, az, dx, dy, dz, argbA, argbB, from, 1.0);
  }

  /**
   * One visible slice [t0, t1] of a link, its endpoint colours sampled from the link's gradient.
   */
  private static void linkPiece(
      VertexConsumer lines,
      PoseStack.Pose pose,
      Vec3 cam,
      double ax,
      double ay,
      double az,
      double dx,
      double dy,
      double dz,
      int argbA,
      int argbB,
      double t0,
      double t1) {
    edgeGradient(
        lines,
        pose,
        cam,
        ax + dx * t0,
        ay + dy * t0,
        az + dz * t0,
        ax + dx * t1,
        ay + dy * t1,
        az + dz * t1,
        lerpColor(argbA, argbB, t0),
        lerpColor(argbA, argbB, t1),
        LINK_WIDTH);
  }

  /**
   * Slab clip: the span of {@code origin + t * dir}, t in [0, 1], that lies inside {@code box}, or
   * null when the segment misses it entirely.
   */
  static double[] insideSpan(
      AABB box, double ox, double oy, double oz, double dx, double dy, double dz) {
    final double[] origin = {ox, oy, oz};
    final double[] dir = {dx, dy, dz};
    final double[] min = {box.minX, box.minY, box.minZ};
    final double[] max = {box.maxX, box.maxY, box.maxZ};
    double t0 = 0.0;
    double t1 = 1.0;
    for (int axis = 0; axis < 3; axis++) {
      if (Math.abs(dir[axis]) < 1.0e-9) {
        if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
          return null; // parallel to this slab and outside it
        }
        continue;
      }
      final double a = (min[axis] - origin[axis]) / dir[axis];
      final double b = (max[axis] - origin[axis]) / dir[axis];
      t0 = Math.max(t0, Math.min(a, b));
      t1 = Math.min(t1, Math.max(a, b));
      if (t0 >= t1) {
        return null;
      }
    }
    return new double[] {t0, t1};
  }

  /**
   * The player's continuous position along the drawn polyline as a node index (segment index +
   * fraction), from the frame-interpolated player position projected (in the horizontal plane) onto
   * the nearest segment. Returns -1 when there is no player or too few nodes to have a segment.
   */
  private static double playerNodePos(Minecraft mc, List<Waypoint> drawn) {
    final LocalPlayer player = mc.player;
    final int n = drawn.size();
    if (player == null || n < 2) {
      return -1.0;
    }
    final float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
    final double px = Mth.lerp(partial, player.xOld, player.getX());
    final double pz = Mth.lerp(partial, player.zOld, player.getZ());
    double bestDistSq = Double.MAX_VALUE;
    double bestPos = 0.0;
    for (int i = 0; i + 1 < n; i++) {
      final double ax = drawn.get(i).centerX();
      final double az = drawn.get(i).centerZ();
      final double dx = drawn.get(i + 1).centerX() - ax;
      final double dz = drawn.get(i + 1).centerZ() - az;
      final double lenSq = dx * dx + dz * dz;
      final double t =
          lenSq < 1.0e-9 ? 0.0 : Mth.clamp(((px - ax) * dx + (pz - az) * dz) / lenSq, 0.0, 1.0);
      final double ox = px - (ax + dx * t);
      final double oz = pz - (az + dz * t);
      final double distSq = ox * ox + oz * oz;
      if (distSq < bestDistSq) {
        bestDistSq = distSq;
        bestPos = i + t;
      }
    }
    return bestPos;
  }

  /**
   * Reveal factor [0,1] for node {@code k}: 0 before the head reaches it, ramping to 1 over
   * REVEAL_BAND.
   */
  private static double nodeReveal(double head, int k) {
    return Mth.clamp((head - k) / REVEAL_BAND, 0.0, 1.0);
  }

  /** Multiply an ARGB colour's alpha by {@code factor} (clamped), keeping RGB. */
  private static int scaleAlpha(int argb, double factor) {
    final int alpha = (int) Math.round(((argb >>> 24) & 0xFF) * Mth.clamp(factor, 0.0, 1.0));
    return (alpha << 24) | (argb & 0x00FFFFFF);
  }

  /**
   * Lazily-built see-through LINES render type (vanilla LINES snippet with the depth test forced to
   * pass).
   */
  private static RenderType seeThroughLines() {
    return SeeThroughLines.type();
  }

  private static RenderType seeThroughQuads() {
    return SeeThroughQuads.type();
  }

  /**
   * Top-face world Y of a node's block (max maxY over its collision boxes); {@code floorY + 1.0} if
   * empty.
   */
  private static double blockTop(List<AABB> boxes, int floorY) {
    if (boxes.isEmpty()) {
      return floorY + 1.0;
    }
    double top = Double.NEGATIVE_INFINITY;
    for (final AABB b : boxes) {
      if (b.maxY > top) {
        top = b.maxY;
      }
    }
    return top;
  }

  /** Interpolate two ARGB colours (per channel) at {@code t} in [0, 1]. */
  private static int lerpColor(int from, int to, double t) {
    final double u = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
    final int fa = (from >>> 24) & 0xFF;
    final int fr = (from >> 16) & 0xFF;
    final int fg = (from >> 8) & 0xFF;
    final int fb = from & 0xFF;
    final int ta = (to >>> 24) & 0xFF;
    final int tr = (to >> 16) & 0xFF;
    final int tg = (to >> 8) & 0xFF;
    final int tb = to & 0xFF;
    final int oa = (int) Math.round(fa + (ta - fa) * u);
    final int or = (int) Math.round(fr + (tr - fr) * u);
    final int og = (int) Math.round(fg + (tg - fg) * u);
    final int ob = (int) Math.round(fb + (tb - fb) * u);
    return (oa << 24) | (or << 16) | (og << 8) | ob;
  }

  /**
   * Drop interior nodes that lie on the straight line between their neighbours (within
   * COLLINEAR_EPS), so a long straight run renders as just its two endpoints. Endpoints are always
   * kept. Purely visual - the executor still walks the full path. The anchor is the last KEPT node,
   * so a real corner is preserved even when the nodes leading into it were straight.
   */
  private static List<Waypoint> cullCollinear(List<Waypoint> nodes) {
    final int size = nodes.size();
    if (size <= 2) {
      return nodes;
    }
    final List<Waypoint> out = new ArrayList<>(size);
    out.add(nodes.get(0));
    for (int i = 1; i < size - 1; i++) {
      final Waypoint prev = out.get(out.size() - 1);
      final Waypoint cur = nodes.get(i);
      final Waypoint next = nodes.get(i + 1);
      if (perpDistance(cur, prev, next) > COLLINEAR_EPS) {
        out.add(cur);
      }
    }
    out.add(nodes.get(size - 1));
    return out;
  }

  /**
   * Perpendicular distance (blocks) from node {@code p}'s center to the infinite line through a and
   * b.
   */
  private static double perpDistance(Waypoint p, Waypoint a, Waypoint b) {
    final double ax = a.centerX();
    final double ay = a.centerY();
    final double az = a.centerZ();
    final double dx = b.centerX() - ax;
    final double dy = b.centerY() - ay;
    final double dz = b.centerZ() - az;
    final double px = p.centerX() - ax;
    final double py = p.centerY() - ay;
    final double pz = p.centerZ() - az;
    final double lenSq = dx * dx + dy * dy + dz * dz;
    if (lenSq < 1.0e-9) {
      return Math.sqrt(px * px + py * py + pz * pz);
    }
    final double t = (px * dx + py * dy + pz * dz) / lenSq;
    final double ox = px - dx * t;
    final double oy = py - dy * t;
    final double oz = pz - dz * t;
    return Math.sqrt(ox * ox + oy * oy + oz * oz);
  }

  /**
   * World-space boxes that make up the block at (x, y, z) - one for a slab/full block, several for
   * stairs - so the overlay can hug the real shape instead of a floating unit cube. Falls back to a
   * unit cube when the block has no collision shape (or the level is unavailable). {@code
   * getCollisionShape} is the same vanilla classification the pathfinder already trusts; we never
   * reimplement {@link VoxelShape}.
   */
  private static List<AABB> blockBoxes(ClientLevel level, int x, int y, int z) {
    if (level != null) {
      final BlockPos pos = new BlockPos(x, y, z);
      final VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
      if (!shape.isEmpty()) {
        final List<AABB> local = shape.toAabbs();
        final List<AABB> world = new ArrayList<>(local.size());
        for (final AABB b : local) {
          world.add(b.move(x, y, z));
        }
        return world;
      }
    }
    return List.of(new AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0));
  }

  /**
   * Filled box with a vertical colour gradient - {@code colorBottom} at y0, {@code colorTop} at y1.
   * The four side faces blend top-to-bottom; the horizontal top/bottom faces are flat.
   */
  private static void addGradientBox(
      VertexConsumer vc,
      PoseStack.Pose pose,
      double x0,
      double y0,
      double z0,
      double x1,
      double y1,
      double z1,
      int colorBottom,
      int colorTop) {
    final float ax = (float) x0;
    final float ay = (float) y0;
    final float az = (float) z0;
    final float bx = (float) x1;
    final float by = (float) y1;
    final float bz = (float) z1;
    quad(
        vc,
        pose,
        ax,
        ay,
        az,
        bx,
        ay,
        az,
        bx,
        by,
        az,
        ax,
        by,
        az,
        colorBottom,
        colorBottom,
        colorTop,
        colorTop); // z = z0
    quad(
        vc,
        pose,
        ax,
        ay,
        bz,
        ax,
        by,
        bz,
        bx,
        by,
        bz,
        bx,
        ay,
        bz,
        colorBottom,
        colorTop,
        colorTop,
        colorBottom); // z = z1
    quad(
        vc,
        pose,
        ax,
        ay,
        az,
        ax,
        by,
        az,
        ax,
        by,
        bz,
        ax,
        ay,
        bz,
        colorBottom,
        colorTop,
        colorTop,
        colorBottom); // x = x0
    quad(
        vc,
        pose,
        bx,
        ay,
        az,
        bx,
        ay,
        bz,
        bx,
        by,
        bz,
        bx,
        by,
        az,
        colorBottom,
        colorBottom,
        colorTop,
        colorTop); // x = x1
    quad(
        vc,
        pose,
        ax,
        ay,
        az,
        ax,
        ay,
        bz,
        bx,
        ay,
        bz,
        bx,
        ay,
        az,
        colorBottom,
        colorBottom,
        colorBottom,
        colorBottom); // y = y0
    quad(
        vc, pose, ax, by, az, bx, by, az, bx, by, bz, ax, by, bz, colorTop, colorTop, colorTop,
        colorTop); // y = y1
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
      int c1,
      int c2,
      int c3,
      int c4) {
    vc.addVertex(pose, x1, y1, z1).setColor(c1);
    vc.addVertex(pose, x2, y2, z2).setColor(c2);
    vc.addVertex(pose, x3, y3, z3).setColor(c3);
    vc.addVertex(pose, x4, y4, z4).setColor(c4);
  }

  /**
   * The 12 edges of a world-space box, drawn with a vertical colour gradient (bottom rectangle
   * {@code colorBottom}, top rectangle {@code colorTop}, verticals blending between).
   */
  private static void addGradientBoxOutline(
      VertexConsumer lines,
      PoseStack.Pose pose,
      Vec3 cam,
      double x0,
      double y0,
      double z0,
      double x1,
      double y1,
      double z1,
      int colorBottom,
      int colorTop,
      float width) {
    // bottom rectangle
    edge(lines, pose, cam, x0, y0, z0, x1, y0, z0, colorBottom, width);
    edge(lines, pose, cam, x1, y0, z0, x1, y0, z1, colorBottom, width);
    edge(lines, pose, cam, x1, y0, z1, x0, y0, z1, colorBottom, width);
    edge(lines, pose, cam, x0, y0, z1, x0, y0, z0, colorBottom, width);
    // top rectangle
    edge(lines, pose, cam, x0, y1, z0, x1, y1, z0, colorTop, width);
    edge(lines, pose, cam, x1, y1, z0, x1, y1, z1, colorTop, width);
    edge(lines, pose, cam, x1, y1, z1, x0, y1, z1, colorTop, width);
    edge(lines, pose, cam, x0, y1, z1, x0, y1, z0, colorTop, width);
    // vertical edges (bottom -> top gradient)
    edgeGradient(lines, pose, cam, x0, y0, z0, x0, y1, z0, colorBottom, colorTop, width);
    edgeGradient(lines, pose, cam, x1, y0, z0, x1, y1, z0, colorBottom, colorTop, width);
    edgeGradient(lines, pose, cam, x1, y0, z1, x1, y1, z1, colorBottom, colorTop, width);
    edgeGradient(lines, pose, cam, x0, y0, z1, x0, y1, z1, colorBottom, colorTop, width);
  }

  /** One camera-relative line segment between two world points (single colour). */
  private static void edge(
      VertexConsumer lines,
      PoseStack.Pose pose,
      Vec3 cam,
      double ax,
      double ay,
      double az,
      double bx,
      double by,
      double bz,
      int argb,
      float width) {
    edgeGradient(lines, pose, cam, ax, ay, az, bx, by, bz, argb, argb, width);
  }

  /**
   * One camera-relative line segment whose endpoints carry different colours (the vertex shader
   * blends them along the segment), used for the gradient link between nodes.
   */
  private static void edgeGradient(
      VertexConsumer lines,
      PoseStack.Pose pose,
      Vec3 cam,
      double ax,
      double ay,
      double az,
      double bx,
      double by,
      double bz,
      int argbA,
      int argbB,
      float width) {
    final double dx = bx - ax;
    final double dy = by - ay;
    final double dz = bz - az;
    final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (len < 1.0e-6) {
      return;
    }
    final float nx = (float) (dx / len);
    final float ny = (float) (dy / len);
    final float nz = (float) (dz / len);
    lines
        .addVertex(pose, (float) (ax - cam.x), (float) (ay - cam.y), (float) (az - cam.z))
        .setColor(argbA)
        .setLineWidth(width)
        .setNormal(pose, nx, ny, nz);
    lines
        .addVertex(pose, (float) (bx - cam.x), (float) (by - cam.y), (float) (bz - cam.z))
        .setColor(argbB)
        .setLineWidth(width)
        .setNormal(pose, nx, ny, nz);
  }

  /** The execution trail preserves the executor's waypoints and foot heights. */
  private static void drawExecutionTrail(
      Minecraft mc,
      Vec3 cam,
      PoseStack pose,
      SubmitNodeCollector collector,
      PathExecutor executor) {
    List<Vec3> route = executor.getRouteFootPoints();
    int cursor = Math.clamp(executor.getWaypointIndex(), 0, route.size() - 1);
    boolean fullRoute = RyznSettings.get().pathfinder().isPathDrawInstant();

    // Measure reveal distance from the current trail segment. The active cursor keeps crossings
    // and stacked paths from jumping to an unrelated segment.
    double distance = 0;
    if (mc.player != null && cursor > 0) {
      Vec3 start = route.get(cursor - 1);
      Vec3 segment = route.get(cursor).subtract(start);
      double lengthSquared = segment.lengthSqr();
      if (lengthSquared > 1.0e-9) {
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 feet = mc.player.getPosition(partial);
        double fraction = Mth.clamp(feet.subtract(start).dot(segment) / lengthSquared, 0, 1);
        distance = (1 - fraction) * Math.sqrt(lengthSquared);
      }
    }
    double distanceToCursor = distance;
    collector.submitCustomGeometry(
        pose,
        seeThroughLines(),
        (last, lines) -> {
          double ahead = distanceToCursor;
          for (int i = 0; i + 1 < route.size(); i++) {
            Vec3 a = route.get(i);
            Vec3 b = route.get(i + 1);
            double length = a.distanceTo(b);
            double from = i < cursor - 1 ? 0 : i == cursor - 1 ? ahead - length : ahead;
            double to = i < cursor - 1 ? 0 : i == cursor - 1 ? ahead : ahead + length;
            if (!fullRoute && from >= LEAD_DISTANCE) break;
            if (!fullRoute && to > LEAD_DISTANCE) {
              b = a.lerp(b, (LEAD_DISTANCE - from) / (to - from));
              to = LEAD_DISTANCE;
            }
            int start =
                scaleAlpha(
                    hueAt(i, route.size()), fullRoute ? 1 : (LEAD_DISTANCE - from) / FADE_DISTANCE);
            int end =
                scaleAlpha(
                    hueAt(i + 1, route.size()),
                    fullRoute ? 1 : (LEAD_DISTANCE - to) / FADE_DISTANCE);
            edgeGradient(
                lines,
                last,
                cam,
                a.x,
                a.y + INFLATE,
                a.z,
                b.x,
                b.y + INFLATE,
                b.z,
                scaleAlpha(start, 0.24),
                scaleAlpha(end, 0.24),
                ROUTE_GLOW_WIDTH);
            edgeGradient(
                lines,
                last,
                cam,
                a.x,
                a.y + INFLATE,
                a.z,
                b.x,
                b.y + INFLATE,
                b.z,
                start,
                end,
                LINK_WIDTH);
            if (i >= cursor) ahead += length;
          }
        });
  }

  private static void drawCurve(
      Minecraft mc,
      ClientLevel level,
      Vec3 cam,
      PoseStack pose,
      SubmitNodeCollector collector,
      List<Waypoint> drawn) {
    if (drawn.size() < 2) {
      return; // a single point has no curve to draw
    }
    // Control points sit on the TOP face of the block each turn point stands on (slab = +0.5, full
    // block
    // = +1.0), so the curve rides the walking surface instead of cutting through it.
    final List<Vec3> controls = new ArrayList<>(drawn.size());
    for (final Waypoint w : drawn) {
      controls.add(
          new Vec3(
              w.centerX(), blockTop(blockBoxes(level, w.x(), w.y(), w.z()), w.y()), w.centerZ()));
    }
    final List<Vec3> curve = VisualPathSpline.sample(controls, SAMPLE_STEP, 0);
    final int n = curve.size();
    if (n < 2) {
      return;
    }

    // Reveal head, as a continuous curve-sample index. Drawing the route whole and laying it down
    // ahead of
    // the player are the same code path: an unreachable head reveals everything at full alpha. That
    // is also
    // the no-player case, so the instant toggle just forces it.
    final double fadeSamples = FADE_DISTANCE / SAMPLE_STEP;
    final double playerAt =
        RyznSettings.get().pathfinder().isPathDrawInstant() ? -1.0 : playerCurvePos(mc, curve);
    final double head = playerAt < 0.0 ? n + fadeSamples : playerAt + LEAD_DISTANCE / SAMPLE_STEP;

    // One continuous strip. Each sample carries its own stop on the travelling accent gradient, so
    // the
    // colour flows along the route rather than stepping at the corners.
    collector.submitCustomGeometry(
        pose,
        seeThroughLines(),
        (last, lines) -> {
          for (int i = 0; i + 1 < n; i++) {
            final double revealB = sampleReveal(head, i + 1, fadeSamples);
            if (revealB <= 0.0) {
              break; // head hasn't reached here, and every later sample is further still
            }
            final Vec3 a = curve.get(i);
            final Vec3 b = curve.get(i + 1);
            edgeGradient(
                lines,
                last,
                cam,
                a.x,
                a.y,
                a.z,
                b.x,
                b.y,
                b.z,
                scaleAlpha(hueAt(i, n), sampleReveal(head, i, fadeSamples)),
                scaleAlpha(hueAt(i + 1, n), revealB),
                LINK_WIDTH);
          }

          // A bar across the curve at each turn point. Snapped to the nearest sample rather than
          // drawn at the
          // control point itself, because corner cutting moves the curve off its own controls.
          for (final Vec3 control : controls) {
            final int index = nearestSample(curve, control);
            final double reveal = sampleReveal(head, index, fadeSamples);
            if (reveal <= 0.0) {
              continue;
            }
            addTick(lines, last, cam, curve, index, scaleAlpha(hueAt(index, n), reveal));
          }
        });
  }

  /**
   * The player's continuous position along the curve as a sample index (segment index + fraction),
   * from the frame-interpolated player position projected (in the horizontal plane) onto the
   * nearest segment. Returns -1 when there is no player or too few samples to have a segment.
   */
  private static double playerCurvePos(Minecraft mc, List<Vec3> curve) {
    final LocalPlayer player = mc.player;
    final int n = curve.size();
    if (player == null || n < 2) {
      return -1.0;
    }
    final float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
    final double px = Mth.lerp(partial, player.xOld, player.getX());
    final double pz = Mth.lerp(partial, player.zOld, player.getZ());
    double bestDistSq = Double.MAX_VALUE;
    double bestPos = 0.0;
    for (int i = 0; i + 1 < n; i++) {
      final double ax = curve.get(i).x;
      final double az = curve.get(i).z;
      final double dx = curve.get(i + 1).x - ax;
      final double dz = curve.get(i + 1).z - az;
      final double lenSq = dx * dx + dz * dz;
      final double t =
          lenSq < 1.0e-9 ? 0.0 : Mth.clamp(((px - ax) * dx + (pz - az) * dz) / lenSq, 0.0, 1.0);
      final double ox = px - (ax + dx * t);
      final double oz = pz - (az + dz * t);
      final double distSq = ox * ox + oz * oz;
      if (distSq < bestDistSq) {
        bestDistSq = distSq;
        bestPos = i + t;
      }
    }
    return bestPos;
  }

  /** Index of the sample nearest the control point. */
  private static int nearestSample(List<Vec3> curve, Vec3 point) {
    int best = 0;
    double bestDistSq = Double.MAX_VALUE;
    for (int i = 0; i < curve.size(); i++) {
      final double distSq = curve.get(i).distanceToSqr(point);
      if (distSq < bestDistSq) {
        bestDistSq = distSq;
        best = i;
      }
    }
    return best;
  }

  /**
   * Reveal factor [0,1] for curve sample {@code i}: 0 before the head reaches it, ramping to 1 over
   * the fade.
   */
  private static double sampleReveal(double head, int i, double fadeSamples) {
    return Mth.clamp((head - i) / fadeSamples, 0.0, 1.0);
  }

  /** This sample's color along the route. */
  private static int hueAt(int i, int n) {
    return lerpColor(START_COLOR, END_COLOR, n <= 1 ? 0.0 : (double) i / (n - 1));
  }

  /**
   * A bar laid across the curve at {@code index}, perpendicular to the local heading in the
   * horizontal plane. The heading is taken from the samples either side so the bar squares up to
   * the curve through a bend rather than to one segment of it.
   */
  private static void addTick(
      VertexConsumer lines, PoseStack.Pose pose, Vec3 cam, List<Vec3> curve, int index, int argb) {
    final Vec3 before = curve.get(Math.max(0, index - 1));
    final Vec3 after = curve.get(Math.min(curve.size() - 1, index + 1));
    final double dx = after.x - before.x;
    final double dz = after.z - before.z;
    final double len = Math.sqrt(dx * dx + dz * dz);
    if (len < 1.0e-6) {
      return; // a vertical step has no horizontal heading to lay a bar across
    }
    final Vec3 at = curve.get(index);
    final double nx = -dz / len * TICK_HALF_WIDTH;
    final double nz = dx / len * TICK_HALF_WIDTH;
    edge(
        lines, pose, cam, at.x - nx, at.y, at.z - nz, at.x + nx, at.y, at.z + nz, argb, TICK_WIDTH);
  }
}
