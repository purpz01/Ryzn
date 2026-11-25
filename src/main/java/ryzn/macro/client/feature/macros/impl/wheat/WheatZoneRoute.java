package ryzn.macro.client.feature.macros.impl.wheat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/** Owns the fixed wheat-zone itinerary and progress through the current pass. */
final class WheatZoneRoute {
  private static final BlockPos[] centers = {
    new BlockPos(58, 71, -71),
    new BlockPos(75, 72, -73),
    new BlockPos(58, 71, -88),
    new BlockPos(48, 72, -98),
    new BlockPos(37, 72, -95),
    new BlockPos(32, 71, -86)
  };

  private int index;
  private int completed;

  BlockPos currentCenter() {
    return centers[index];
  }

  BlockPos firstCenter() {
    return centers[0];
  }

  int currentNumber() {
    return index + 1;
  }

  int zoneCount() {
    return centers.length;
  }

  void completeCurrent() {
    completed++;
  }

  boolean allCompleted() {
    return completed >= centers.length;
  }

  void advance() {
    index = (index + 1) % centers.length;
  }

  void reset() {
    index = 0;
    completed = 0;
  }

  boolean isInside(LocalPlayer player, double radius) {
    return horizontalDistanceSquared(player) <= radius * radius;
  }

  boolean isAtY(LocalPlayer player, double tolerance) {
    return Math.abs(player.getY() - currentCenter().getY()) <= tolerance;
  }

  private double horizontalDistanceSquared(LocalPlayer player) {
    BlockPos center = currentCenter();
    double dx = (center.getX() + 0.5) - player.getX();
    double dz = (center.getZ() + 0.5) - player.getZ();
    return dx * dx + dz * dz;
  }
}
