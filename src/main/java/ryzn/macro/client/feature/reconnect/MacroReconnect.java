package ryzn.macro.client.feature.reconnect;

import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.EndClientTickEvent;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.ReconnectDisarmEvent;
import ryzn.macro.client.core.events.api.ReconnectRecoveryStartEvent;
import ryzn.macro.client.core.events.api.ReconnectRejoinEvent;
import ryzn.macro.client.feature.commands.impl.MacroCommands;
import ryzn.macro.client.feature.macros.impl.MacroManagerImpl;
import ryzn.macro.client.util.text.RyznChat;
import ryzn.macro.client.world.WorldChangeDetector;

/**
 * Gets an unattended grind back to SkyBlock after the three ways Hypixel takes it away: a dropped
 * connection, a limbo route, and a kick into the SkyBlock lobby. Global and macro-agnostic: it only
 * cares THAT a macro was running, never which one. Where to go once SkyBlock is reached is left to
 * the {@link ReconnectRejoinEvent} subscribers.
 *
 * <p>A drop is decided by the screen - vanilla shows a {@link DisconnectedScreen} when the
 * connection is lost and the title screen when the player quits on purpose. Limbo and kicks never
 * produce a screen at all (the socket stays up, the server just moves you), so those are read from
 * chat, which is how SkyHanni, aether and V5 all detect them.
 *
 * <p>"A macro was running" is LATCHED, not sampled. Hypixel's real eviction order is world-change
 * first, socket death second, and our own world-change failsafe stops the macro in between -
 * sampling at the moment the disconnect screen appears always saw "nothing running" and never
 * rearmed. Only a deliberate {@link #disarm()} clears it.
 *
 * <p>The way back is {@code /lobby}, {@code /play skyblock}, then {@code /hub}. Stages are gated on
 * the world ACTUALLY changing and on Hypixel ACTUALLY reporting SkyBlock ({@link HypixelLocation})
 * rather than on a fixed delay - under the lag that caused the drop, a timer fires into a world
 * that has not caught up yet. Retries slow down instead of giving up, because "slow" and "dead"
 * look identical from here and only one of them is worth abandoning a session over.
 *
 * <p>Anti-detection: the rejoin is vanilla's own {@link ConnectScreen} join flow, the same code
 * path as a human clicking the server - no packet is crafted, blocked or replayed. Every delay is
 * randomized.
 */
@Accessors(fluent = true)
public final class MacroReconnect {

  private static final Logger logger = LogUtils.getLogger();

  /**
   * First rejoin waits this long; each further failed attempt doubles it, up to {@link
   * #MAX_DELAY_MS}.
   */
  private static final long BASE_DELAY_MS = 8_000L;

  private static final long MAX_DELAY_MS = 5 * 60_000L;

  /**
   * Settle time in-world before the first command, measured from the tick the player actually
   * spawns.
   */
  private static final long JOIN_SETTLE_MS = 5_000L;

  /** Limbo is entered well before the server will take a command, so let it breathe first. */
  private static final long LIMBO_SETTLE_MS = 10_000L;

  /**
   * Hypixel refuses SkyBlock for about a minute after a kick, so a retry inside that window is
   * wasted.
   */
  private static final long KICK_HOLD_MS = 65_000L;

  /** A stage waits this long for proof it landed, then asks again at the same cadence. */
  private static final long STAGE_TIMEOUT_MS = 10_000L;

  /** How long after the last running tick a silent eviction still counts as "this run was live". */
  private static final long RUN_WINDOW_MS = 60_000L;

  /** Where the chain is on the way back to SkyBlock. */
  private enum Stage {
    IDLE,
    SETTLE,
    LOBBY,
    SKYBLOCK,
    HUB
  }

  /** Captured while connected: {@code Minecraft} clears its own copy as part of the disconnect. */
  private static ServerData lastServer = null;

  /** Latched "a macro was running and nobody deliberately stopped it". See the class note. */
  private static boolean armed = false;

  /** Set when a rejoin is in flight, so the settle can start on the tick the player exists. */
  private static boolean joinPending = false;

  private static int attempts = 0;

  /**
   * Wall clock, so the on-screen countdown is smooth at any frame rate. 0 when nothing is
   * scheduled.
   */
  private static long reconnectAtMs = 0L;

  /** No {@code /skyblock} before this: the server is still refusing us after a kick. */
  private static long skyblockHoldUntilMs = 0L;

  /**
   * Wall clock of the last tick any macro was running, so a stopped-and-wandering player is left
   * alone.
   */
  private static long lastMacroRunningMs = 0L;

  /** Wall clock this client first went off SkyBlock with a run live, or 0 while it is on one. */
  private static long offSkyBlockSinceMs = 0L;

  private static Stage stage = Stage.IDLE;

  /**
   * Wall clock the current recovery left IDLE, so listeners can ask "was it live when this
   * STARTED".
   *
   * <p>For listeners deciding whether the interrupted session was theirs. Measure against THIS,
   * never against arrival: the walk back can take minutes on the same bad line that caused it, and
   * by then every "was it running recently" window has expired.
   */
  @Getter private static long recoveryStartedMs = 0L;

  @Getter private static long recoveryGeneration = 0L;
  private static boolean recoveryActive = false;
  private static List<Runnable> recentStarts = List.of();
  private static List<Runnable> pendingStarts = List.of();
  private static long stageDeadlineMs = 0L;
  private static int stageSends = 0;

  /** Identity of the level the current stage was started in; a change means the command landed. */
  private static int stageLevelId = 0;

  private MacroReconnect() {}

  public static void register() {
    RyznBootstrap.eventBus()
        .subscribe(EndClientTickEvent.class, event -> onClientTick(event.client()));
    RyznBootstrap.eventBus()
        .subscribe(
            GameMessageEvent.class, event -> onGameMessage(event.message(), event.overlay()));
  }

  /**
   * Whether a {@link ReconnectRejoinEvent} still belongs to the live recovery. Every subscriber
   * checks this first, so one that disarms or restarts recovery stops the later ones.
   */
  public static boolean rejoinStillCurrent(long generation) {
    return recoveryActive && recoveryGeneration == generation;
  }

  public static void registerRecoveryListeners(Runnable onRejoin, Runnable disarm) {
    RyznBootstrap.eventBus()
        .subscribe(
            ReconnectRejoinEvent.class,
            event -> {
              if (MacroReconnect.rejoinStillCurrent(event.generation())) {
                onRejoin.run();
              }
            });
    RyznBootstrap.eventBus().subscribe(ReconnectDisarmEvent.class, event -> disarm.run());
  }

  /** The user stopping automation on purpose - the only thing that cancels a pending recovery. */
  public static void disarm() {
    final boolean wasArmed = armed || isRecovering();
    armed = false;
    joinPending = false;
    stage = Stage.IDLE;
    reconnectAtMs = 0L;
    recoveryActive = false;
    lastMacroRunningMs = 0L;
    offSkyBlockSinceMs = 0L;
    recoveryStartedMs = 0L;
    recentStarts = List.of();
    pendingStarts = List.of();
    RyznBootstrap.eventBus().post(new ReconnectDisarmEvent());
    if (wasArmed) {
      logger.info("[ryzn-reconnect] disarmed by the user");
    }
  }

  /** True from the moment an eviction is seen until SkyBlock is reached again. */
  public static boolean isRecovering() {
    return stage != Stage.IDLE || reconnectAtMs != 0L || joinPending;
  }

  /** Where the walk back is, for the macro HUD card that outlives the run it interrupted. */
  public static String status() {
    if (reconnectAtMs != 0L) {
      return "Reconnecting in "
          + Math.max(0L, reconnectAtMs - System.currentTimeMillis()) / 1000
          + "s";
    }
    if (joinPending) {
      return "Rejoining the server";
    }
    return switch (stage) {
      case IDLE -> "Idle";
      case SETTLE -> "Knocked out of SkyBlock - settling";
      case LOBBY -> "Returning to the lobby";
      case SKYBLOCK ->
          System.currentTimeMillis() < skyblockHoldUntilMs
              ? "Sitting out the kick cooldown"
              : "Entering SkyBlock";
      case HUB -> "Warping to the Hub";

      default -> throw new MatchException(null, null);
    };
  }

  // --- tick ----------------------------------------------------------------

  private static void onClientTick(Minecraft mc) {
    if (mc.player != null) {
      onInWorldTick(mc);
      return;
    }
    if (!(mc.gui.screen() instanceof DisconnectedScreen) || !armed || lastServer == null) {
      reconnectAtMs = 0L;
      return;
    }
    if (reconnectAtMs == 0L) {
      scheduleReconnect();
      return;
    }
    if (System.currentTimeMillis() < reconnectAtMs) {
      return;
    }
    reconnect(mc);
  }

  private static void onInWorldTick(Minecraft mc) {
    attempts = 0;
    reconnectAtMs = 0L;
    final ServerData server = mc.getCurrentServer();
    if (server != null) {
      lastServer = server;
    }
    // Latched, never cleared here: by the time a drop is visible the macro has already been
    // stopped.
    if (RyznBootstrap.macroManager().anyMacroRunning()) {
      armed = true;
      lastMacroRunningMs = System.currentTimeMillis();
      if (stage == Stage.IDLE && !recoveryActive) {
        recentStarts = ((MacroManagerImpl) RyznBootstrap.macroManager()).captureReconnectStarts();
      }
    } else if (stage != Stage.IDLE) {
      armed = true;
    } else if (!recoveryActive && inSkyBlock()) {
      recentStarts = List.of();
    }
    watchForSilentEviction();
    if (joinPending) {
      // Only now is the settle worth anything - it used to start at connect time and expire during
      // login.
      joinPending = false;
      enterStage(Stage.SETTLE, JOIN_SETTLE_MS);
    }
    if (stage != Stage.IDLE) {
      tickStage(mc);
    }
  }

  /**
   * Being off SkyBlock IS the eviction, whatever chat said about it. Hypixel routes to limbo
   * silently often enough that waiting for {@link EvictionLines} to match has left a run parked
   * there all night, and that failure is invisible - the same "never gate on a single signal that
   * can go silently false" lesson that put the TAB area next to the mod API in {@link
   * #inSkyBlock()}.
   *
   * <p>The signature is "a run was live a moment ago and we are still off SkyBlock after the
   * settle." A normal warp can briefly clear the TAB area, but should not remain off SkyBlock for
   * the whole settle. The recent-running window keeps an old armed latch from starting a walk back
   * after a later manual trip to the lobby.
   */
  private static void watchForSilentEviction() {
    final long now = System.currentTimeMillis();
    if (stage != Stage.IDLE || !armed || inSkyBlock() || now - lastMacroRunningMs > RUN_WINDOW_MS) {
      offSkyBlockSinceMs = 0L;
      return;
    }
    if (offSkyBlockSinceMs == 0L) {
      offSkyBlockSinceMs = now;
      return;
    }
    if (now - offSkyBlockSinceMs < LIMBO_SETTLE_MS) {
      return;
    }
    offSkyBlockSinceMs = 0L;
    logger.info(
        "[ryzn-reconnect] off SkyBlock for {}s with a run live - walking back in without a chat "
            + "line",
        LIMBO_SETTLE_MS / 1000);
    RyznChat.send("§bReconnect§7: §eoff SkyBlock with a macro running §7- walking back in.");
    beginRecovery();
    send(Minecraft.getInstance(), Stage.LOBBY, "lobby");
  }

  /**
   * Back off between attempts: a server refusing us now is not helped by asking again in 8 seconds.
   */
  private static void scheduleReconnect() {
    beginRecovery();
    final long base = Math.min(BASE_DELAY_MS << Math.min(attempts, 5), MAX_DELAY_MS);
    reconnectAtMs =
        System.currentTimeMillis() + base + ThreadLocalRandom.current().nextLong(base / 2 + 1);
    logger.info(
        "[ryzn-reconnect] drop seen with a macro armed, rejoining in ~{}s (attempt {})",
        base / 1000,
        attempts + 1);
  }

  private static void reconnect(Minecraft mc) {
    final ServerData server = lastServer;
    attempts++;
    reconnectAtMs = 0L;
    stage = Stage.IDLE;
    joinPending = true;
    RyznChat.send(
        "§bReconnect§7: §eyou were dropped with a macro running §7- rejoining "
            + server.ip
            + " (attempt "
            + attempts
            + ").");
    logger.info("[ryzn-reconnect] attempt {} to {}", attempts, server.ip);
    ConnectScreen.startConnecting(
        new TitleScreen(), mc, ServerAddress.parseString(server.ip), server, false, null);
  }

  // --- chat: limbo and kicks, neither of which produces a screen -------------

  private static void onGameMessage(Component message, boolean overlay) {
    if (overlay || message == null) {
      return;
    }
    final String raw = message.getString();
    if (EvictionLines.isKick(raw)) {
      skyblockHoldUntilMs = System.currentTimeMillis() + KICK_HOLD_MS;
      logger.info(
          "[ryzn-reconnect] kicked - holding SkyBlock retries for {}s", KICK_HOLD_MS / 1000);
      startWalkBack(
          KICK_HOLD_MS,
          "§bReconnect§7: §ekicked out of SkyBlock §7- sitting out the "
              + "cooldown, then walking back in.");
      return;
    }
    if (EvictionLines.isEviction(raw)) {
      startWalkBack(
          LIMBO_SETTLE_MS,
          "§bReconnect§7: §eknocked out of SkyBlock with a macro running "
              + "§7- walking back in.");
    }
  }

  /** Evicted but still connected, so there is nothing to rejoin - go straight to the walk back. */
  private static void startWalkBack(long settleMs, String message) {
    if (!armed || stage != Stage.IDLE) {
      return;
    }
    if (Minecraft.getInstance().player == null) {
      return;
    } // a real drop: the screen path owns it
    beginRecovery();
    RyznChat.send(message);
    logger.info("[ryzn-reconnect] walk back armed, settling {}s first", settleMs / 1000);
    enterStage(Stage.SETTLE, settleMs);
  }

  // --- the walk back to SkyBlock -------------------------------------------

  private static void tickStage(Minecraft mc) {
    final long now = System.currentTimeMillis();
    final boolean worldChanged =
        mc.level != null && System.identityHashCode(mc.level) != stageLevelId;
    final boolean timedOut = now >= stageDeadlineMs;
    switch (stage) {
      case SETTLE -> {
        if (!timedOut) {
          return;
        }
        // Hypixel often puts you straight back on your island, in which case there is nothing to
        // do.
        if (inSkyBlock()) {
          send(mc, Stage.HUB, "hub");
        } else {
          send(mc, Stage.LOBBY, "lobby");
        }
      }
      // A lobby hop is done the moment the world swaps; only resend if nothing happened at all.
      case LOBBY -> {
        if (worldChanged || timedOut) {
          send(mc, Stage.SKYBLOCK, "play skyblock");
        }
      }
      // Hypixel's location is the real proof of arrival: the world swaps into a loading limbo
      // first.
      case SKYBLOCK -> {
        if (inSkyBlock()) {
          send(mc, Stage.HUB, "hub");
        } else if (now < skyblockHoldUntilMs) {
          stageDeadlineMs = skyblockHoldUntilMs;
        } else if (timedOut) {
          resend(mc, "play skyblock");
        }
      }
      case HUB -> {
        if (inHub() || (worldChanged && inSkyBlock())) {
          finish("§bReconnect§7: §aback in the Hub §7- handing control back to the macro.");
        } else if (timedOut) {
          resend(mc, "hub");
        }
      }
      case IDLE -> {}

      default -> {}
    }
  }

  private static void send(Minecraft mc, Stage next, String command) {
    enterStage(next, STAGE_TIMEOUT_MS);
    if (next == Stage.SKYBLOCK && System.currentTimeMillis() < skyblockHoldUntilMs) {
      stageDeadlineMs = skyblockHoldUntilMs; // still kicked: the command would only bounce
      return;
    }
    stageSends = 1;
    MacroCommands.sendCommand(mc, command);
  }

  /**
   * Ask again, but slower each time. Never gives up: under a bad line, slow reads exactly like
   * dead.
   */
  private static void resend(Minecraft mc, String command) {
    stageSends++;
    stageDeadlineMs = System.currentTimeMillis() + STAGE_TIMEOUT_MS;
    logger.info("[ryzn-reconnect] {} did not land, resend #{}", command, stageSends);
    MacroCommands.sendCommand(mc, command);
  }

  private static void enterStage(Stage next, long timeoutMs) {
    final Minecraft mc = Minecraft.getInstance();
    stage = next;
    stageSends = 0;
    stageDeadlineMs = System.currentTimeMillis() + timeoutMs;
    stageLevelId = mc.level == null ? 0 : System.identityHashCode(mc.level);
  }

  private static void finish(String message) {
    stage = Stage.IDLE;
    skyblockHoldUntilMs = 0L;
    RyznChat.send(message);
    logger.info("[ryzn-reconnect] back in SkyBlock, handing over to the rejoin listeners");
    long generation = recoveryGeneration;
    RyznBootstrap.eventBus().post(new ReconnectRejoinEvent(generation));
    if (recoveryActive && recoveryGeneration == generation) {
      for (Runnable start : pendingStarts) {
        if (!recoveryActive || recoveryGeneration != generation) {
          break;
        }
        try {
          start.run();
        } catch (Exception | LinkageError failure) {
          logger.warn("[ryzn-reconnect] macro restart failed", failure);
        }
      }
    }
    pendingStarts = List.of();
    recentStarts = List.of();
    if (recoveryGeneration == generation) {
      recoveryActive = false;
    }
  }

  private static void beginRecovery() {
    if (recoveryActive) {
      return;
    }
    recoveryGeneration = nextRecoveryGeneration(recoveryGeneration, false);
    recoveryStartedMs = System.currentTimeMillis();
    recoveryActive = true;
    pendingStarts =
        recoveryStartedMs - lastMacroRunningMs <= RUN_WINDOW_MS ? recentStarts : List.of();
    RyznBootstrap.eventBus().post(new ReconnectRecoveryStartEvent(recoveryGeneration));
    if (RyznBootstrap.macroManager().anyMacroRunning()) {
      RyznBootstrap.failsafeManager()
          .stopAllMacros("World Change: kicked out of SkyBlock during reconnect");
    }
  }

  static long nextRecoveryGeneration(long generation, boolean recoveryActive) {
    return recoveryActive ? generation : generation + 1L;
  }

  /**
   * Two answers, either of which is proof. {@link HypixelLocation} is the clean one, but it goes
   * SILENT under a channel-hiding mod (OpSec's {@code spoofAsVanilla}): the location event never
   * arrives, this stayed false while standing in The Park, and {@code /skyblock} was resent every
   * minute all night while the server answered "You are already playing SkyBlock!". The TAB "Area:"
   * line is only ever populated on a SkyBlock server - null in limbo and null in a Hypixel lobby -
   * so it covers exactly the gap.
   */
  private static boolean inSkyBlock() {
    return HypixelLocation.isSkyBlock() || WorldChangeDetector.currentArea() != null;
  }

  private static boolean inHub() {
    String area = WorldChangeDetector.currentArea();
    if (!inSkyBlock() || area == null) {
      return false;
    }
    String lower = area.toLowerCase(Locale.ROOT);
    return lower.contains("hub") || lower.equals("village");
  }
}
