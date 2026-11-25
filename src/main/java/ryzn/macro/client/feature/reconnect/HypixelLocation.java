package ryzn.macro.client.feature.reconnect;

import com.mojang.logging.LogUtils;
import net.hypixel.data.type.GameType;
import net.hypixel.data.type.ServerType;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;
import org.slf4j.Logger;

/**
 * Where Hypixel says we are, taken from Hypixel's own mod API rather than scraped off the
 * scoreboard.
 *
 * <p>The scoreboard could not answer this at all. "SkyBlock" lives in the sidebar OBJECTIVE title,
 * never in a score line, so a lines-only search was false even while standing on the island: the
 * walk back in {@link MacroReconnect} never finished, {@code /skyblock} was resent forever, and the
 * macro never resumed. Every string-matching alternative (the area line, the footer URL) is the
 * same class of guess with the same failure mode - this is the server telling us directly.
 *
 * <p>Anti-detection: the {@code hypixel:} channel is Hypixel's own, published for exactly this
 * purpose and used by every large SkyBlock mod. It carries no movement or interaction packet and
 * says nothing about automation; the only thing it reveals is that a mod is installed, which
 * Hypixel permits.
 */
public final class HypixelLocation {

  private static final Logger logger = LogUtils.getLogger();

  /** Written on the network thread by the mod API, read on the client thread. */
  private static volatile ServerType serverType = null;

  private static volatile String serverName = "";

  private HypixelLocation() {}

  /**
   * Must run before the first connection: the subscription is what tells Hypixel to push a location
   * on every server switch, including the initial join.
   */
  public static void register() {
    HypixelModAPI.getInstance().subscribeToEventPacket(ClientboundLocationPacket.class);
    HypixelModAPI.getInstance()
        .createHandler(
            ClientboundLocationPacket.class,
            packet -> {
              serverType = packet.getServerType().orElse(null);
              serverName = packet.getServerName();
              logger.info(
                  "[ryzn-location] {} type={}",
                  serverName,
                  serverType == null ? "unknown" : serverType.name());
            });
  }

  /** True while Hypixel reports the current server is a SkyBlock one. */
  public static boolean isSkyBlock() {
    return serverType == GameType.SKYBLOCK;
  }

  /**
   * The raw server id, e.g. {@code mini196AK} or {@code limbo}. Empty until the first location
   * arrives.
   */
  public static String serverName() {
    return serverName;
  }
}
