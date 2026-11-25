package ryzn.macro.client.feature.failsafe;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.config.groups.FailsafeSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.failsafe.api.Failsafe;

/**
 * Watches for another account loitering next to the bot. Someone walking past is normal traffic and
 * clears the dwell timer on its own; someone who parks on the grind spot is either competing for
 * the mobs or watching, and both are worth leaving over. The usual answer is {@link
 * FailsafeAction#HOP} - go through the hub and come back - rather than ending the session outright.
 */
public final class ProximityFailsafe extends Failsafe {
  /**
   * @description Creates the process-lifetime detector owned by the failsafe manager.
   */
  public ProximityFailsafe() {
    super("proximity", "Player Nearby", "Detects player nearby.");
  }

  private final FailsafeDelay delay = new FailsafeDelay();

  void tick(Minecraft client, long tick, FailsafeSettings settings) {
    final boolean crowded =
        client != null
            && client.player != null
            && client.level != null
            && settings.isEnabled()
            && settings.isProximityEnabled()
            && FailsafeActivity.foregroundActive()
            && nearbyPlayer(client, settings.getProximityRangeBlocks()) != null;
    // Dwell is the whole point of the detector, so it carries the delay itself; the shared random
    // delay
    // still rides on top so two sessions never leave on the same beat.
    if (delay.update(
        crowded,
        tick,
        settings.getProximityDwellSeconds() * 20,
        () -> FailsafeCoordinator.sampleAdditionalDelay(settings))) {
      final Player who = nearbyPlayer(client, settings.getProximityRangeBlocks());
      RyznBootstrap.failsafeManager()
          .trigger(
              FailsafeType.PROXIMITY,
              (who == null ? "someone" : who.getName().getString())
                  + " stayed within "
                  + settings.getProximityRangeBlocks()
                  + " blocks for "
                  + settings.getProximityDwellSeconds()
                  + "s");
    }
  }

  /**
   * Nearest OTHER account inside the radius; Hypixel NPCs and armour-stand holograms are not
   * players.
   */
  private static Player nearbyPlayer(Minecraft client, int range) {
    final double rangeSquared = (double) range * range;
    Player closest = null;
    double closestSquared = Double.MAX_VALUE;
    for (Player other : client.level.players()) {
      if (!MacroInput.isRealPlayer(client, other)) {
        continue;
      }
      final double distanceSquared = other.distanceToSqr(client.player);
      if (distanceSquared <= rangeSquared && distanceSquared < closestSquared) {
        closest = other;
        closestSquared = distanceSquared;
      }
    }
    return closest;
  }

  void reset() {
    delay.reset();
  }
}
