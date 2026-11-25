package ryzn.macro.client.feature.pathfinder.path;

/** Constant jumping: the hop is held on every tick the executor's gate calls safe. */
final class HopMovementMode implements GroundMovementMode {

  static final HopMovementMode INSTANCE = new HopMovementMode();

  private HopMovementMode() {}

  @Override
  public String label() {
    return "hop";
  }

  @Override
  public PathSpeedProfile shape(PathSpeedProfile profile) {
    return profile;
  }

  @Override
  public boolean holdsConstantJump(boolean safeToHop) {
    return safeToHop;
  }
}
