package ryzn.macro.client.world;

import com.mojang.logging.LogUtils;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.slf4j.Logger;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.ClientDisconnectEvent;
import ryzn.macro.client.core.events.api.EndClientTickEvent;
import ryzn.macro.client.core.events.api.WorldChangeEvent;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.util.text.MacroText;

/**
 * Passive "which world am I in" detector for Hypixel SkyBlock. Fully read-only: it never sends,
 * blocks, delays, or alters a packet, so it is invisible to the server (the same anti-detection
 * rule the pathfinder cache lives by). Two signals are fused:
 *
 * <ul>
 *   <li><b>Edge (a server instance changed):</b> the inbound login/respawn packet, observed off the
 *       existing {@code ClientConnectionMixin} tap via {@link #onInboundPacket}. Every Hypixel
 *       server hop (lobby swap, warp, island change) routes through a fresh login/respawn, so this
 *       is the earliest, most deterministic "world changed" signal. {@link
 *       System#identityHashCode(Object)} of the {@link ClientLevel} is a belt-and-suspenders
 *       fallback for the same edge.
 *   <li><b>Content (which area):</b> the parsed TAB "Area:" line, read from already received client
 *       state.
 * </ul>
 *
 * <p>The packet only flips a volatile flag on the network thread; all listener firing happens on
 * the client thread from the {@code END_CLIENT_TICK} poll, so listeners may safely touch Minecraft
 * state. Listeners subscribe to {@link WorldChangeEvent} on the event bus; the current location is
 * also exposed via {@link #current}.
 */
public final class WorldChangeDetector {

  private static final Logger logger = LogUtils.getLogger();

  // Network thread -> client thread handoff: the login/respawn packet sets this; the tick poll
  // consumes it.
  private static volatile boolean packetBoundary = false;
  // Client-thread-only identity of the live world; changes on every login/respawn (new
  // ClientLevel).
  private static int levelId = 0;
  // Last published location. Volatile so other features can read current()/currentArea()
  // off-thread.
  private static volatile Location current = Location.NONE;

  private WorldChangeDetector() {}

  /**
   * The world the client is in, identified by its SkyBlock area (null when none is detectable, e.g.
   * a lobby). The dimension is deliberately not tracked: on SkyBlock everything runs in {@code
   * minecraft:overworld}, so it never changes and carries no signal. World-instance hops are
   * detected via the login/respawn edge and the {@link ClientLevel} identity, not the dimension.
   */
  public record Location(String area) {
    public static final Location NONE = new Location(null);
  }

  // --- registration --------------------------------------------------------

  /** Call once during client init. Packet feeding is driven by {@code ClientConnectionMixin}. */
  public static void register() {
    RyznBootstrap.eventBus()
        .subscribe(EndClientTickEvent.class, event -> onClientTick(event.client()));
    RyznBootstrap.eventBus().subscribe(ClientDisconnectEvent.class, event -> onDisconnect());
  }

  public static Location current() {
    return current;
  }

  /**
   * The current SkyBlock area, or null when none is detectable (e.g. a lobby or outside SkyBlock).
   */
  public static String currentArea() {
    return current.area();
  }

  // --- packet ingestion (network thread, read-only) ------------------------

  /**
   * Forwarded from {@code ClientConnectionMixin} for every inbound S2C packet. Only flags a
   * world-session boundary on login/respawn; nothing here touches Minecraft state, so it is safe
   * off the client thread.
   */
  public static void onInboundPacket(Packet<?> packet) {
    if (packet instanceof ClientboundBundlePacket bundle) {
      for (Packet<?> sub : bundle.subPackets()) {
        onInboundPacket(sub);
      }
    } else if (packet instanceof ClientboundLoginPacket
        || packet instanceof ClientboundRespawnPacket) {
      packetBoundary = true;
    }
  }

  // --- detection (client tick thread) --------------------------------------

  private static void onClientTick(Minecraft mc) {
    final ClientLevel level = mc.level;
    if (level == null) {
      return;
    } // teardown is handled by the DISCONNECT event

    final int id = System.identityHashCode(level);
    final boolean newInstance = id != levelId || packetBoundary;
    packetBoundary = false;

    final Location resolved = resolve(mc, newInstance);
    if (!newInstance && resolved.equals(current)) {
      return;
    }

    final Location previous = current;
    current = resolved;
    levelId = id;
    fire(previous, resolved, newInstance);
  }

  private static Location resolve(Minecraft mc, boolean newInstance) {
    String area = readArea(mc);
    // Sticky area within the same instance: a one-tick TAB read failure (area momentarily null)
    // must not
    // look like an area change. A real warp comes with a new instance (newInstance == true), which
    // clears
    // the area until the new TAB line arrives, so stickiness only masks the transient blip.
    if (area == null && !newInstance && current.area() != null) {
      area = current.area();
    }
    return new Location(area);
  }

  /**
   * The current SkyBlock area read from the player-list (TAB) "Area:" line, or null when absent.
   * Hypixel renders it as a fake player entry's tab name; it is island-level and stable. Read-only.
   */
  private static String readArea(Minecraft mc) {
    final ClientPacketListener conn = mc.getConnection();
    if (conn == null) {
      return null;
    }
    for (PlayerInfo info : conn.getOnlinePlayers()) {
      final Component name = info.getTabListDisplayName();
      if (name == null) {
        continue;
      }
      final String line = MacroText.cleanDecoratedText(name.getString());
      final int marker = line.toLowerCase(Locale.ROOT).indexOf("area:");
      if (marker < 0) {
        continue;
      }
      final String area = line.substring(marker + 5).trim();
      if (!area.isEmpty()) {
        return area.length() > 32 ? area.substring(0, 32).trim() : area;
      }
    }
    return null;
  }

  private static void onDisconnect() {
    // Reset silently so the next join re-fires from NONE; listeners that touch MC state are spared
    // a fire
    // while the player/level are being torn down.
    levelId = 0;
    packetBoundary = false;
    current = Location.NONE;
  }

  private static void fire(Location previous, Location current, boolean newInstance) {
    logger.info(
        "[ryzn-world] {} change: area={} (was area={})",
        newInstance ? "instance" : "area",
        current.area(),
        previous.area());
    DebugLog.chat(
        DebugLog.Category.NAV,
        "Moved to a new %s - now in %s",
        newInstance ? "server" : "area",
        String.valueOf(current.area()));
    RyznBootstrap.eventBus().post(new WorldChangeEvent(previous, current, newInstance));
  }
}
