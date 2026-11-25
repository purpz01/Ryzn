package ryzn.macro.client.feature.pathfinder.path;

/** Plain walking: jumps only where the route or a wall-grind needs one, never on a rhythm. */
final class WalkMovementMode implements GroundMovementMode {

  static final WalkMovementMode INSTANCE = new WalkMovementMode();

  private WalkMovementMode() {}

  @Override
  public String label() {
    return "walk";
  }

  @Override
  public PathSpeedProfile shape(PathSpeedProfile profile) {
    return profile;
  }

  @Override
  public boolean holdsConstantJump(boolean safeToHop) {
    return false;
  }
}
