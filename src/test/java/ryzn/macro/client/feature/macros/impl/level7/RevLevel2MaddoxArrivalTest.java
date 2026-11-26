package ryzn.macro.client.feature.macros.impl.level7;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RevLevel2MaddoxArrivalTest {
  @Test
  void acceptsReachableMaddoxFromTheAdjacentBlock() {
    assertTrue(RevLevel2Macro.isWithinMaddoxArrival(-82.5, 67, -132.5, true, true));
  }

  @Test
  void doesNotAcceptAnObstructedNpcJustBecauseTheWaypointWasReached() {
    assertFalse(RevLevel2Macro.isWithinMaddoxArrival(-82.5, 67, -131.5, true, false));
  }

  @Test
  void allowsMenuStateToWaitForAnNpcThatHasNotLoadedAtTheWaypoint() {
    assertTrue(RevLevel2Macro.isWithinMaddoxArrival(-82.5, 67, -131.5, false, false));
    assertFalse(RevLevel2Macro.isWithinMaddoxArrival(-82.5, 67, -132.5, false, false));
  }
}
