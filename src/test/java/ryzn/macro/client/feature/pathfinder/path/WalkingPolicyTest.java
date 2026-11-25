package ryzn.macro.client.feature.pathfinder.path;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WalkingPolicyTest {
  @Test
  void keepsForwardWhileTheStrafeStillTravelsAhead() {
    assertTrue(WalkingPolicy.carryStrafeForward(true, 69f));
    assertTrue(WalkingPolicy.carryStrafeForward(true, -69f));
    assertFalse(WalkingPolicy.carryStrafeForward(false, 69f));
    assertTrue(WalkingPolicy.carryStrafeForward(true, 89f));
    assertFalse(WalkingPolicy.carryStrafeForward(true, 90f));
  }

  @Test
  void dropApproachDoesNotTapForwardAgainAfterCoasting() {
    var brake = new WalkingPolicy.DropBrake();
    assertFalse(brake.holdForwardOff(14, false, true));
    assertTrue(brake.holdForwardOff(14, true, true));
    assertTrue(brake.holdForwardOff(14, false, true));
    assertFalse(brake.holdForwardOff(14, true, false));
    assertFalse(brake.holdForwardOff(14, true, true));
    assertTrue(brake.holdForwardOff(15, true, true));
    assertFalse(brake.holdForwardOff(-1, false, false));
    assertTrue(brake.holdForwardOff(15, true, true));
  }

  @Test
  void sprintFollowsTheSettingUnlessCrouchingWasRequested() {
    assertTrue(WalkingPolicy.sprint(true, false));
    assertFalse(WalkingPolicy.sprint(false, false));
    assertFalse(WalkingPolicy.sprint(true, true));
  }

  @Test
  void arrivalRejectsGlideToTheSideOrAway() {
    assertTrue(WalkingPolicy.coastReaches(2, 0, 1, 0, 2, .45));
    assertFalse(WalkingPolicy.coastReaches(2, .6, 1, 0, 3, .45));
    assertFalse(WalkingPolicy.coastReaches(2, 0, -1, 0, 3, .45));
    assertFalse(WalkingPolicy.coastReaches(2, 0, 1, 0, 1, .45));
  }
}
