package ryzn.macro.client.feature.pathfinder.path;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.groups.WalkingSettings.Control;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.rotation.impl.Angle;
import ryzn.macro.client.feature.rotation.impl.AngleUtil;

/**
 * Chooses look targets on the planned route. Ordinary rotations aim at the next drawn node;
 * recorded swipes look ahead along the trail and pitch with its grade.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class PathGazePlanner {

  /** Offset off the neighbour line under which a mark is straight, matching the path renderer. */
  private static final double COLLINEAR_EPS = 0.1;

  /** Height over the node's standing surface the crosshair aims at; just under eye height. */
  private static final double AIM_ABOVE_NODE = 1.5;

  /** About eight travel ticks at the recorded ratio of displacement to post-drag velocity. */
  private static final double LOOK_AHEAD_TICKS = 14.0;

  private static final double FAST_LOOK_AHEAD_TICKS = 18.0;
  private static final double RECORDED_FORWARD_SPEED = 0.61;
  private static final double FAST_FORWARD_SPEED = 0.70;

  private static final double MIN_LOOK_SPEED = 0.2;
  private static final double MIN_LOOK_AHEAD_BLOCKS = 2.5;
  private static final double MAX_LOOK_AHEAD_BLOCKS = 10.0;

  private static final float WALK_PITCH_BIAS = 4.85f;
  private static final float WALK_PITCH_GRADE_GAIN = 0.63f;
  private static final float WALK_PITCH_BLEND = 0.2f;

  /** Pitch past which the controller's wide clamp band is asked for. */
  private static final float VERTICAL_OPEN_DEG = 8f;

  /**
   * Walk trail points carry the SUPPORT block, one below the body standing on them - see {@code
   * PathExecutor.trailFootY}.
   */
  private static final double TRAIL_FOOT_OFFSET = 1.0;

  private final Minecraft client;
  private final PathSpatialQueries spatialQueries;
  private final GazeStringPull stringPull = new GazeStringPull();

  @Getter(AccessLevel.PACKAGE)
  private boolean verticalGazeOpen;

  /** Node the string pull settled on last tick, or -1 when some other branch owned the head. */
  private int gazeIndex = -1;

  private int traceCounter;

  /** Smoothed horizontal speed for the look-ahead distance, so a bump does not jerk the point. */
  private double lookSpeed;

  private float walkPitch;
  private boolean walkPitchReady;
  private boolean recordedCombat;

  void setRecordedCombat(boolean enabled) {
    recordedCombat = enabled;
  }

  /** Angle aimed last tick, held while the body stands on its gaze node. */
  private Angle lastAim;

  private double hopEyeY = Double.NaN;

  /** {@link #keyMarks} cache, keyed on the executor's mark list instance and size. */
  private List<Integer> keyMarksSource;

  private int keyMarksSize;
  private List<Integer> keyMarks = List.of();

  /**
   * Let the string out and record where it went taut. Called EVERY tick, including while a macro
   * owns the head: macros gate handing the head back on {@link #gazeNodeIndex()}, so it cannot be
   * allowed to go stale for the length of a hold.
   */
  void track(Context context) {
    if (client.player == null) {
      gazeIndex = -1;
      return;
    }
    gazeIndex =
        stringPull.updateNodes(
            context.trail().size(),
            context.cursor(),
            keyMarks(context),
            client.player.getY(),
            client.player.onGround(),
            client.player.getDeltaMovement().horizontalDistance(),
            new TrailTerrain(context));
  }

  Angle angleFor(Context context, Vec3 gazeTarget, boolean preciseLook) {
    if (client.player == null) {
      return AngleUtil.getPlayerAngle();
    }
    boolean steadyHopPitch =
        AutomaticHop.enabled(client)
            && !client.player.isInWater()
            && !client.player.isInLava()
            && !preciseLook;
    if (!steadyHopPitch) {
      hopEyeY = Double.NaN;
    } else if (client.player.onGround() || Double.isNaN(hopEyeY)) {
      hopEyeY = client.player.getEyeY();
    }
    if (preciseLook) {
      if (gazeTarget == null) {
        return AngleUtil.getPlayerAngle();
      }
      return AngleUtil.getRotation(new Vec3(gazeTarget.x, gazeTarget.y + 0.5, gazeTarget.z));
    }

    List<Vec3> trail = context.trail();
    int index = gazeIndex;
    if (index < 0 || index >= trail.size()) {
      return AngleUtil.getPlayerAngle();
    }

    Vec3 node = trail.get(index);
    double footY = context.footY().applyAsDouble(index);
    float recordedPitch = Float.NaN;
    final LookAhead ahead = pointAhead(context);
    if (ahead != null) {
      node = ahead.point();
      footY = node.y;
      recordedPitch = recordedCombat ? (float) ahead.grade() : gradePitch(ahead.grade());
    }
    double flatGap = flatDistance(client.player.getX(), client.player.getZ(), node.x, node.z);
    // Only the last node is still looked at this close; standing on it the bearing is noise.
    if (flatGap < GazeStringPull.NODE_HANDOFF_BLOCKS) {
      return lastAim != null ? lastAim : AngleUtil.getPlayerAngle();
    }

    double aimHeight =
        recordedCombat ? client.player.getEyeY() - client.player.getY() : AIM_ABOVE_NODE;
    Vec3 aimPoint = new Vec3(node.x, footY + aimHeight, node.z);
    Angle onNode = AngleUtil.getRotation(aimPoint);
    if (steadyHopPitch) {
      // Route pitch uses takeoff eye height, so the body's jump arc cannot make the head nod.
      float stablePitch = (float) -Math.toDegrees(Math.atan2(aimPoint.y - hopEyeY, flatGap));
      onNode = new Angle(onNode.getYaw(), stablePitch);
    }
    float yaw = AngleUtil.normalizeAngle(onNode.getYaw());
    float pitch = Float.isNaN(recordedPitch) ? onNode.getPitch() : recordedPitch;
    verticalGazeOpen = Math.abs(pitch) > VERTICAL_OPEN_DEG;

    if (++traceCounter % 5 == 0) {
      DebugLog.detail(
          DebugLog.Category.NAV,
          "[GAZE] cur=%d node=%d aim=(%.2f,%.2f,%.2f) eyeY=%.2f gap=%.2f target=(%.1f,%.1f)"
              + " head=(%.1f,%.1f)",
          context.cursor(),
          index,
          aimPoint.x,
          aimPoint.y,
          aimPoint.z,
          client.player.getEyeY(),
          flatGap,
          yaw,
          pitch,
          AngleUtil.normalizeAngle(client.player.getYRot()),
          client.player.getXRot());
    }
    lastAim = new Angle(yaw, pitch);
    return lastAim;
  }

  /**
   * Looks ahead along the planned trail at measured speed. Short step-downs measure grade from the
   * body's height; other movement keeps the trail-floor reference.
   */
  private LookAhead pointAhead(Context context) {
    final List<Vec3> trail = context.trail();
    final int cursor = context.cursor();
    if (cursor <= 0 || cursor >= trail.size() || gazeIndex < cursor) {
      return null;
    }
    double measuredSpeed = client.player.getDeltaMovement().horizontalDistance();
    lookSpeed =
        recordedCombat && lookSpeed == 0.0
            ? measuredSpeed
            : lookSpeed + (measuredSpeed - lookSpeed) * 0.2;
    final double speed = Math.max(MIN_LOOK_SPEED, lookSpeed);
    final double fast =
        Math.clamp(
            (speed - RECORDED_FORWARD_SPEED) / (FAST_FORWARD_SPEED - RECORDED_FORWARD_SPEED),
            0.0,
            1.0);
    final double lead = LOOK_AHEAD_TICKS + (FAST_LOOK_AHEAD_TICKS - LOOK_AHEAD_TICKS) * fast;
    double left =
        Math.clamp(speed * lead, MIN_LOOK_AHEAD_BLOCKS, MAX_LOOK_AHEAD_BLOCKS)
            * RyznSettings.get().pathfinder().walking().get(Control.GAZE_LEAD)
            / 100;
    final double reach = left;
    final Vec3 from = trail.get(cursor - 1);
    final Vec3 to = trail.get(cursor);
    final double segX = to.x - from.x;
    final double segZ = to.z - from.z;
    final double segSq = segX * segX + segZ * segZ;
    double t =
        segSq < 1.0e-6
            ? 1.0
            : Math.clamp(
                ((client.player.getX() - from.x) * segX + (client.player.getZ() - from.z) * segZ)
                    / segSq,
                0.0,
                1.0);
    final double fromY = context.footY().applyAsDouble(cursor - 1);
    final double startY = fromY + (context.footY().applyAsDouble(cursor) - fromY) * t;
    final double gradeStartY = context.falling() ? client.player.getY() : startY;
    Vec3 at = new Vec3(from.x + segX * t, startY, from.z + segZ * t);
    for (int i = cursor; i <= gazeIndex && i < trail.size(); i++) {
      final Vec3 next = trail.get(i);
      if (!spatialQueries.gazeSightOpen(next)) {
        return null;
      }
      final double step = flatDistance(at.x, at.z, next.x, next.z);
      final double nextY = context.footY().applyAsDouble(i);
      if (step >= left) {
        final double f = left / step;
        final Vec3 point =
            new Vec3(
                at.x + (next.x - at.x) * f, at.y + (nextY - at.y) * f, at.z + (next.z - at.z) * f);
        return new LookAhead(point, -Math.toDegrees(Math.atan2(point.y - gradeStartY, reach)));
      }
      left -= step;
      at = new Vec3(next.x, nextY, next.z);
    }
    return new LookAhead(
        at, -Math.toDegrees(Math.atan2(at.y - gradeStartY, Math.max(0.5, reach - left))));
  }

  private float gradePitch(double grade) {
    final float target =
        WALK_PITCH_BIAS
            + WALK_PITCH_GRADE_GAIN * (float) grade
            + RyznSettings.get().pathfinder().walking().get(Control.PITCH_BIAS);
    walkPitch = walkPitchReady ? walkPitch + WALK_PITCH_BLEND * (target - walkPitch) : target;
    walkPitchReady = true;
    return walkPitch;
  }

  private record LookAhead(Vec3 point, double grade) {}

  /**
   * Route nodes the head steps between: the turn marks minus any lying on the straight line through
   * their neighbours, the same cull {@code PathRenderer} applies to the boxes it draws. Looking at
   * a collinear mark is looking at the line.
   */
  private List<Integer> keyMarks(Context context) {
    List<Integer> marks = context.nodeMarks();
    if (marks == keyMarksSource && marks.size() == keyMarksSize) {
      return keyMarks;
    }
    List<Vec3> trail = context.trail();
    List<Integer> kept = new ArrayList<>();
    for (int i = 0; i < marks.size(); i++) {
      int mark = marks.get(i);
      if (mark < 0 || mark >= trail.size()) {
        continue;
      }
      boolean interior = !kept.isEmpty() && i + 1 < marks.size() && marks.get(i + 1) < trail.size();
      if (!interior
          || offLine(trail.get(kept.getLast()), trail.get(mark), trail.get(marks.get(i + 1)))) {
        kept.add(mark);
      }
    }
    keyMarksSource = marks;
    keyMarksSize = marks.size();
    keyMarks = kept;
    return kept;
  }

  private static boolean offLine(Vec3 from, Vec3 point, Vec3 to) {
    Vec3 line = to.subtract(from);
    double lengthSq = line.lengthSqr();
    Vec3 offset = point.subtract(from);
    if (lengthSq < 1.0e-9) {
      return offset.length() > COLLINEAR_EPS;
    }
    Vec3 closest = line.scale(offset.dot(line) / lengthSq);
    return offset.subtract(closest).length() > COLLINEAR_EPS;
  }

  /** Node the head is aiming at, or -1 when the pull did not choose one this tick. */
  int gazeNodeIndex() {
    return gazeIndex;
  }

  void reset() {
    verticalGazeOpen = false;
    gazeIndex = -1;
    lastAim = null;
    hopEyeY = Double.NaN;
    lookSpeed = 0.0;
    walkPitchReady = false;
    keyMarksSource = null;
    stringPull.reset();
  }

  boolean isCornerNode(List<Integer> turnLaneMarks, int waypointIndex) {
    return turnLaneMarks.contains(waypointIndex);
  }

  boolean trailClimbsAhead(List<Vec3> trail, int cursor) {
    if (cursor >= trail.size() || client.player == null) {
      return false;
    }
    for (int index = cursor; index < trail.size() && index <= cursor + 5; index++) {
      Vec3 point = trail.get(index);
      if (flatDistance(client.player.getX(), client.player.getZ(), point.x, point.z) > 3.0) {
        break;
      }
      if (point.y + TRAIL_FOOT_OFFSET > client.player.getY() + 0.6) {
        return true;
      }
    }
    return false;
  }

  private static double flatDistance(double x1, double z1, double x2, double z2) {
    return Math.hypot(x1 - x2, z1 - z2);
  }

  /** Backs the string pull's per-node probes with the live world. */
  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  private final class TrailTerrain implements GazeStringPull.Terrain {
    private final Context context;

    @Override
    public double footY(int index) {
      return context.footY().applyAsDouble(index);
    }

    @Override
    public double flatGap(int index) {
      Vec3 node = context.trail().get(index);
      return flatDistance(client.player.getX(), client.player.getZ(), node.x, node.z);
    }

    @Override
    public boolean visible(int index) {
      return spatialQueries.gazeSightOpen(context.trail().get(index));
    }
  }

  /**
   * @param trail live walk trail
   * @param footY feet Y of a body standing on a trail node, by index
   * @param cursor node the body is walking at
   * @param airTicks ticks off the ground; non-zero latches the pull
   * @param bodyYaw heading the body is being steered on, which the deviation cap is measured
   *     against; {@code NaN} leaves the gaze uncapped
   * @param nodeMarks trail indices of the route's turn nodes, which the gaze steps between
   * @param falling descending a short step without a jump, so gaze includes the remaining drop
   */
  record Context(
      List<Vec3> trail,
      IntToDoubleFunction footY,
      int cursor,
      int airTicks,
      float bodyYaw,
      List<Integer> nodeMarks,
      boolean falling) {}
}
