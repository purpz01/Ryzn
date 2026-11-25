package ryzn.macro.client.feature.failsafe;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum FailsafeType {
  INVENTORY_SLOT("Hotbar Changed"),
  UNEXPECTED_SCREEN("Unexpected GUI"),
  GHOST_BLOCK("Ghost Block"),
  DIRT("Dirt Check"),
  ROTATION("Forced Rotation"),
  WORLD_CHANGE("World Change"),
  PROXIMITY("Player Nearby");

  private final String label;
}
