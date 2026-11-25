package ryzn.pathfinding;

import java.util.concurrent.FutureTask;

/** Coordinates one search slot shared by the client and the native-search worker. */
final class SearchState<T> {
  private volatile long generation;
  private volatile FutureTask<Void> task;
  private volatile Outcome<T> outcome = new Outcome<>(null, null);

  long generation() {
    return generation;
  }

  Outcome<T> outcome() {
    return outcome;
  }

  boolean isSearching() {
    return task != null;
  }

  synchronized void start(FutureTask<Void> task) {
    this.task = task;
  }

  synchronized FutureTask<Void> invalidate() {
    generation++;
    FutureTask<Void> previous = task;
    task = null;
    return previous;
  }

  synchronized void finish(long generation, T result, String error) {
    if (generation == this.generation) {
      outcome = new Outcome<>(result, error);
      task = null;
    }
  }

  record Outcome<T>(T result, String error) {}
}
