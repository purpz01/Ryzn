package ryzn.macro.client.feature.pathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import ryzn.macro.client.feature.pathfinder.path.PathNode;
import ryzn.pathfinding.PathManager;

class WalkingRouteBuilderTest {
  @Test
  void keepsFullBlockAscentsAndLetsHalfStepsAutoStep() {
    int[] points = {0, 64, 0, 1, 65, 0, 2, 66, 0};
    var result =
        new PathManager.RouteData(points, points, new int[] {0, PathManager.FLAG_STEP_UP_HALF, 0});

    var route = WalkingRouteBuilder.build(null, result, 3);

    assertEquals(3, route.nodes().size());
    assertFalse(route.nodes().get(0).jumpUp());
    assertTrue(route.nodes().get(1).jumpUp());
    assertFalse(route.nodes().get(2).jumpUp());
    assertEquals(66, route.nodes().getLast().cell().getY());
  }

  @Test
  void retainsWaterBoundariesEvenWhenTheyAreBetweenEngineKeyNodes() {
    int[] points = {0, 64, 0, 1, 64, 0, 2, 64, 0, 3, 64, 0};
    int[] keys = {0, 64, 0, 3, 64, 0};
    int[] flags = {0, PathManager.FLAG_FLUID_FEET, PathManager.FLAG_SWIM_SURFACE, 0};

    var route = WalkingRouteBuilder.build(null, new PathManager.RouteData(points, keys, flags), 8);

    assertEquals(4, route.nodes().size());
    assertEquals(PathNode.Medium.WADE, route.nodes().get(1).medium());
    assertEquals(PathNode.Medium.SWIM_SURFACE, route.nodes().get(2).medium());
    assertEquals(65.0, route.nodes().get(2).center().y);
  }
}
