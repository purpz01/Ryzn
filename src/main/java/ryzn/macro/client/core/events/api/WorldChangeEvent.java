package ryzn.macro.client.core.events.api;

import ryzn.macro.client.world.WorldChangeDetector.Location;

/**
 * The world changed, on the client thread. The first attach after joining has {@code previous ==
 * Location.NONE}.
 *
 * @param previous the location before the change
 * @param current the location after the change
 * @param newInstance true for a server-instance hop, false when only the area name changed
 */
public record WorldChangeEvent(Location previous, Location current, boolean newInstance)
    implements Event {
  @Override
  public boolean fatalOnFailure() {
    return false;
  }
}
