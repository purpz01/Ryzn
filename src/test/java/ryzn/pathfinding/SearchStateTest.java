package ryzn.pathfinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;

class SearchStateTest {
  @Test
  void staleCompletionCannotPublishOrFinishTheReplacement() {
    var state = new SearchState<String>();
    long first = state.generation();
    var oldTask = new FutureTask<Void>(() -> null);
    state.start(oldTask);
    assertSame(oldTask, state.invalidate());

    long second = state.generation();
    state.start(new FutureTask<>(() -> null));
    state.finish(first, "stale route", null);
    assertNull(state.outcome().result());
    assertTrue(state.isSearching());

    state.finish(second, "new route", null);
    assertEquals("new route", state.outcome().result());
    assertFalse(state.isSearching());
  }

  @Test
  void canceledCompletionCannotReplaceTheLastCompletedOutcome() {
    var state = new SearchState<String>();
    state.finish(state.generation(), "last route", null);
    state.start(new FutureTask<>(() -> null));
    long canceled = state.generation();
    state.invalidate();
    state.finish(canceled, null, "late failure");

    assertEquals("last route", state.outcome().result());
    assertNull(state.outcome().error());
    assertFalse(state.isSearching());
  }
}
