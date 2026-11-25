package ryzn.swift.cache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.LongPredicate;

/** Coalesces packet notifications and gives temporarily missing chunks a bounded retry. */
final class PendingChunks {
  private static final int MAX_ATTEMPTS = 3;

  private final LinkedHashMap<Long, Integer> pending = new LinkedHashMap<>();
  private long generation;

  synchronized void add(long key) {
    pending.put(key, 0);
  }

  synchronized void clear() {
    pending.clear();
    generation++;
  }

  synchronized int size() {
    return pending.size();
  }

  int process(int budget, LongPredicate ingest) {
    final List<Attempt> batch = new ArrayList<>();
    final long batchGeneration;
    synchronized (this) {
      batchGeneration = generation;
      var iterator = pending.entrySet().iterator();
      while (batch.size() < budget && iterator.hasNext()) {
        var entry = iterator.next();
        batch.add(new Attempt(entry.getKey(), entry.getValue()));
        iterator.remove();
      }
    }

    for (Attempt attempt : batch) {
      if (!ingest.test(attempt.key()) && attempt.failures() + 1 < MAX_ATTEMPTS) {
        synchronized (this) {
          if (batchGeneration == generation) {
            // A fresh packet already queued during conversion gets its own retry budget.
            pending.putIfAbsent(attempt.key(), attempt.failures() + 1);
          }
        }
      }
    }
    return batch.size();
  }

  private record Attempt(long key, int failures) {}
}
