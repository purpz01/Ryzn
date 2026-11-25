package com.chattriggers.ctjs.api.world.pathfinding;

/** The result constructor and its argument order form part of the V5 JNI ABI. */
public record NativePathResult(
    int[] path,
    int[] keyPath,
    long timeMs,
    int nodesExplored,
    double nanosecondsPerNode,
    int selectedStartIndex,
    int[] pathFlags,
    int[] keyNodeFlags,
    int[] keyNodeMetrics,
    String pathSignature) {}
