package ryzn.macro.client.feature.macros.impl.wheat;

import net.minecraft.world.phys.Vec3;

/** Holds the look target shared by the wheat harvest and merchant phases. */
final class WheatLookMotion {
  private Vec3 target;

  Vec3 target() {
    return target;
  }

  void setTarget(Vec3 target) {
    this.target = target;
  }

  boolean hasTarget() {
    return target != null;
  }

  void clearTarget() {
    target = null;
  }
}
