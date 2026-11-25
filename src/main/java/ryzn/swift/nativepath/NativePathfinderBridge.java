package ryzn.swift.nativepath;

import com.chattriggers.ctjs.api.world.pathfinding.NativePathResult;

/**
 * Safe wrapper around {@link NativePathfinderJNI}. Catches throwables, surfaces them via {@link
 * #getLastError()}, and no-ops when the native lib failed to load.
 */
public final class NativePathfinderBridge {

  public static final class NativePathSearchRequest {
    public final int[] startPoints;
    public final int[] endPoints;
    public final int maxIterations;
    public final double heuristicWeight;
    public final int moveOrderOffset;
    public final int[] avoidMeta;
    public final double[] avoidPenalty;

    public NativePathSearchRequest(
        int[] startPoints,
        int[] endPoints,
        int maxIterations,
        double heuristicWeight,
        int moveOrderOffset,
        int[] avoidMeta,
        double[] avoidPenalty) {
      this.startPoints = startPoints;
      this.endPoints = endPoints;
      this.maxIterations = maxIterations;
      this.heuristicWeight = heuristicWeight;
      this.moveOrderOffset = moveOrderOffset;
      this.avoidMeta = avoidMeta;
      this.avoidPenalty = avoidPenalty;
    }
  }

  private static volatile String lastError = null;

  private NativePathfinderBridge() {}

  public static boolean isAvailable() {
    return NativePathfinderJNI.isAvailable();
  }

  public static String getLastError() {
    return lastError != null ? lastError : NativePathfinderJNI.getLoadError();
  }

  public static void setWorld(String worldKey, int minY, int maxY) {
    if (!isAvailable()) {
      final String err = NativePathfinderJNI.getLoadError();
      lastError = err != null ? err : "Pathfinder unavailable";
      return;
    }
    try {
      com.chattriggers.ctjs.api.world.pathfinding.NativePathfinderJNI.setWorld(
          worldKey, minY, maxY);
      lastError = null;
    } catch (Throwable t) {
      captureError(t);
    }
  }

  public static void clearWorld() {
    if (!isAvailable()) {
      return;
    }
    try {
      com.chattriggers.ctjs.api.world.pathfinding.NativePathfinderJNI.clearWorld();
      lastError = null;
    } catch (Throwable t) {
      captureError(t);
    }
  }

  public static void upsertChunk(
      int chunkX, int chunkZ, int minY, int maxY, long sectionMask, short[] sectionFlags) {
    if (!isAvailable()) {
      return;
    }
    try {
      com.chattriggers.ctjs.api.world.pathfinding.NativePathfinderJNI.upsertChunk(
          chunkX, chunkZ, minY, maxY, sectionMask, sectionFlags);
      lastError = null;
    } catch (Throwable t) {
      captureError(t);
    }
  }

  public static void applyBlockUpdates(int[] updates) {
    if (!isAvailable() || updates.length == 0) {
      return;
    }
    try {
      com.chattriggers.ctjs.api.world.pathfinding.NativePathfinderJNI.applyBlockUpdates(updates);
      lastError = null;
    } catch (Throwable t) {
      captureError(t);
    }
  }

  public static NativePathResult findPath(NativePathSearchRequest request) {
    if (!isAvailable()) {
      final String err = NativePathfinderJNI.getLoadError();
      lastError = err != null ? err : "Pathfinder unavailable";
      return null;
    }
    try {
      final NativePathResult result =
          com.chattriggers.ctjs.api.world.pathfinding.NativePathfinderJNI.findPath(
              request.startPoints,
              request.endPoints,
              false,
              request.maxIterations,
              request.heuristicWeight,
              250.0,
              request.moveOrderOffset,
              request.avoidMeta,
              request.avoidPenalty);
      lastError = result == null ? "Pathfinder found no path" : null;
      return result;
    } catch (Throwable t) {
      captureError(t);
      return null;
    }
  }

  public static void cancelSearch() {
    if (!isAvailable()) {
      return;
    }
    try {
      com.chattriggers.ctjs.api.world.pathfinding.NativePathfinderJNI.cancelSearch();
    } catch (Throwable ignored) {
      // Cancellation is best effort and must not replace the active search's error state.
    }
  }

  private static void captureError(Throwable t) {
    lastError = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
  }
}
