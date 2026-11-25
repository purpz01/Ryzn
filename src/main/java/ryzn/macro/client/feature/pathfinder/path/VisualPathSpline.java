package ryzn.macro.client.feature.pathfinder.path;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

public final class VisualPathSpline {

  private VisualPathSpline() {}

  public static List<Vec3> sample(List<Vec3> controls, double maxSegmentLength) {
    return sample(controls, maxSegmentLength, 1);
  }

  /**
   * As {@link #sample(List, double)} but with {@code passes} rounds of corner cutting. Each pass
   * halves the remaining angle at a control point, so two rounds take a square turn most of the way
   * to a curve. Endpoints are preserved by every pass.
   */
  public static List<Vec3> sample(List<Vec3> controls, double maxSegmentLength, int passes) {
    if (controls.isEmpty()) {
      return List.of();
    }
    if (controls.size() == 1) {
      return List.of(controls.getFirst());
    }
    if (!(maxSegmentLength > 0.0)) {
      throw new IllegalArgumentException("maxSegmentLength must be > 0");
    }
    if (passes < 0) {
      throw new IllegalArgumentException("passes must be >= 0");
    }

    List<Vec3> smoothed = List.copyOf(controls);
    for (int pass = 0; pass < passes && smoothed.size() >= 3; pass++) {
      smoothed = chaikin(smoothed);
    }
    List<Vec3> sampled = new ArrayList<>();
    sampled.add(smoothed.getFirst());
    for (int index = 1; index < smoothed.size(); index++) {
      Vec3 start = smoothed.get(index - 1);
      Vec3 end = smoothed.get(index);
      int segmentCount = Math.max(1, (int) Math.ceil(start.distanceTo(end) / maxSegmentLength));
      for (int segment = 1; segment <= segmentCount; segment++) {
        sampled.add(start.lerp(end, (double) segment / segmentCount));
      }
    }
    sampled.set(0, controls.getFirst());
    sampled.set(sampled.size() - 1, controls.getLast());
    return List.copyOf(sampled);
  }

  private static List<Vec3> chaikin(List<Vec3> controls) {
    List<Vec3> smoothed = new ArrayList<>(controls.size() * 2);
    smoothed.add(controls.getFirst());
    for (int index = 0; index + 1 < controls.size(); index++) {
      Vec3 start = controls.get(index);
      Vec3 end = controls.get(index + 1);
      smoothed.add(start.lerp(end, 0.25));
      smoothed.add(start.lerp(end, 0.75));
    }
    smoothed.add(controls.getLast());
    return smoothed;
  }
}
