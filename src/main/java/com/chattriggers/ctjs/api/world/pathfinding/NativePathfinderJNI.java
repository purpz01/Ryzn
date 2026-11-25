package com.chattriggers.ctjs.api.world.pathfinding;

/** JNI entry points matching the bundled V5 libraries. */
public final class NativePathfinderJNI {
  private NativePathfinderJNI() {}

  public static native boolean initNative();

  public static native void setWorld(String worldKey, int minY, int maxY);

  public static native void clearWorld();

  public static native void upsertChunk(
      int chunkX, int chunkZ, int minY, int maxY, long sectionMask, short[] sectionFlags);

  public static native void applyBlockUpdates(int[] updates);

  public static native NativePathResult findPath(
      int[] startPoints,
      int[] endPoints,
      boolean isFly,
      int maxIterations,
      double heuristicWeight,
      double nonPrimaryStartPenalty,
      int moveOrderOffset,
      int[] avoidMeta,
      double[] avoidPenalty);

  public static native void cancelSearch();
}
