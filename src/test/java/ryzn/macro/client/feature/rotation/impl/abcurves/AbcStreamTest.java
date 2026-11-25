package ryzn.macro.client.feature.rotation.impl.abcurves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class AbcStreamTest {
  @Test
  void commitsBlocksAndReceivesTargetsCausally() {
    var stream =
        new AbcStream(
            (history, x, y, time) -> {
              double[][] result = new double[32][2];
              for (double[] sample : result) {
                sample[0] = history.goalX(639);
              }
              return result;
            });
    stream.reset(0, 0);
    stream.updateTarget(1, 0, 0);
    stream.updateTarget(2, 0, 31999);
    stream.updateTarget(3, 0, 32000);
    stream.updateTarget(4, 0, 32001);
    assertEquals(0, stream.advance(17).size());
    assertEquals(new AbcStream.Sample(31000, 31, 0), stream.advance(31999).getLast());
    assertEquals(new AbcStream.Sample(32000, 32, 0), stream.advance(32000).getLast());
    assertEquals(new AbcStream.Sample(33000, 35, 0), stream.advance(33000).getLast());
    assertEquals(new AbcStream.Sample(64000, 128, 0), stream.advance(64000).getLast());
    assertEquals(new AbcStream.Sample(65000, 132, 0), stream.advance(65000).getLast());
    assertThrows(IllegalArgumentException.class, () -> stream.updateTarget(1, 0, 64999));
    assertThrows(IllegalArgumentException.class, () -> stream.advance(64999));
  }

  @Test
  void remainsStillBeforeTargetAndDiscardsStateOnReset() {
    var stream =
        new AbcStream(
            (history, x, y, time) -> {
              throw new AssertionError("Uncommanded movement invoked the planner");
            });
    stream.reset(11, -8);
    stream.updateTarget(20, 1, 32000);
    assertEquals(new AbcStream.Sample(31000, 11, -8), stream.advance(31999).getLast());
    stream.reset(5, 7);
    assertEquals(new AbcStream.Sample(100000, 5, 7), stream.advance(100000).getLast());
  }
}
