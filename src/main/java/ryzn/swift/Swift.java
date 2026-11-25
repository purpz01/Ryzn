package ryzn.swift;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Constants + shared executor for the pathfinder. The Hypixel/Wynncraft integrations and the custom
 * PacketEvent abstraction are intentionally dropped - ryzn wires the chunk feeder directly to
 * Fabric's {@code ClientChunkEvents}.
 */
public final class Swift {

  public static final int CHUNKS_PER_TICK = 8;
  public static final int MAXIMUM_CACHED_CHUNKS = 4096;

  /** Eviction trims down to this, so it runs once per few hundred new chunks, not every tick. */
  public static final int EVICT_TO_CHUNKS = 3840;

  private static final int EXECUTOR_QUEUE_CAPACITY = 64;

  public static final ExecutorService executor;

  /**
   * Cache disk loads and saves, one at a time and in order, kept off {@link #executor} so a path
   * search never queues behind file IO.
   */
  public static final ExecutorService io =
      Executors.newSingleThreadExecutor(
          r -> {
            final Thread t = new Thread(r, "Swift-Cache-IO");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
          });

  /** Wait up to {@code millis} for queued cache IO to finish (client shutdown). */
  public static void awaitIo(long millis) {
    try {
      io.submit(() -> {}).get(millis, TimeUnit.MILLISECONDS);
    } catch (Exception ignored) {
      // A save still running past the deadline is abandoned; its temp file is cleaned next start.
    }
  }

  static {
    final AtomicInteger threadIdx = new AtomicInteger();
    final ThreadFactory threadFactory =
        r -> {
          final Thread t = new Thread(r, "Swift-Pathfinder-" + threadIdx.getAndIncrement());
          t.setDaemon(true);
          t.setPriority(Thread.NORM_PRIORITY - 1);
          return t;
        };
    final int maxThreads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    executor =
        new ThreadPoolExecutor(
            1,
            maxThreads,
            30L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(EXECUTOR_QUEUE_CAPACITY),
            threadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy());
  }

  private Swift() {}
}
