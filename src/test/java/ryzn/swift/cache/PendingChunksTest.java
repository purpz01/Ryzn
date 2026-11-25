package ryzn.swift.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PendingChunksTest {
  @Test
  void coalescesDuplicatesAndLeavesExcessWorkForTheNextTick() {
    var pending = new PendingChunks();
    var ingested = new ArrayList<Long>();
    pending.add(10);
    pending.add(10);
    pending.add(20);

    assertEquals(1, pending.process(1, ingested::add));
    assertEquals(1, pending.size());
    assertEquals(1, pending.process(1, ingested::add));
    assertEquals(List.of(10L, 20L), ingested);
  }

  @Test
  void missingChunksEventuallyLeaveTheQueue() {
    var pending = new PendingChunks();
    pending.add(10);

    assertEquals(1, pending.process(8, key -> false));
    assertEquals(1, pending.process(8, key -> false));
    assertEquals(1, pending.process(8, key -> false));
    assertEquals(0, pending.process(8, key -> false));
  }

  @Test
  void clearInvalidatesRetriesFromThePreviousWorld() {
    var pending = new PendingChunks();
    pending.add(10);
    pending.process(
        8,
        key -> {
          pending.clear();
          pending.add(20);
          return false;
        });

    var ingested = new ArrayList<Long>();
    pending.process(8, ingested::add);
    assertEquals(List.of(20L), ingested);
  }
}
